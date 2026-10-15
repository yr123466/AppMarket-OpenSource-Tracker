package top.app.market.data.remote.github

import io.ktor.client.HttpClient
import io.ktor.client.request.get
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

internal data class GithubApiConfig(
    val apiBaseUrl: String = "https://api.github.com",
    val searchPageSize: Int = 20,
    val releasePageSize: Int = 10,
    /**
     * 搜索结果前 N 条额外拉一次最新 Release，用来补全包名与版本号。
     * 匿名访问限流只有 60 次/小时，取 3 是「列表可用」与「限流可接受」的折中。
     */
    val enrichSearchResults: Int = 3,
    val maxConcurrency: Int = 4,
)

private const val UserAgent = "AppMarket-Android"
private const val ApiVersion = "2022-11-28"

/**
 * GitHub REST v3 只读客户端。
 *
 * 只依赖公开接口，不需要 OAuth：搜索走 `/search/repositories`，
 * 仓库与发布走 `/repos/{owner}/{repo}`、`/repos/{owner}/{repo}/releases`。
 */
internal class GithubApi(
    private val client: HttpClient,
    private val tokens: OpenSourceTokenStore,
    private val config: GithubApiConfig = GithubApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(keyword: String, page: Int): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(emptyList(), hasMore = false)
        val targetPage = (page + 1).coerceAtLeast(1)
        val body = getJson(
            "/search/repositories?q=${query.encodeURLParameter()}" +
                "&per_page=${config.searchPageSize}&page=$targetPage&sort=stars&order=desc"
        )
        val result = json.decodeFromString<GithubSearchResponse>(body)
        val repos = result.items.filterNot { it.archived }
        if (repos.isEmpty()) return SearchPage(emptyList(), hasMore = false)

        val enriched = enrich(repos.take(config.enrichSearchResults))
            .associate { resolved -> resolved.repo.id to resolved.repo.toAppInfo(resolved.release) }
        val items = repos.map { repo -> enriched[repo.id] ?: repo.toAppInfo(release = null) }
        return SearchPage(
            items = items,
            hasMore = repos.size >= config.searchPageSize && result.totalCount > targetPage * config.searchPageSize,
        )
    }

    suspend fun repoById(id: Long): GithubRepo = json.decodeFromString(getJson("/repositories/$id"))

    suspend fun repoBySlug(slug: String): GithubRepo =
        json.decodeFromString(getJson("/repos/${slug.normalizedSlug()}"))

    suspend fun releases(slug: String, limit: Int = config.releasePageSize): List<GithubRelease> {
        val body = getJson("/repos/${slug.normalizedSlug()}/releases?per_page=$limit")
        return json.decodeFromString<List<GithubRelease>>(body)
            .filterNot { it.draft }
            .sortedByDescending { it.publishedAt ?: it.createdAt ?: "" }
    }

    suspend fun tags(slug: String, limit: Int = config.releasePageSize): List<String> =
        json.decodeFromString<List<GithubTag>>(getJson("/repos/${slug.normalizedSlug()}/tags?per_page=$limit"))
            .map { it.name }
            .filter { it.isNotBlank() }

    /**
     * 取该仓库「最值得展示」的一次发布：优先稳定版，其次预发布版；
     * 一个 Release 都没有时退化为最新 tag（此时没有下载链接，只用于版本比较）。
     */
    suspend fun latestRelease(slug: String): GithubRelease? {
        val all = releases(slug)
        val stable = all.firstOrNull { !it.prerelease }
        return stable ?: all.firstOrNull()
    }

    suspend fun appDetail(repo: GithubRepo, release: GithubRelease?): AppDetail {
        val app = repo.toAppInfo(release)
        val downloads = release?.assets?.sumOf { it.downloadCount } ?: 0L
        return AppDetail(
            app = app,
            brief = repo.description.orEmpty(),
            introduction = repo.description.orEmpty().ifBlank { repo.slug },
            changeLog = release?.body.orEmpty(),
            category = repo.language.orEmpty(),
            ageClassification = "",
            downloadCount = downloads,
            registrationNum = repo.license?.spdxId.orEmpty(),
            privacyUrl = "",
            screenshots = emptyList(),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val slug = app.slugOrThrow()
        val release = latestRelease(slug)
            ?: throw MarketException("GitHub 仓库 $slug 还没有发布版本")
        val asset = pickBestApk(release.assets, { it.name }, { it.size })
            ?: throw MarketException("GitHub 仓库 $slug 的最新发布里没有 APK 资产")
        val identity = parseApkAssetName(asset.name)
        val versionName = normalizeReleaseVersion(release.tagName)
        val versionCode = identity?.versionCode?.takeIf { it > 0L } ?: app.versionCode
        return DownloadMeta(
            appId = app.appId,
            packageName = identity?.packageName ?: app.packageName,
            displayName = app.displayName,
            versionName = versionName.ifBlank { app.versionName },
            versionCode = versionCode,
            url = asset.downloadUrl,
            size = asset.size,
            parts = listOf(DownloadPart("", "base", asset.downloadUrl, asset.size)),
            icon = app.icon,
            changeLog = release.body.orEmpty(),
            source = AppSource.GITHUB,
        )
    }

    /**
     * 按跟踪表批量检查更新。
     *
     * GitHub 没有「按包名反查仓库」的接口，所以只能对 [tracked] 里的仓库逐个查询；
     * 未跟踪任何仓库时返回空列表（这是 GitHub 源的固有边界，不是错误）。
     */
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
                                debugLog("GithubApi") { "Update check failed for ${repo.slug}: ${error.message}" }
                            }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** 单个跟踪仓库的更新检查，供「按包名 / 链接检测」入口使用。 */
    suspend fun checkUpdate(tracked: TrackedRepo, local: InstalledPackage): SourceUpdateCheck {
        val release = latestRelease(tracked.slug) ?: return SourceUpdateCheck.NotFound
        val app = resolveUpdate(tracked, local, release) ?: return SourceUpdateCheck.UpToDate
        return SourceUpdateCheck.Available(app)
    }

    private suspend fun resolveUpdate(
        repo: TrackedRepo,
        local: InstalledPackage,
        prefetched: GithubRelease? = null,
    ): MarketAppInfo? {
        val release = prefetched ?: latestRelease(repo.slug) ?: return null
        val asset = pickBestApk(release.assets, { it.name }, { it.size })
        val identity = asset?.let { parseApkAssetName(it.name) }
        val remoteVersionCode = identity?.versionCode?.takeIf { it > 0L }
        val remoteVersionName = normalizeReleaseVersion(release.tagName)
        val hasUpdate = if (remoteVersionCode != null) {
            remoteVersionCode > local.versionCode
        } else {
            compareVersionNames(remoteVersionName, local.versionName) > 0
        }
        if (!hasUpdate) return null

        return repo.toTrackedAppInfo(
            release = release,
            packageName = identity?.packageName ?: repo.packageName,
            versionCode = remoteVersionCode ?: local.versionCode + 1,
            versionName = remoteVersionName,
        ).copy(
            installedVersionName = local.versionName,
            installedVersionCode = local.versionCode,
            installedBaseApkPath = local.baseApkPath,
            installedSplits = local.splits,
            installedOldApkHash = local.oldApkHash,
            isSystemApp = local.isSystemApp,
        )
    }

    /** 并发拉取前 N 个仓库的发布信息；单条失败只丢这一条，不影响整页搜索。 */
    private suspend fun enrich(repos: List<GithubRepo>): List<ResolvedApp> {
        if (repos.isEmpty() || config.enrichSearchResults <= 0) return emptyList()
        val gate = Semaphore(config.maxConcurrency)
        return coroutineScope {
            repos.map { repo ->
                async {
                    gate.withPermit {
                        runCatching { ResolvedApp(repo, latestRelease(repo.slug)) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                debugLog("GithubApi") { "Enrich failed for ${repo.slug}: ${error.message}" }
                            }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun getJson(path: String): String {
        val token = tokens.github()
        val response = client.get("${config.apiBaseUrl}$path") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", ApiVersion)
            header(HttpHeaders.UserAgent, UserAgent)
            if (token.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $token")
        }
        return response.requireBody("GitHub $path", token.isNotBlank())
    }

    private suspend fun HttpResponse.requireBody(label: String, authenticated: Boolean): String {
        if (status.isSuccess()) return bodyAsText()
        val detail = runCatching {
            json.decodeFromString<GithubError>(bodyAsText()).message
        }.getOrNull().orEmpty()
        val remaining = headers["x-ratelimit-remaining"]
        if (status.value == 403 || status.value == 429 || remaining == "0") {
            val hint = if (authenticated) "请稍后重试，或更换 GitHub token" else "填入 GitHub token 可提升到 5000 次/小时"
            throw MarketException("GitHub 接口访问受限（HTTP ${status.value}）：$hint")
        }
        if (status.value == 404) throw MarketException("GitHub 未找到对应仓库或发布：$label")
        throw MarketException("GitHub 请求失败（HTTP ${status.value}）：${detail.ifBlank { label }}")
    }
}

private data class ResolvedApp(val repo: GithubRepo, val release: GithubRelease?)

private fun GithubRepo.toAppInfo(release: GithubRelease?): MarketAppInfo {
    val asset = release?.let { pickBestApk(it.assets, { a -> a.name }, { a -> a.size }) }
    val identity = asset?.let { parseApkAssetName(it.name) }
    return toTrackedAppInfo(
        release = release,
        packageName = identity?.packageName.orEmpty(),
        versionCode = identity?.versionCode?.takeIf { it > 0L } ?: 0L,
        versionName = release?.tagName?.let(::normalizeReleaseVersion).orEmpty(),
    )
}

private fun GithubRepo.toTrackedAppInfo(
    release: GithubRelease?,
    packageName: String,
    versionCode: Long,
    versionName: String,
): MarketAppInfo {
    val asset = release?.let { pickBestApk(it.assets, { a -> a.name }, { a -> a.size }) }
    return MarketAppInfo(
        appId = id,
        packageName = packageName,
        displayName = name.ifBlank { slug },
        publisherName = owner?.login.orEmpty(),
        versionName = versionName,
        versionCode = versionCode,
        icon = owner?.avatarUrl.orEmpty(),
        apkSize = asset?.size ?: 0L,
        ratingScore = 0.0,
        changeLog = release?.body.orEmpty(),
        openLink = htmlUrl.ifBlank { "https://github.com/$slug" },
        type = language.orEmpty(),
        source = AppSource.GITHUB,
        category = language.orEmpty(),
        downloadCount = stargazersCount.toLong(),
    )
}

/**
 * 更新检查只有跟踪表里的信息（没有仓库描述、star 数等），
 * 因此这条路径单独构造，[TrackedRepo.appId] 保证更新条目仍能跳转到详情页。
 */
private fun TrackedRepo.toTrackedAppInfo(
    release: GithubRelease?,
    packageName: String,
    versionCode: Long,
    versionName: String,
): MarketAppInfo {
    val asset = release?.let { pickBestApk(it.assets, { a -> a.name }, { a -> a.size }) }
    return MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName.ifBlank { slug },
        publisherName = slug.substringBefore('/'),
        versionName = versionName,
        versionCode = versionCode,
        icon = icon,
        apkSize = asset?.size ?: 0L,
        ratingScore = 0.0,
        changeLog = release?.body.orEmpty(),
        openLink = "https://github.com/$slug",
        source = AppSource.GITHUB,
    )
}

/** 兼容用户直接粘贴的完整链接、带 `.git` 后缀或前后空白的写法。 */
internal fun String.normalizedSlug(): String = trim()
    .removePrefix("https://github.com/")
    .removePrefix("http://github.com/")
    .removePrefix("github.com/")
    .removeSuffix(".git")
    .trim('/')

/** 从 GitHub 详情页 URL 或统一格式里取回 `owner/repo`。 */
internal fun MarketAppInfo.slugOrThrow(): String {
    val fromLink = openLink
        .substringAfter("github.com/", "")
        .substringBefore('?')
        .trim('/')
        .removeSuffix(".git")
    if (fromLink.isNotBlank() && '/' in fromLink) return fromLink
    throw MarketException("无法从该条目定位 GitHub 仓库：$openLink")
}
