package top.app.market.data.remote.play

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import top.app.market.data.platform.debugLog
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.model.market.compareVersionName

internal data class GooglePlayApiConfig(
    val storeBaseUrl: String = "https://play.google.com",
    val language: String = "en",
    val maxConcurrency: Int = 4,
)

private const val UserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * Google Play 只读客户端（仅更新检测）。
 *
 * 没有官方免鉴权接口，只能解析商店详情页 HTML。版本号在页面内嵌的 `AF_initDataCallback`
 * 数据块中以 `]]],"1.2.3",null,[[[` 的形式出现，据此提取 versionName。Play 不公开 versionCode，
 * 因此版本对比统一走 [compareVersionName]。
 *
 * 注意：Google 对高频抓取会限流/改版，解析失败时按「未检测到」降级，不影响其他来源。
 */
internal class GooglePlayApi(
    private val client: HttpClient,
    private val config: GooglePlayApiConfig = GooglePlayApiConfig(),
) {
    private val titlePattern = Regex("""<title[^>]*>([^<]*)</title>""")

    suspend fun versionName(packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return null
        val html = getText(detailUrl(pkg))
        return extractPlayVersionName(html)
    }

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
                                debugLog("GooglePlayApi") { "Update check failed for ${local.packageName}: ${error.message}" }
                            }
                            .getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun checkUpdate(packageName: String, versionName: String): SourceUpdateCheck {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return SourceUpdateCheck.NotFound
        val remote = versionName(pkg) ?: return SourceUpdateCheck.NotFound
        if (compareVersionName(remote, versionName) <= 0) return SourceUpdateCheck.UpToDate
        return SourceUpdateCheck.Available(
            MarketAppInfo(
                appId = playAppId(pkg),
                packageName = pkg,
                displayName = pkg,
                publisherName = "",
                versionName = remote,
                versionCode = 0L,
                icon = "",
                apkSize = 0L,
                ratingScore = 0.0,
                openLink = detailUrl(pkg),
                installedVersionName = versionName,
                source = AppSource.PLAY,
            )
        )
    }

    private suspend fun resolveUpdate(local: InstalledPackage): MarketAppInfo? {
        val remote = versionName(local.packageName) ?: return null
        if (compareVersionName(remote, local.versionName) <= 0) return null
        val displayName = local.displayName.ifBlank { local.packageName }
        return MarketAppInfo(
            appId = playAppId(local.packageName),
            packageName = local.packageName,
            displayName = displayName,
            publisherName = "",
            versionName = remote,
            versionCode = 0L,
            icon = "",
            apkSize = 0L,
            ratingScore = 0.0,
            openLink = detailUrl(local.packageName),
            installedVersionName = local.versionName,
            installedVersionCode = local.versionCode,
            installedOldApkHash = local.oldApkHash,
            installedBaseApkPath = local.baseApkPath,
            installedSplits = local.splits,
            isSystemApp = local.isSystemApp,
            source = AppSource.PLAY,
        )
    }

    suspend fun appDetail(packageName: String): AppDetail {
        val pkg = packageName.trim()
        val html = runCatching { getText(detailUrl(pkg)) }.getOrNull().orEmpty()
        val remote = extractPlayVersionName(html)
        val title = titlePattern.find(html)?.groupValues?.get(1)?.substringBefore(" - ")?.trim()
        val app = MarketAppInfo(
            appId = playAppId(pkg),
            packageName = pkg,
            displayName = title?.takeIf { it.isNotBlank() } ?: pkg,
            publisherName = "",
            versionName = remote.orEmpty(),
            versionCode = 0L,
            icon = "",
            apkSize = 0L,
            ratingScore = 0.0,
            openLink = detailUrl(pkg),
            source = AppSource.PLAY,
        )
        return AppDetail(
            app = app,
            brief = "",
            introduction = "",
            changeLog = "",
            category = "",
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            privacyUrl = "",
            screenshots = emptyList(),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    private fun detailUrl(pkg: String) =
        "${config.storeBaseUrl}/store/apps/details?id=${pkg.encodeURLParameter()}&hl=${config.language}"

    private suspend fun getText(url: String): String {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgent)
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Google Play 请求失败（HTTP ${response.status.value}）：$url")
        }
        return response.bodyAsText()
    }
}

// Play 详情页把版本号内嵌在 `AF_initDataCallback` 数据块里，形如 `]]],"1.2.3",null,[[[`。
private val playVersionPattern = Regex("""\]\]\],"([0-9][0-9A-Za-z._-]*)",null,""")

/** 从 Play 商店详情页 HTML 中提取当前版本号；无法匹配时返回 null。 */
internal fun extractPlayVersionName(html: String): String? =
    playVersionPattern.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

/** Play 没有数字 id，用包名的稳定哈希当 [MarketAppInfo.appId]，仅用于列表去重与缓存键。 */
internal fun playAppId(packageName: String): Long {
    var hash = 1469598103934665603L
    for (char in packageName) hash = (hash xor char.code.toLong()) * 1099511628211L
    return hash and 0x7FFFFFFFFFFFFFFFL
}
