package top.app.market.domain.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck

/**
 * F-Droid 官方仓库匿名只读接入。
 *
 * 与其他源最大的差别：F-Droid 是**唯一支持按包名反查**的来源
 * （`https://f-droid.org/api/v1/packages/{包名}` 直接给出建议版本与全部历史版本），
 * 因此它可以对**任意**已安装应用做版本对比，不需要任何跟踪表或用户操作。
 */
interface FdroidRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage

    /** [appId] 为包名哈希占位，真正定位靠 [packageName]。 */
    suspend fun appDetail(appId: Long, packageName: String): AppDetail

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta

    /** 按已安装包名逐个反查 F-Droid 仓库并对比 versionCode。 */
    suspend fun checkUpdates(): List<MarketAppInfo>

    /** 针对单个包名的更新检查，未收录该包时返回 [SourceUpdateCheck.NotFound]。 */
    suspend fun checkUpdate(packageName: String, versionCode: Long, versionName: String): SourceUpdateCheck
}
