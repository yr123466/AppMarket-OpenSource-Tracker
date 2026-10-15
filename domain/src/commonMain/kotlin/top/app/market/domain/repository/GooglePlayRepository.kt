package top.app.market.domain.repository

import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SourceUpdateCheck

/**
 * Google Play 匿名只读接入（仅更新检测）。
 *
 * Play 没有免鉴权的元数据 API，也不公开 versionCode：版本号从商店详情页解析，
 * 只能按 versionName 比较，且需要对任意已安装包名逐个查询。因此本来源只参与更新检测，
 * 不提供搜索 / 分区，更新动作由调用方改为拉起 Play 商店。
 */
interface GooglePlayRepository {
    /** 详情页仅能提供标题与版本号，用于更新列表跳转后的兜底展示。 */
    suspend fun appDetail(packageName: String): AppDetail

    /** 对全部已安装包名逐个查询 Play 商店并对比 versionName。 */
    suspend fun checkUpdates(): List<MarketAppInfo>

    /** 针对单个包名的更新检查，未收录该包时返回 [SourceUpdateCheck.NotFound]。 */
    suspend fun checkUpdate(packageName: String, versionCode: Long, versionName: String): SourceUpdateCheck
}
