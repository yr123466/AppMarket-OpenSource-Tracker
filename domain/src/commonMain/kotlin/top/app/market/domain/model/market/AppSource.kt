package top.app.market.domain.model.market

/** 搜索结果来源。[MarketAppInfo.appId] 语义随来源而变，均为对应商店的站内应用 id。 */
enum class AppSource(val token: String, val capabilities: SourceCapabilities) {
    XIAOMI(
        "xiaomi",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = true,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = true,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
            supportsQuickAppFilter = true,
            supportsReservationFilter = true,
            supportsPromotions = true,
        ),
    ),
    VIVO(
        "vivo",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    WANDOUJIA(
        "wandoujia",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = true,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // 豌豆荚无原生更新元数据协议，更新下载与手动更新均回退小米，不提供独立更新来源
            supportsUpdates = false,
        ),
    ),
    OPPO(
        "oppo",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = true,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    SAMSUNG(
        "samsung",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),
    HONOR(
        "honor",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = true,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    HUAWEI(
        "huawei",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = true,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    TAPTAP(
        "taptap",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsDeltaUpdates = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),
    BOX7723(
        "7723",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = false,
        ),
    ),

    /** GitHub Releases：按 owner/repo 跟踪，从发布资产名 `{包名}_{versionCode}.apk` 还原包名与版本号。 */
    GITHUB(
        "github",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            // 发布页链接不是应用深链：已安装时优先拉起本地应用，未安装才回退打开仓库页
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),

    /** GitLab Releases：与 GitHub 同构，按 namespace/project 跟踪。 */
    GITLAB(
        "gitlab",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),

    /** F-Droid 官方仓库：唯一支持「按包名反查」的源，可直接对全部已安装应用做版本对比。 */
    FDROID(
        "fdroid",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    );

    companion object {
        val Default: Set<AppSource> = setOf(WANDOUJIA)

        /**
         * 开源分发来源（GitHub / GitLab / F-Droid）。
         * 三者都没有评论、同开发者应用、今日内容与原生分区接口，聚合层共用同一条回退路径。
         */
        val OpenSources: List<AppSource> = listOf(GITHUB, GITLAB, FDROID)

        /** 按 `owner/repo`（GitHub）或 `namespace/project`（GitLab）跟踪的来源。 */
        val RepoSlugSources: List<AppSource> = listOf(GITHUB, GITLAB)

        /** 今日页内容来源默认值；须为声明了 [SourceCapabilities.supportsTodayFeed] 的源。 */
        val DefaultTodaySource: AppSource = XIAOMI

        /** 游戏 / 应用分区的内容来源默认值；其余来源无原生分区接口，会回退小米。 */
        val DefaultCategorySource: AppSource = XIAOMI

        /** 提供原生分区内容的来源，仅这些值有意义。 */
        val CategorySources: List<AppSource> = listOf(XIAOMI, TAPTAP, HUAWEI, OPPO)

        /** 更新来源默认值；须为声明了 [SourceCapabilities.supportsUpdates] 的源。 */
        val DefaultUpdateSource: AppSource = XIAOMI

        fun parse(raw: String?): Set<AppSource> {
            if (raw.isNullOrBlank()) return Default
            // 旧版本允许多选；设置页改为单选后按枚举顺序保留第一个有效来源。
            val selected = entries.firstOrNull { source ->
                raw.split(',').any { it.trim() == source.token }
            }
            return selected?.let(::setOf) ?: Default
        }

        fun serialize(sources: Set<AppSource>): String =
            entries.filter { it in sources }.joinToString(",") { it.token }

        fun fromToken(raw: String?): AppSource? =
            entries.firstOrNull { it.token.equals(raw, ignoreCase = true) }
    }
}
