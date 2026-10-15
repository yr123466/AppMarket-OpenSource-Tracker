package top.app.market.data.remote.gitlab

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import top.app.market.data.platform.debugLog
import top.app.market.data.remote.opensource.OpenSourceTokenStore
import top.app.market.data.remote.opensource.compareVersionNames
import top.app.market.data.remote.opensource.normalizeReleaseVersion
import top.app.market.data.remote.opensource.parseApkAssetName
import top.app.market.data.remote.opensource.pickBestApk
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.model.tracked.TrackedRepo

internal data class GitlabApiConfig(
    val apiBaseUrl: String = "https://gitlab.com/api/v4",
    val webBaseUrl: String = "https://gitlab.com",
    val searchPageSize: Int = 20,
    val releasePageSize: Int = 10,
    val enrichSearchResults: Int = 3,
    val maxConcurrency: Int = 4,
)

private const val UserAgent = "AppMarket-Android"

/**
 * GitLab REST v4 只读客户端，与 [top.app.market.data.remote.github.GithubApi] 同构。
 *
 * 关键差别：GitLab 的发布附件放在 `assets.links[]`（而不是 `assets[]`），
 * 且项目定位一律走 URL 编码后的 `namespace%2Fproject`。
 */
internal class GitlabApi(
    private val client: HttpClient,
    private val tokens: OpenSourceTokenStore,
    private val config: GitlabApiConfig = GitlabApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(keyword: String, page: Int): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(emptyList(), hasMore = false)
        val targetPage = (page + 1).coerceAtLeast(1)
        val body = getJson(
            "/projects?search=${query.encodeURLParameter()}&per_page=${config.searchPageSize}" +
                "&page=$targetPage&order_by=star_count&sort=desc"
        )
        val projects = json.decodeFromString<List<GitlabProject>>(body).filterNot { it.archived }
        if (projects.isEmpty()) return SearchPage(emptyList(), hasMore = false)

        val enriched = enrich(projects.take(config.enrichSearchResults))
            .associate { (project, release) -> project.id to project.toAppInfo(release) }
        val items = projects.map { project -> enriched[project.id] ?: project.toAppInfo(release = null) }
        return SearchPage(
            items = items,
            hasMore = projects.size >= config.searchPageSize,
        )
    }

    suspend fun projectById(id: Long): GitlabProject = json.decodeFromString(getJson("/projects/$id"))

    suspend fun projectBySlug(slug: String): GitlabProject =
        json.decodeFromString(getJson("/projects/${slug.normalizedGitlabSlug().encodeURLParameter()}"))

    suspend fun releases(slug: String, limit: Int = config.releasePageSize): List<GitlabRelease> {
        val body = getJson("/projects/${slug.normalizedGitlabSlug().encodeURLParameter()}/releases?per_page=$limit")
        return json.decodeFromString<List<GitlabRelease>>(body)
            .filterNot { it.upcomingRelease }
            .sortedByDescending { it.releasedAt ?: it.createdAt ?: "" }
    }

    suspend fun tags(slug: String, limit: Int = config.releasePageSize): List<String> =
        json.decodeFromString<List<GitlabTag>>(
            getJson("/projects/${slug.normalizedGitlabSlug().encodeURLParameter()}/repository/tags?per_page=$limit")
        ).map { it.name }.filter { it.isNotBlank() }

    suspend fun latestRelease(slug: String): GitlabRelease? = releases(slug).firstOrNull()

    suspend fun appDetail(project: GitlabProject, release: GitlabRelease?): AppDetail {
        val app = project.toAppInfo(release)
        return AppDetail(
            app = app,
            brief = project.description.orEmpty(),
            introduction = project.description.orEmpty().ifBlank { project.slug },
            changeLog = release?.description.orEmpty(),
            category = project.topics.firstOrNull().orEmpty(),
            ageClassification = "",
            downloadCount = project.starCount.toLong(),
            registrationNum = project.license?.nickname ?: project.license?.name.orEmpty(),
            privacyUrl = "",
            screenshots = emptyList(),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val slug = app.gitlabSlugOrThrow()
        val release = latestRelease(slug)
            ?: throw MarketException("GitLab 项目 $slug 还没有发布版本")
        val links = release.assets?.links.orEmpty()
        val asset = pickBestApk(links, { it.name.ifBlank { it.downloadUrl.substringAfterLast('/') } }, { 0L })
            ?: throw MarketException("GitLab 项目 $slug 的最新发布里没有 APK 附件")
        val fileName = asset.name.ifBlank { asset.downloadUrl.substringAfterLast('/') }
        val identity = parseApkAssetName(fileName)
        val versionName = normalizeReleaseVersion(release.tagName)
        // GitLab 的附件元数据不带体积，直链 HEAD 才能拿到真实大小；
        // 拿不到时退回已解析到的体积，避免显示成 0。
        val size = contentLength(asset.downloadUrl).takeIf { it > 0L } ?: app.apkSize
        return DownloadMeta(
            appId = app.appId,
            packageName = identity?.packageName ?: app.packageName,
            displayName = app.displayName,
            versionName = versionName.ifBlank { app.versionName },
            versionCode = identity?.versionCode?.takeIf { it > 0L } ?: app.versionCode,
            url = asset.downloadUrl,
            size = size,
            parts = listOf(DownloadPart("", "base", asset.downloadUrl, size)),
            icon = app.icon,
            changeLog = release.description.orEmpty(),
            source = AppSource.GITLAB,
        )
    }

    /** 直链 HEAD 取 `Content-Length`；失败一律视为未知，不阻断下载流程。 */
    private suspend fun contentLength(url: String): Long = try {
        val response = client.head(url) { header(HttpHeaders.UserAgent, UserAgent) }
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        debugLog("GitlabApi") { "HEAD $url failed: ${error.message}" }
        0L
    }

    suspend fun checkUpdates(
        installed: List<InstalledPackage>,
        tracked: List<TrackedRepo>,
    ): List<MarketAppInfo> {
        if (installed.isEmpty() || tracked.isEmpty()) return emptyList()
        val installedByPackage = installed.associateBy { it.packageName }
        val gate = Semaphore(config.maxConcurrency)
        return coroutineScope {
            tracked.map { repo ->
                async {
                    val local = installedByPackage[repo.packageName] ?: return@async null
                    gate.withPermit {
                        runCatching { resolveUpdate(repo, local) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                debugLog("GitlabApi") { "Update check failed for ${repo.slug}: ${error.message}" }
                            }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** 单个跟踪项目的更新检查，供「按包名 / 链接检测」入口使用。 */
    suspend fun checkUpdate(tracked: TrackedRepo, local: InstalledPackage): SourceUpdateCheck {
        val release = latestRelease(tracked.slug) ?: return SourceUpdateCheck.NotFound
        val app = resolveUpdate(tracked, local, release) ?: return SourceUpdateCheck.UpToDate
        return SourceUpdateCheck.Available(app)
    }

    private suspend fun resolveUpdate(
        repo: TrackedRepo,
        local: InstalledPackage,
        prefetched: GitlabRelease? = null,
    ): MarketAppInfo? {
        val release = prefetched ?: latestRelease(repo.slug) ?: return null
        val links = release.assets?.links.orEmpty()
        val asset = pickBestApk(links, { it.name.ifBlank { it.downloadUrl.substringAfterLast('/') } }, { 0L })
        val fileName = asset?.name?.ifBlank { asset.downloadUrl.substringAfterLast('/') }
        val identity = fileName?.let { parseApkAssetName(it) }
        val remoteVersionCode = identity?.versionCode?.takeIf { it > 0L }
        val remoteVersionName = normalizeReleaseVersion(release.tagName)
        val hasUpdate = if (remoteVersionCode != null) {
            remoteVersionCode > local.versionCode
        } else {
            compareVersionNames(remoteVersionName, local.versionName) > 0
        }
        if (!hasUpdate) return null

        return repo.toTrackedAppInfo(release, identity?.packageName ?: repo.packageName, remoteVersionCode).copy(
            versionName = remoteVersionName,
            installedVersionName = local.versionName,
            installedVersionCode = local.versionCode,
            installedBaseApkPath = local.baseApkPath,
            installedSplits = local.splits,
            installedOldApkHash = local.oldApkHash,
            isSystemApp = local.isSystemApp,
        )
    }

    /** 并发拉取前 N 个项目的发布信息；单条失败只丢这一条，不影响整页搜索。 */
    private suspend fun enrich(projects: List<GitlabProject>): List<Pair<GitlabProject, GitlabRelease?>> {
        if (projects.isEmpty() || config.enrichSearchResults <= 0) return emptyList()
        val gate = Semaphore(config.maxConcurrency)
        return coroutineScope {
            projects.map { project ->
                async {
                    gate.withPermit {
                        runCatching { project to latestRelease(project.slug) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                debugLog("GitlabApi") { "Enrich failed for ${project.slug}: ${error.message}" }
                            }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun getJson(path: String): String {
        val token = tokens.gitlab()
        val response = client.get("${config.apiBaseUrl}$path") {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, UserAgent)
            if (token.isNotBlank()) header("PRIVATE-TOKEN", token)
        }
        return response.requireBody("GitLab $path")
    }

    private suspend fun HttpResponse.requireBody(label: String): String {
        if (status.isSuccess()) return bodyAsText()
        val detail = runCatching {
            val error = json.decodeFromString<GitlabError>(bodyAsText())
            error.message.ifBlank { error.error }
        }.getOrNull().orEmpty()
        if (status.value == 401 || status.value == 403) {
            throw MarketException("GitLab 接口访问受限（HTTP ${status.value}）：请检查 PRIVATE-TOKEN 是否有效")
        }
        if (status.value == 404) throw MarketException("GitLab 未找到对应项目或发布：$label")
        throw MarketException("GitLab 请求失败（HTTP ${status.value}）：${detail.ifBlank { label }}")
    }
}

private fun GitlabProject.toAppInfo(
    release: GitlabRelease?,
    packageName: String = resolvePackageName(release),
    versionCode: Long? = null,
): MarketAppInfo {
    val links = release?.assets?.links.orEmpty()
    val identity = links.firstNotNullOfOrNull { link ->
        parseApkAssetName(link.name.ifBlank { link.downloadUrl.substringAfterLast('/') })
    }
    return MarketAppInfo(
        appId = id,
        packageName = packageName,
        displayName = name.ifBlank { slug.substringAfterLast('/') },
        publisherName = namespace?.name.orEmpty(),
        versionName = release?.tagName?.let(::normalizeReleaseVersion).orEmpty(),
        versionCode = versionCode ?: identity?.versionCode ?: 0L,
        icon = avatarUrl.orEmpty(),
        apkSize = 0L,
        ratingScore = 0.0,
        changeLog = release?.description.orEmpty(),
        openLink = webUrl.ifBlank { "https://gitlab.com/$slug" },
        type = topics.firstOrNull().orEmpty(),
        source = AppSource.GITLAB,
        category = topics.firstOrNull().orEmpty(),
        downloadCount = starCount.toLong(),
    )
}

private fun GitlabProject.resolvePackageName(release: GitlabRelease?): String =
    release?.assets?.links.orEmpty().firstNotNullOfOrNull { link ->
        parseApkAssetName(link.name.ifBlank { link.downloadUrl.substringAfterLast('/') })?.packageName
    }.orEmpty()

/** 更新检查只有跟踪表里的信息，单独构造；[TrackedRepo.appId] 保证更新条目仍能跳转详情页。 */
private fun TrackedRepo.toTrackedAppInfo(
    release: GitlabRelease?,
    packageName: String,
    versionCode: Long?,
): MarketAppInfo {
    val links = release?.assets?.links.orEmpty()
    val identity = links.firstNotNullOfOrNull { link ->
        parseApkAssetName(link.name.ifBlank { link.downloadUrl.substringAfterLast('/') })
    }
    return MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName.ifBlank { slug.substringAfterLast('/') },
        publisherName = slug.substringBeforeLast('/', ""),
        versionName = release?.tagName?.let(::normalizeReleaseVersion).orEmpty(),
        versionCode = versionCode ?: identity?.versionCode ?: 0L,
        icon = icon,
        apkSize = 0L,
        ratingScore = 0.0,
        changeLog = release?.description.orEmpty(),
        openLink = "https://gitlab.com/$slug",
        source = AppSource.GITLAB,
    )
}

internal fun String.normalizedGitlabSlug(): String = trim()
    .removePrefix("https://gitlab.com/")
    .removePrefix("http://gitlab.com/")
    .removePrefix("gitlab.com/")
    .removeSuffix(".git")
    .trim('/')

internal fun MarketAppInfo.gitlabSlugOrThrow(): String {
    val fromLink = openLink.substringAfter("gitlab.com/", "").substringBefore('?').trim('/').removeSuffix(".git")
    if (fromLink.isNotBlank() && '/' in fromLink) return fromLink
    throw MarketException("无法从该条目定位 GitLab 项目：$openLink")
}
