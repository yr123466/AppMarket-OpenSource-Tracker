package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck

/**
 * GitLab Releases 匿名只读接入，与 [GithubRepository] 同构。
 *
 * `appId` 为 GitLab 项目数字 id；`slug` 为 `namespace/project`（含子组）。
 */
interface GitlabRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage

    suspend fun appDetail(appId: Long): AppDetail

    suspend fun appDetailBySlug(slug: String): AppDetail

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta

    suspend fun checkUpdates(): List<MarketAppInfo>

    /** 针对单个包名的更新检查；未跟踪该包名时返回 [SourceUpdateCheck.NotFound]。 */
    suspend fun checkUpdate(packageName: String, versionCode: Long, versionName: String): SourceUpdateCheck

    suspend fun track(slug: String, app: MarketAppInfo)
}
