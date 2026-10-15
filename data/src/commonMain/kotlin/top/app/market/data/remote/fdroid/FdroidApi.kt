package top.app.market.data.remote.fdroid

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
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.download.DownloadPart
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppScreenshot
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.ScreenshotOrientation
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck

internal data class FdroidApiConfig(
    val siteBaseUrl: String = "https://f-droid.org",
    val searchBaseUrl: String = "https://search.f-droid.org",
    val apiBaseUrl: String = "https://f-droid.org/api/v1",
    val repoBaseUrl: String = "https://f-droid.org/repo",
    val language: String = "en",
    val searchPageSize: Int = 20,
    val maxConcurrency: Int = 4,
)

private const val UserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * F-Droid 官方仓库只读客户端。
 *
 * 三条通路各司其职：
 * - 搜索：`search.f-droid.org`（服务端渲染 HTML，无 JSON API）
 * - 详情：`f-droid.org/{lang}/packages/{包名}/` 网页
 * - **更新检查与下载：`api/v1/packages/{包名}`** —— 轻量 JSON，可按包名反查，
 *   这是 F-Droid 能对任意已安装应用做版本对比的原因。
 */
internal class FdroidApi(
    private val client: HttpClient,
    private val config: FdroidApiConfig = FdroidApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(keyword: String, page: Int): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(emptyList(), hasMore = false)
        val targetPage = (page + 1).coerceAtLeast(1)
        val html = getText(
            "${config.searchBaseUrl}/?q=${query.encodeURLParameter()}" +
                "&lang=${config.language}&page=$targetPage"
        )
        val items = FdroidHtml.parseSearch(html).map { it.toAppInfo() }
        return SearchPage(
            items = items,
            hasMore = items.size >= config.searchPageSize,
        )
    }

    suspend fun packageDetail(packageName: String): FdroidPackageDetail {
        val pkg = packageName.trim()
        if (pkg.isBlank()) throw MarketException("F-Droid 需要包名才能定位应用")
        val html = getText("${config.siteBaseUrl}/${config.language}/packages/${pkg.encodeURLParameter()}/")
        return FdroidHtml.parseDetail(html, pkg)
    }

    /** 按包名反查仓库里的版本信息；未收录时返回 null。 */
    suspend fun versions(packageName: String): FdroidPackageVersions? {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return null
        val response = client.get("${config.apiBaseUrl}/packages/${pkg.encodeURLParameter()}") {
            header(HttpHeaders.UserAgent, UserAgent)
            header(HttpHeaders.Accept, "application/json")
        }
        if (response.status.value == 404) return null
        if (!response.status.isSuccess()) {
            throw MarketException("F-Droid 版本查询失败：HTTP ${response.status.value}")
        }
        // 解析失败必须留痕：否则接口改版会被静默当成「未收录」，表现为所有应用都查不到更新。
        return runCatching { json.decodeFromString<FdroidPackageVersions>(response.bodyAsText()) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                debugLog("FdroidApi") { "Parse failed for $pkg: ${error.message}" }
            }
            .getOrNull()
    }

    /**
     * 批量更新检查：对每个已安装包名查一次 `api/v1/packages/{包名}`，只保留版本号更大的。
     * 这是唯一「零配置」就能覆盖全部已安装应用的源。
     */
    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val gate = Semaphore(config.maxConcurrency)
        return coroutineScope {
            installed.map { local ->
                async {
                    gate.withPermit {
                        runCatching { resolveUpdate(local) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                debugLog("FdroidApi") { "Update check failed for ${local.packageName}: ${error.message}" }
                            }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * 单个包的更新检查，供「按包名检测」入口使用。
     * 与批量检查走同一条通路，只是把入参从已安装列表换成一个显式包名。
     */
    suspend fun checkUpdate(packageName: String, versionCode: Long, versionName: String): SourceUpdateCheck {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return SourceUpdateCheck.NotFound
        val versions = versions(pkg) ?: return SourceUpdateCheck.NotFound
        if (versions.suggestedVersionCode <= versionCode) return SourceUpdateCheck.UpToDate
        val local = InstalledPackage(
            packageName = pkg,
            versionCode = versionCode,
            isSystemApp = false,
            versionName = versionName,
        )
        val app = resolveUpdate(local) ?: return SourceUpdateCheck.UpToDate
        return SourceUpdateCheck.Available(app)
    }

    private suspend fun resolveUpdate(local: InstalledPackage): MarketAppInfo? {
        val versions = versions(local.packageName) ?: return null
        val suggested = versions.suggestedVersionCode
        if (suggested <= local.versionCode) return null
        val remote = versions.packages.firstOrNull { it.versionCode == suggested }
            ?: versions.packages.maxByOrNull { it.versionCode }
        val displayName = local.displayName.ifBlank { local.packageName }
        return MarketAppInfo(
            appId = fdroidAppId(local.packageName),
            packageName = local.packageName,
            displayName = displayName,
            publisherName = "",
            versionName = remote?.versionName.orEmpty(),
            versionCode = suggested,
            icon = "",
            apkSize = 0L,
            ratingScore = 0.0,
            openLink = "${config.siteBaseUrl}/${config.language}/packages/${local.packageName}/",
            installedVersionName = local.versionName,
            installedVersionCode = local.versionCode,
            installedOldApkHash = local.oldApkHash,
            installedBaseApkPath = local.baseApkPath,
            installedSplits = local.splits,
            isSystemApp = local.isSystemApp,
            source = AppSource.FDROID,
        )
    }

    suspend fun appDetail(packageName: String): AppDetail {
        val detail = packageDetail(packageName)
        val versions = runCatching { versions(packageName) }.getOrNull()
        val suggested = versions?.suggestedVersionCode ?: detail.latestVersionCode
        val versionName = versions?.packages?.firstOrNull { it.versionCode == suggested }?.versionName
            ?: detail.latestVersionName
        val app = MarketAppInfo(
            appId = fdroidAppId(packageName),
            packageName = packageName,
            displayName = detail.displayName,
            publisherName = detail.website.substringAfter("//").substringBefore('/'),
            versionName = versionName,
            versionCode = suggested,
            icon = detail.iconUrl,
            apkSize = detail.latestApkSize,
            ratingScore = 0.0,
            changeLog = detail.changeLog,
            openLink = "${config.siteBaseUrl}/${config.language}/packages/$packageName/",
            type = "",
            source = AppSource.FDROID,
            category = detail.license,
        )
        return AppDetail(
            app = app,
            brief = detail.summary,
            introduction = detail.description,
            changeLog = detail.changeLog,
            category = detail.license,
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = detail.license,
            privacyUrl = "",
            screenshots = detail.screenshots.map { url ->
                AppScreenshot(url = url, expandedUrl = url, orientation = ScreenshotOrientation.PORTRAIT)
            },
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    /**
     * 官方仓库的 APK 路径是确定性的：`{repoBaseUrl}/{包名}_{versionCode}.apk`。
     * 因此只要拿到建议版本号就能直接拼出直链，不必依赖详情页 HTML。
     */
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val pkg = app.packageName.trim()
        if (pkg.isBlank()) throw MarketException("F-Droid 需要包名才能下载")
        val versions = versions(pkg)
        val versionCode = versions?.suggestedVersionCode?.takeIf { it > 0L }
            ?: app.versionCode.takeIf { it > 0L }
            ?: throw MarketException("F-Droid 未收录 $pkg 的可下载版本")
        val versionName = versions?.packages?.firstOrNull { it.versionCode == versionCode }?.versionName
            ?: app.versionName
        val url = "${config.repoBaseUrl}/${pkg}_$versionCode.apk"
        val size = contentLength(url).takeIf { it > 0L } ?: app.apkSize
        return DownloadMeta(
            appId = app.appId,
            packageName = pkg,
            displayName = app.displayName,
            versionName = versionName,
            versionCode = versionCode,
            url = url,
            size = size,
            parts = listOf(DownloadPart("", "base", url, size)),
            icon = app.icon,
            changeLog = app.changeLog,
            source = AppSource.FDROID,
        )
    }

    private suspend fun contentLength(url: String): Long = try {
        val response = client.head(url) { header(HttpHeaders.UserAgent, UserAgent) }
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        0L
    }

    private suspend fun getText(url: String): String {
        val response: HttpResponse = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }
        if (!response.status.isSuccess()) {
            throw MarketException("F-Droid 请求失败（HTTP ${response.status.value}）：$url")
        }
        return response.bodyAsText()
    }
}

/** F-Droid 没有数字 id，用包名的稳定哈希当 [MarketAppInfo.appId]，仅用于列表去重与缓存键。 */
internal fun fdroidAppId(packageName: String): Long {
    var hash = 1125899906842597L
    for (char in packageName) hash = 31 * hash + char.code
    return hash and 0x7FFFFFFFFFFFFFFFL
}

private fun FdroidSearchItem.toAppInfo(): MarketAppInfo = MarketAppInfo(
    appId = fdroidAppId(packageName),
    packageName = packageName,
    displayName = displayName,
    publisherName = "",
    versionName = "",
    versionCode = 0L,
    icon = iconUrl,
    apkSize = 0L,
    ratingScore = 0.0,
    changeLog = summary,
    openLink = "https://f-droid.org/en/packages/$packageName/",
    source = AppSource.FDROID,
    category = license,
)
