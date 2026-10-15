package top.app.market.data.repository

import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppCategory
import top.app.market.domain.model.market.AppComments
import top.app.market.domain.model.market.AppSubCategory
import top.app.market.domain.model.market.CategoryOption
import top.app.market.domain.model.market.GameRanking
import top.app.market.domain.model.market.GameSubCategory
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.model.market.mergeUpdateSources
import top.app.market.domain.model.today.TodayArticle
import top.app.market.domain.model.today.TodayFeedPage
import top.app.market.domain.model.update.ManualUpdateRequest
import top.app.market.domain.model.update.ManualUpdateResult
import top.app.market.domain.model.update.ManualUpdateStatus
import top.app.market.domain.repository.FdroidRepository
import top.app.market.domain.repository.GithubRepository
import top.app.market.domain.repository.GitlabRepository
import top.app.market.domain.repository.GooglePlayRepository
import top.app.market.domain.repository.HonorRepository
import top.app.market.domain.repository.HuaweiRepository
import top.app.market.domain.repository.MarketRepository
import top.app.market.domain.repository.MarketSourceRepository
import top.app.market.domain.repository.OppoRepository
import top.app.market.domain.repository.SamsungRepository
import top.app.market.domain.repository.TodayRepository
import top.app.market.domain.repository.TapTapRepository
import top.app.market.domain.repository.VivoRepository
import top.app.market.domain.repository.WandoujiaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Central source router. Capabilities are declared on [AppSource.capabilities]. */
internal class MarketSourceRepositoryImpl(
    private val market: MarketRepository,
    private val today: TodayRepository,
    private val vivo: VivoRepository,
    private val wandoujia: WandoujiaRepository,
    private val oppo: OppoRepository,
    private val samsung: SamsungRepository,
    private val honor: HonorRepository,
    private val huawei: HuaweiRepository,
    private val tapTap: TapTapRepository,
    private val box7723: top.app.market.domain.repository.Box7723Repository,
    private val github: GithubRepository,
    private val gitlab: GitlabRepository,
    private val fdroid: FdroidRepository,
    private val play: GooglePlayRepository,
) : MarketSourceRepository {
    override suspend fun search(source: AppSource, keyword: String, page: Int): SearchPage = when (source) {
        AppSource.XIAOMI -> market.search(keyword, page)
        AppSource.VIVO -> vivo.search(keyword, page)
        AppSource.WANDOUJIA -> wandoujia.search(keyword, page)
        AppSource.OPPO -> oppo.search(keyword, page)
        AppSource.SAMSUNG -> samsung.search(keyword, page)
        AppSource.HONOR -> honor.search(keyword, page)
        AppSource.HUAWEI -> huawei.search(keyword, page)
        AppSource.TAPTAP -> tapTap.search(keyword, page)
        AppSource.BOX7723 -> box7723.search(keyword, page)
        AppSource.GITHUB -> github.search(keyword, page)
        AppSource.GITLAB -> gitlab.search(keyword, page)
        AppSource.FDROID -> fdroid.search(keyword, page)
        // Google Play 仅用于更新检测，不参与搜索
        AppSource.PLAY -> SearchPage(emptyList(), hasMore = false)
    }

    override suspend fun categoryApps(
        source: AppSource,
        category: AppCategory,
        subCategory: AppSubCategory,
        ranking: GameRanking,
        gameSubCategory: GameSubCategory,
        optionId: String?,
        page: Int,
    ): SearchPage = when {
        category == AppCategory.GAMES && source == AppSource.TAPTAP -> tapTap.rankedGames(ranking, page)
        source == AppSource.HUAWEI -> huawei.rankedApps(games = category == AppCategory.GAMES, page = page)
        source == AppSource.OPPO -> oppo.categoryApps(
            categoryId = optionId ?: oppo.categories(category == AppCategory.GAMES).first().id,
            page = page,
        )

        category == AppCategory.GAMES -> market.categoryApps(gameSubCategory.xiaomiCategoryId, page)
        else -> market.categoryApps(subCategory.xiaomiCategoryId, page)
    }

    override suspend fun categoryOptions(source: AppSource, category: AppCategory): List<CategoryOption> =
        if (source == AppSource.OPPO) oppo.categories(category == AppCategory.GAMES) else emptyList()

    override suspend fun appDetail(
        source: AppSource,
        appId: Long,
        packageName: String,
        externalQuery: String?,
        repoSlug: String,
    ): AppDetail = when (source) {
        AppSource.XIAOMI -> market.appDetail(appId, packageName, externalQuery)
        AppSource.VIVO -> vivo.appDetail(
            resolveStoreAppId(source, packageName, appId) { vivo.search(it, 0) },
            packageName,
        )

        AppSource.WANDOUJIA -> wandoujia.appDetail(
            resolveStoreAppId(source, packageName, appId) { wandoujia.search(it, 0) },
        )

        AppSource.OPPO -> oppo.appDetail(appId, packageName, externalQuery)
        AppSource.SAMSUNG -> samsung.appDetail(appId, packageName, externalQuery)
        AppSource.HONOR -> honor.appDetail(appId, packageName)
        AppSource.HUAWEI -> huawei.appDetail(appId, packageName)
        AppSource.TAPTAP -> tapTap.appDetail(appId, packageName)
        AppSource.BOX7723 -> box7723.appDetail(
            resolveStoreAppId(source, packageName, appId) { box7723.search(it, 0) },
        )

        // 仓库链接只带 slug，没有数字 id；有 slug 时按 slug 取，否则按搜索结果里的 id 取
        AppSource.GITHUB -> repoSlug.takeIf { it.isNotBlank() }
            ?.let { github.appDetailBySlug(it) } ?: github.appDetail(appId)
        AppSource.GITLAB -> repoSlug.takeIf { it.isNotBlank() }
            ?.let { gitlab.appDetailBySlug(it) } ?: gitlab.appDetail(appId)
        // F-Droid 以包名为主键，appId 只是稳定哈希，无需再反查
        AppSource.FDROID -> fdroid.appDetail(appId, packageName)
        // Play 以包名为主键
        AppSource.PLAY -> play.appDetail(packageName)
    }

    override suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments {
        if (!source.capabilities.supportsComments) return AppComments(emptyList(), 0L)
        val xiaomi = app.onXiaomi()
        return market.appComments(xiaomi.appId, xiaomi.versionCode)
    }

    override suspend fun sameDeveloperApps(source: AppSource, app: MarketAppInfo): List<MarketAppInfo> {
        if (!source.capabilities.supportsSameDeveloperApps) return emptyList()
        val xiaomi = app.onXiaomi()
        return filterSameDeveloperCandidates(app.packageName, market.sameDeveloperApps(xiaomi.appId))
    }

    override suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadMeta(app, keyword)
        AppSource.VIVO -> vivo.downloadMeta(app)
        AppSource.WANDOUJIA -> wandoujia.downloadMeta(app)
        AppSource.OPPO -> oppo.downloadMeta(app)
        AppSource.SAMSUNG -> samsung.downloadMeta(app)
        AppSource.HONOR -> honor.downloadMeta(app)
        AppSource.HUAWEI -> huawei.downloadMeta(app)
        AppSource.TAPTAP -> tapTap.downloadMeta(app)
        AppSource.BOX7723 -> box7723.downloadMeta(app)
        AppSource.GITHUB -> github.downloadMeta(app)
        AppSource.GITLAB -> gitlab.downloadMeta(app)
        AppSource.FDROID -> fdroid.downloadMeta(app)
        // Play 不提供 APK 直链，更新动作由调用方改为拉起商店
        AppSource.PLAY -> throw IllegalStateException("Google Play 不提供 APK 直链，请改用商店更新")
    }

    override suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadUpdateMeta(app)
        AppSource.VIVO -> vivo.downloadUpdateMeta(app)
        // 豌豆荚 / 7723 无原生更新元数据协议，回退小米商店解析
        AppSource.WANDOUJIA, AppSource.BOX7723 -> market.downloadUpdateMeta(app.onXiaomi())
        AppSource.OPPO -> oppo.downloadUpdateMeta(app)
        AppSource.SAMSUNG -> samsung.downloadUpdateMeta(app)
        AppSource.HONOR -> honor.downloadUpdateMeta(app)
        AppSource.HUAWEI -> huawei.downloadUpdateMeta(app)
        AppSource.TAPTAP -> tapTap.downloadUpdateMeta(app)
        // 开源来源只提供整包，没有增量补丁
        AppSource.GITHUB -> github.downloadMeta(app)
        AppSource.GITLAB -> gitlab.downloadMeta(app)
        AppSource.FDROID -> fdroid.downloadMeta(app)
        AppSource.PLAY -> throw IllegalStateException("Google Play 不提供 APK 直链，请改用商店更新")
    }

    override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> =
        market.loadReconciledCachedUpdates()

    /**
     * 全渠道聚合检测：并发跑选中的每个来源，按包名合并（同一包名取版本更高的一条）。
     * 除小米（需向上传递「应用列表不可用」异常）外，单源失败按空结果降级，避免一个源拖垮整轮检测。
     */
    override fun checkUpdatesFlow(sources: Set<AppSource>): Flow<List<MarketAppInfo>> {
        if (sources.isEmpty()) return flowOf(emptyList())
        val flows = sources.sortedBy { it.ordinal }.map { sourceUpdatesFlow(it) }
        return combine(flows) { arrays ->
            arrays.fold(emptyList<MarketAppInfo>()) { acc, next ->
                mergeUpdateSources(acc, next as List<MarketAppInfo>)
            }
        }
    }

    private fun sourceUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>> = when (source) {
        AppSource.XIAOMI -> market.checkUpdatesFlow()
        AppSource.VIVO -> degradedFlow { vivo.checkUpdates() }
        AppSource.WANDOUJIA -> degradedFlow { wandoujia.checkUpdates() }
        AppSource.OPPO -> degradedFlow { oppo.checkUpdates() }
        AppSource.SAMSUNG -> degradedFlow { samsung.checkUpdates() }
        AppSource.HONOR -> degradedFlow { honor.checkUpdates() }
        AppSource.HUAWEI -> degradedFlow { huawei.checkUpdates() }
        AppSource.TAPTAP -> degradedFlow { tapTap.checkUpdates() }
        AppSource.BOX7723 -> flowOf(emptyList())
        AppSource.GITHUB -> degradedFlow { github.checkUpdates() }
        AppSource.GITLAB -> degradedFlow { gitlab.checkUpdates() }
        AppSource.FDROID -> degradedFlow { fdroid.checkUpdates() }
        AppSource.PLAY -> degradedFlow { play.checkUpdates() }
    }

    /** 单源检测失败时降级为空结果，保证聚合流程不被单个来源的异常中断。 */
    private fun degradedFlow(block: suspend () -> List<MarketAppInfo>): Flow<List<MarketAppInfo>> = flow {
        emit(runCatching { block() }.getOrDefault(emptyList()))
    }

    override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult =
        when (source) {
            AppSource.XIAOMI -> market.checkManualUpdate(request)
            AppSource.VIVO -> vivo.checkManualUpdate(request)
            // 豌豆荚 / 7723 无原生手动更新协议，回退小米商店解析
            AppSource.WANDOUJIA, AppSource.BOX7723 -> market.checkManualUpdate(request)
            AppSource.OPPO -> oppo.checkManualUpdate(request)
            AppSource.SAMSUNG -> samsung.checkManualUpdate(request)
            AppSource.HONOR -> honor.checkManualUpdate(request)
            AppSource.HUAWEI -> huawei.checkManualUpdate(request)
            AppSource.TAPTAP -> tapTap.checkManualUpdate(request)
            // 开源来源走各自的「按包名检测」通路：F-Droid 可直接反查，GitHub / GitLab 依赖跟踪表
            AppSource.GITHUB -> github.checkUpdate(request.packageName, request.versionCode, request.versionName)
                .toManualUpdateResult()

            AppSource.GITLAB -> gitlab.checkUpdate(request.packageName, request.versionCode, request.versionName)
                .toManualUpdateResult()

            AppSource.FDROID -> fdroid.checkUpdate(request.packageName, request.versionCode, request.versionName)
                .toManualUpdateResult()

            AppSource.PLAY -> play.checkUpdate(request.packageName, request.versionCode, request.versionName)
                .toManualUpdateResult()
        }

    override suspend fun goldMiFeed(source: AppSource, page: Int, pageSize: Int): TodayFeedPage =
        when (source) {
            AppSource.OPPO -> oppo.beautyFeed(page, pageSize)
            // 豌豆荚 / 三星 / 华为 / 荣耀 / 7723 / 开源来源无独立今日内容，回退小米商店今日
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR,
            AppSource.BOX7723, AppSource.GITHUB, AppSource.GITLAB, AppSource.FDROID, AppSource.PLAY,
            -> today.goldMiFeed(page, pageSize)

            AppSource.VIVO -> vivo.auroraFeed(page, pageSize)
            AppSource.TAPTAP -> tapTap.todayFeed(page, pageSize)
        }

    override suspend fun todayArticle(source: AppSource, rId: String): TodayArticle =
        when (source) {
            AppSource.OPPO -> oppo.beautyArticle(rId)
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR,
            AppSource.BOX7723, AppSource.GITHUB, AppSource.GITLAB, AppSource.FDROID, AppSource.PLAY,
            -> today.todayArticle(rId)

            AppSource.VIVO -> vivo.auroraArticle(rId)
            AppSource.TAPTAP -> tapTap.todayArticle(rId)
        }

    private suspend fun MarketAppInfo.onXiaomi(): MarketAppInfo {
        if (source == AppSource.XIAOMI) return this
        return market.appDetail(
            appId = 0L,
            packageName = packageName,
            externalQuery = "id=$packageName",
        ).app.copy(
            installedVersionName = installedVersionName,
            installedVersionCode = installedVersionCode,
            installedOldApkHash = installedOldApkHash,
            installedBaseApkPath = installedBaseApkPath,
            installedSplits = installedSplits,
        )
    }
}

private suspend fun resolveStoreAppId(
    source: AppSource,
    packageName: String,
    appId: Long,
    search: suspend (String) -> SearchPage,
): Long = appId.takeIf { it > 0L } ?: search(packageName).items
    .firstOrNull { it.packageName.equals(packageName, true) }
    ?.appId
?: throw IllegalStateException("${source.token} 未收录该应用")

internal fun filterSameDeveloperCandidates(
    currentPackageName: String,
    candidates: List<MarketAppInfo>,
): List<MarketAppInfo> = candidates
    .filterNot { it.packageName.equals(currentPackageName, ignoreCase = true) }
    .distinctBy { it.packageName.lowercase() }

/** 把开源来源的三态检查结果映射成统一的「手动更新」结果。 */
private fun SourceUpdateCheck.toManualUpdateResult(): ManualUpdateResult = when (this) {
    is SourceUpdateCheck.Available -> ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, app)
    SourceUpdateCheck.UpToDate -> ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
    SourceUpdateCheck.NotFound -> ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
}
