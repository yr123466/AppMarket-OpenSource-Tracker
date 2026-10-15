package top.app.market.domain.model.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSourceTest {
    @Test
    fun parseSupportsMultiSelection() {
        // 更新来源多选：解析出全部命中的来源
        assertEquals(setOf(AppSource.XIAOMI, AppSource.VIVO), AppSource.parse("xiaomi,vivo"))
        assertEquals(setOf(AppSource.WANDOUJIA), AppSource.parse("wandoujia"))
        // 未知 token 回退默认值
        assertEquals(AppSource.Default, AppSource.parse("unknown-source"))
        // 默认更新来源集合覆盖全部支持更新检测的源
        assertEquals(AppSource.AllUpdateSources, AppSource.parse(AppSource.serialize(AppSource.AllUpdateSources)))
        assertTrue(AppSource.PLAY in AppSource.AllUpdateSources)
    }

    @Test
    fun capabilitiesMatchRoutingContract() {
        // 评论 / 同开发者应用目前只有小米与 OPPO 支持
        assertTrue(AppSource.XIAOMI.capabilities.supportsComments)
        assertTrue(AppSource.XIAOMI.capabilities.supportsSameDeveloperApps)
        assertTrue(AppSource.OPPO.capabilities.supportsComments)
        assertTrue(AppSource.OPPO.capabilities.supportsSameDeveloperApps)
        assertFalse(AppSource.VIVO.capabilities.supportsComments)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsComments)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsComments)
        assertFalse(AppSource.HUAWEI.capabilities.supportsComments)

        // 小米 / vivo / 豌豆荚优先走 openLink 深链，其余优先打开已安装应用
        assertTrue(AppSource.XIAOMI.capabilities.prefersOpenLinkLaunch)
        assertTrue(AppSource.VIVO.capabilities.prefersOpenLinkLaunch)
        assertTrue(AppSource.WANDOUJIA.capabilities.prefersOpenLinkLaunch)
        assertFalse(AppSource.OPPO.capabilities.prefersOpenLinkLaunch)
        assertFalse(AppSource.SAMSUNG.capabilities.prefersOpenLinkLaunch)
        assertFalse(AppSource.HUAWEI.capabilities.prefersOpenLinkLaunch)

        // 只有三星不报告可信 delta 大小
        assertFalse(AppSource.SAMSUNG.capabilities.reportsDeltaSize)
        assertTrue(AppSource.XIAOMI.capabilities.reportsDeltaSize)
        assertTrue(AppSource.HUAWEI.capabilities.reportsDeltaSize)

        // 豌豆荚 / 三星 / 华为 / 荣耀没有独立今日页内容
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsTodayFeed)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsTodayFeed)
        assertFalse(AppSource.HUAWEI.capabilities.supportsTodayFeed)
        assertFalse(AppSource.HONOR.capabilities.supportsTodayFeed)
        assertTrue(AppSource.XIAOMI.capabilities.supportsTodayFeed)
        assertTrue(AppSource.VIVO.capabilities.supportsTodayFeed)
        assertTrue(AppSource.OPPO.capabilities.supportsTodayFeed)
        assertTrue(AppSource.DefaultTodaySource.capabilities.supportsTodayFeed)

        // 搜索过滤 / 详情优惠目前只有对应源会声明
        assertTrue(AppSource.XIAOMI.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.XIAOMI.capabilities.supportsQuickAppFilter)
        assertTrue(AppSource.XIAOMI.capabilities.supportsReservationFilter)
        assertTrue(AppSource.XIAOMI.capabilities.supportsPromotions)
        assertTrue(AppSource.VIVO.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.OPPO.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.HONOR.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.HUAWEI.capabilities.supportsSearchAdsFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsSearchAdsFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsQuickAppFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsReservationFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsPromotions)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsSearchAdsFilter)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsPromotions)
    }

    @Test
    fun openSourcesDeclareUpdatesButNoStoreSpecificFeatures() {
        // 三个开源来源只提供「搜索 / 详情 / 更新 / 下载」，没有评论、今日内容与增量补丁
        for (source in AppSource.OpenSources) {
            assertTrue(source.capabilities.supportsUpdates, "$source 应支持更新检查")
            assertFalse(source.capabilities.supportsComments, "$source 不应声明评论")
            assertFalse(source.capabilities.supportsSameDeveloperApps, "$source 不应声明同开发者应用")
            assertFalse(source.capabilities.supportsTodayFeed, "$source 不应声明今日内容")
            assertFalse(source.capabilities.reportsDeltaSize, "$source 不应报告 delta 大小")
            assertFalse(source.capabilities.supportsDeltaUpdates, "$source 不应支持增量更新")
            // 发布页不是应用深链：已安装时先拉起本地应用
            assertFalse(source.capabilities.prefersOpenLinkLaunch, "$source 不应优先打开深链")
        }
        assertEquals(
            listOf(AppSource.GITHUB, AppSource.GITLAB, AppSource.FDROID),
            AppSource.OpenSources,
        )
        assertEquals(listOf(AppSource.GITHUB, AppSource.GITLAB), AppSource.RepoSlugSources)
    }

    @Test
    fun openSourcesAreAddressableByTokenAndExcludedFromTodayAndCategoryDefaults() {
        assertEquals(AppSource.GITHUB, AppSource.fromToken("github"))
        assertEquals(AppSource.GITLAB, AppSource.fromToken("GitLab"))
        assertEquals(AppSource.FDROID, AppSource.fromToken("fdroid"))
        assertEquals("github,gitlab,fdroid", AppSource.serialize(AppSource.OpenSources.toSet()))

        // 它们没有独立今日内容与分区接口，不应出现在这两个默认值里
        assertFalse(AppSource.DefaultTodaySource in AppSource.OpenSources)
        assertFalse(AppSource.DefaultCategorySource in AppSource.OpenSources)
        assertTrue(AppSource.OpenSources.none { it in AppSource.CategorySources })
    }

    @Test
    fun everySourceHasAStableNonEmptyToken() {
        val tokens = AppSource.entries.map { it.token }
        assertEquals(tokens.size, tokens.toSet().size, "来源 token 必须唯一")
        assertTrue(tokens.all { it.isNotBlank() })
        for (source in AppSource.entries) {
            assertEquals(source, AppSource.fromToken(source.token))
        }
    }
}
