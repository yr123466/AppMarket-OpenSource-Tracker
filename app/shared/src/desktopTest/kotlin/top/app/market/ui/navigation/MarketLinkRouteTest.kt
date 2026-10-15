package top.app.market.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketLink
import top.app.market.domain.model.market.MarketLinkParser

/**
 * 商店链接 → 详情页路由。重点是开源来源：GitHub / GitLab 的仓库链接只有 slug，
 * 没有包名也没有站内 id，早期版本会在路由这一步被直接丢弃，导致链接点不开。
 */
class MarketLinkRouteTest {

    private fun routeOf(uri: String): Route.AppDetail? =
        MarketLinkParser.parse(uri)?.let { marketLinkRoute(it) }

    @Test
    fun `GitHub 仓库链接按 slug 打开`() {
        val route = assertNotNull(routeOf("https://github.com/f-droid/fdroidclient"))
        assertEquals(AppSource.GITHUB, route.source)
        assertEquals("f-droid/fdroidclient", route.repoSlug)
        assertEquals(0L, route.appId)
        assertEquals("", route.packageName)
        assertEquals(null, route.externalQuery)
    }

    @Test
    fun `GitHub 发布页链接同样按 slug 打开`() {
        val route = assertNotNull(routeOf("https://github.com/f-droid/fdroidclient/releases/tag/1.18.0"))
        assertEquals(AppSource.GITHUB, route.source)
        assertEquals("f-droid/fdroidclient", route.repoSlug)
    }

    @Test
    fun `GitLab 子组链接保留完整 slug`() {
        val route = assertNotNull(routeOf("https://gitlab.com/group/subgroup/project"))
        assertEquals(AppSource.GITLAB, route.source)
        assertEquals("group/subgroup/project", route.repoSlug)
    }

    @Test
    fun `F-Droid 链接按包名打开对应来源`() {
        val route = assertNotNull(routeOf("https://f-droid.org/en/packages/org.fdroid.fdroid/"))
        assertEquals(AppSource.FDROID, route.source)
        assertEquals("org.fdroid.fdroid", route.packageName)
        assertEquals("", route.repoSlug)
    }

    @Test
    fun `统一格式里的 repo 参数也能还原成仓库路由`() {
        val link = MarketLink("", AppSource.GITHUB, repoSlug = "owner/repo")
        val route = assertNotNull(marketLinkRoute(link))
        assertEquals(AppSource.GITHUB, route.source)
        assertEquals("owner/repo", route.repoSlug)
        // 分享出去的统一格式链接再解析回来，仍然认得这个仓库
        assertEquals("owner/repo", assertNotNull(MarketLinkParser.parse(link.toUnifiedUri())).repoSlug)
    }

    @Test
    fun `小米链接沿用原查询参数`() {
        val route = assertNotNull(routeOf("https://app.mi.com/details?id=com.tencent.mm&ref=search"))
        assertEquals(AppSource.XIAOMI, route.source)
        assertEquals("com.tencent.mm", route.packageName)
    }

    @Test
    fun `无对应来源的链接回退小米`() {
        val route = assertNotNull(routeOf("https://play.google.com/store/apps/details?id=com.tencent.mm"))
        assertEquals(AppSource.XIAOMI, route.source)
        assertEquals("com.tencent.mm", route.packageName)
        assertEquals("id=com.tencent.mm", route.externalQuery)
    }

    @Test
    fun `没有可打开目标的链接返回 null`() {
        assertNull(routeOf("market://details?name=notapackage"))
        assertNull(routeOf("https://www.baidu.com/s?wd=com.tencent.mm"))
    }
}
