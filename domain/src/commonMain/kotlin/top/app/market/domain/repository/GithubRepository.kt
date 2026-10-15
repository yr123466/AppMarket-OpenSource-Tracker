package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck

/**
 * GitHub Releases 匿名只读接入。
 *
 * 以「仓库」为单位：搜索命中仓库，详情解析最新 Release，下载取 Release 里的 APK 资产。
 * 包名与版本号从资产名 `{包名}_{versionCode}.apk` 还原 —— 这是 F-Droid / IzzyOnDroid 系
 * 发布脚本的通行约定，也是本源能做版本对比的前提。
 */
interface GithubRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage

    suspend fun appDetail(appId: Long): AppDetail

    /** 直接按 `owner/repo` 解析，供链接检测与「添加仓库」使用。 */
    suspend fun appDetailBySlug(slug: String): AppDetail

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta

    /** 按已跟踪仓库批量检查更新。未跟踪任何仓库时返回空列表。 */
    suspend fun checkUpdates(): List<MarketAppInfo>

    /**
     * 针对单个包名的更新检查（「包名检测」入口）。
     *
     * GitHub 无法按包名反查仓库，因此只在该包名已被跟踪时才有结果；未跟踪返回 [SourceUpdateCheck.NotFound]。
     */
    suspend fun checkUpdate(packageName: String, versionCode: Long, versionName: String): SourceUpdateCheck

    /** 把 `owner/repo` 记入跟踪表，使其参与后续更新检查。 */
    suspend fun track(slug: String, app: MarketAppInfo)
}
