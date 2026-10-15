package top.app.market.domain.model.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarketLinkParserTest {
    private fun assertLink(uri: String, packageName: String, source: AppSource?, storeAppId: Long = 0L) {
        assertEquals(MarketLink(packageName, source, storeAppId), MarketLinkParser.parse(uri), uri)
    }

    @Test
    fun unifiedFormatRoundTrips() {
        val link = MarketLink("com.tencent.mm", AppSource.OPPO)
        assertEquals("appmarket://details?id=com.tencent.mm&source=oppo", link.toUnifiedUri())
        assertEquals(link, MarketLinkParser.parse(link.toUnifiedUri()))
    }

    @Test
    fun huaweiSchemeWithoutSourceMapsToHuawei() {
        assertLink("appmarket://details?id=com.tencent.mm", "com.tencent.mm", AppSource.HUAWEI)
        assertLink("hiapp://com.huawei.appmarket?pkgName=com.tencent.mm", "com.tencent.mm", AppSource.HUAWEI)
    }

    @Test
    fun storeSchemes() {
        assertLink("market://details?id=com.tencent.mm", "com.tencent.mm", null)
        assertLink("mimarket://details?id=com.tencent.mm&ref=x", "com.tencent.mm", AppSource.XIAOMI)
        assertLink("vivomarket://details?id=com.tencent.mm", "com.tencent.mm", AppSource.VIVO)
        assertLink("oppomarket://details?packagename=com.tencent.mm", "com.tencent.mm", AppSource.OPPO)
        assertLink("oaps://mk/dt?pkg=com.tencent.mm", "com.tencent.mm", AppSource.OPPO)
        assertLink("honormarket://details?id=com.tencent.mm", "com.tencent.mm", AppSource.HONOR)
        assertLink("samsungapps://ProductDetail/com.tencent.mm", "com.tencent.mm", AppSource.SAMSUNG)
    }

    @Test
    fun webLinks() {
        assertLink("https://app.mi.com/details?id=com.tencent.mm", "com.tencent.mm", AppSource.XIAOMI)
        assertLink("https://m.app.mi.com/#page=detail&id=com.tencent.mm", "com.tencent.mm", AppSource.XIAOMI)
        assertLink("https://play.google.com/store/apps/details?id=com.tencent.mm&hl=zh", "com.tencent.mm", null)
        assertLink(
            "https://appgallery.cloud.huawei.com/appDetail?pkgName=com.tencent.mm",
            "com.tencent.mm",
            AppSource.HUAWEI,
        )
        assertLink("https://h5.appstore.vivo.com.cn/#/details?pkgName=com.tencent.mm", "com.tencent.mm", AppSource.VIVO)
        assertLink("https://galaxystore.samsung.com/detail/com.tencent.mm", "com.tencent.mm", AppSource.SAMSUNG)
        assertLink("https://www.wandoujia.com/apps/com.tencent.mm", "com.tencent.mm", AppSource.WANDOUJIA)
        assertLink("https://www.coolapk.com/apk/com.tencent.mm", "com.tencent.mm", null)
    }

    @Test
    fun tapTapLinksResolveByStoreId() {
        assertLink("https://www.taptap.cn/app/168332", "", AppSource.TAPTAP, 168332L)
        assertLink("https://www.taptap.cn/app/168332?os=android", "", AppSource.TAPTAP, 168332L)
        assertLink("taptap://taptap.com/app?app_id=168332", "", AppSource.TAPTAP, 168332L)
    }

    @Test
    fun huaweiLinksWithOnlyAppId() {
        val real = "https://appgallery.huawei.com/app/C100404489?sharePrepath=ag&locale=zh_TW&source=appshare" +
                "&subsource=C100404489&shareTo=copylink&shareFrom=appmarket&callType=SHARE"
        assertLink(real, "", AppSource.HUAWEI, 100404489L)
        assertLink("https://appgallery.cloud.huawei.com/ag/n/app/C100404489?locale=zh_TW", "", AppSource.HUAWEI, 100404489L)
        assertLink("https://appgallery.huawei.com/#/app/C100404489", "", AppSource.HUAWEI, 100404489L)
        // 路径里的数字必须带 C 前缀，不能把任意数字当成应用 id
        assertNull(MarketLinkParser.parse("https://appgallery.huawei.com/app/100404489"))
    }

    @Test
    fun storeAppIdOnlyLinksRoundTripThroughUnifiedFormat() {
        val huawei = MarketLink("", AppSource.HUAWEI, 100404489L)
        assertEquals("appmarket://details?source=huawei&appId=100404489", huawei.toUnifiedUri())
        assertEquals(huawei, MarketLinkParser.parse(huawei.toUnifiedUri()))
        val taptap = MarketLink("", AppSource.TAPTAP, 168332L)
        assertEquals(taptap, MarketLinkParser.parse(taptap.toUnifiedUri()))
    }

    @Test
    fun findsShortLinkInsideShareText() {
        val text = "我在 華為應用程式商店 找到一個很棒的應用程式，試試看吧！https://url.cloud.huawei.com/Cn9gXTtP6o"
        assertEquals("https://url.cloud.huawei.com/Cn9gXTtP6o", MarketLinkParser.findShortLink(text))
        // 短链接本身不能直接解析，需要联网展开；也不会去展开未知域名
        assertNull(MarketLinkParser.parseText(text))
        assertNull(MarketLinkParser.findShortLink("https://www.baidu.com/abc"))
        assertNull(MarketLinkParser.findShortLink("https://url.cloud.huawei.com/"))
    }

    @Test
    fun findsLinkInsideShareText() {
        val text = "【微信】快来下载吧！https://app.mi.com/details?id=com.tencent.mm，点击链接打开"
        assertEquals(MarketLink("com.tencent.mm", AppSource.XIAOMI), MarketLinkParser.parseText(text))
    }

    @Test
    fun rejectsUnrelatedLinksAndFileNames() {
        assertNull(MarketLinkParser.parse("https://www.baidu.com/s?wd=com.tencent.mm"))
        assertNull(MarketLinkParser.parse("https://galaxystore.samsung.com/appquery/appDetail.as"))
        assertNull(MarketLinkParser.parse("https://www.taptap.cn/moment/123"))
        assertNull(MarketLinkParser.parseText("今天天气不错"))
        assertNull(MarketLinkParser.parse("market://details?id=notapackage"))
    }

    // ---------- 开源分发来源 ----------

    @Test
    fun githubLinksResolveToRepoSlug() {
        assertEquals(
            MarketLink("", AppSource.GITHUB, repoSlug = "f-droid/fdroidclient"),
            MarketLinkParser.parse("https://github.com/f-droid/fdroidclient"),
        )
        // 发布页、tag 页、树页面都应回到仓库本身
        assertEquals(
            MarketLink("", AppSource.GITHUB, repoSlug = "f-droid/fdroidclient"),
            MarketLinkParser.parse("https://github.com/f-droid/fdroidclient/releases/tag/1.18.0"),
        )
        assertEquals(
            MarketLink("", AppSource.GITHUB, repoSlug = "owner/repo"),
            MarketLinkParser.parse("https://github.com/owner/repo.git"),
        )
        assertEquals(
            MarketLink("", AppSource.GITHUB, repoSlug = "owner/repo"),
            MarketLinkParser.parse("https://github.com/owner/repo/tree/main"),
        )
    }

    @Test
    fun githubSitePagesAreNotMistakenForRepositories() {
        assertNull(MarketLinkParser.parse("https://github.com/search?q=fdroid"))
        assertNull(MarketLinkParser.parse("https://github.com/trending"))
        assertNull(MarketLinkParser.parse("https://github.com/topics/android"))
        assertNull(MarketLinkParser.parse("https://github.com/sponsors/someone"))
        assertNull(MarketLinkParser.parse("https://github.com/settings/profile"))
        // 只有 owner 一段，不构成仓库
        assertNull(MarketLinkParser.parse("https://github.com/f-droid"))
    }

    @Test
    fun gitlabLinksKeepSubgroupsAndDropPageAnchors() {
        assertEquals(
            MarketLink("", AppSource.GITLAB, repoSlug = "fdroid/fdroidclient"),
            MarketLinkParser.parse("https://gitlab.com/fdroid/fdroidclient"),
        )
        assertEquals(
            MarketLink("", AppSource.GITLAB, repoSlug = "fdroid/fdroidclient"),
            MarketLinkParser.parse("https://gitlab.com/fdroid/fdroidclient/-/releases"),
        )
        assertEquals(
            MarketLink("", AppSource.GITLAB, repoSlug = "group/subgroup/project"),
            MarketLinkParser.parse("https://gitlab.com/group/subgroup/project"),
        )
        assertNull(MarketLinkParser.parse("https://gitlab.com/explore/projects"))
        assertNull(MarketLinkParser.parse("https://gitlab.com/fdroid"))
    }

    @Test
    fun fdroidLinksResolveToPackageName() {
        assertEquals(
            MarketLink("org.fdroid.fdroid", AppSource.FDROID),
            MarketLinkParser.parse("https://f-droid.org/en/packages/org.fdroid.fdroid/"),
        )
        assertEquals(
            MarketLink("org.fdroid.fdroid", AppSource.FDROID),
            MarketLinkParser.parse("https://f-droid.org/packages/org.fdroid.fdroid"),
        )
        assertEquals(
            MarketLink("org.fdroid.fdroid", AppSource.FDROID),
            MarketLinkParser.parse("https://f-droid.org/zh_Hans/packages/org.fdroid.fdroid/"),
        )
        assertNull(MarketLinkParser.parse("https://f-droid.org/en/"))
    }

    @Test
    fun openSourceLinksRoundTripThroughUnifiedFormat() {
        val github = MarketLink("", AppSource.GITHUB, repoSlug = "f-droid/fdroidclient")
        assertEquals("appmarket://details?source=github&repo=f-droid%2Ffdroidclient", github.toUnifiedUri())
        assertEquals(github, MarketLinkParser.parse(github.toUnifiedUri()))

        val gitlab = MarketLink("", AppSource.GITLAB, repoSlug = "group/subgroup/project")
        assertEquals(gitlab, MarketLinkParser.parse(gitlab.toUnifiedUri()))

        val fdroid = MarketLink("org.fdroid.fdroid", AppSource.FDROID)
        assertEquals(fdroid, MarketLinkParser.parse(fdroid.toUnifiedUri()))
    }

    @Test
    fun findsOpenSourceLinkInsideShareText() {
        val text = "推荐一个开源播放器 https://github.com/owner/player ，很干净"
        assertEquals(
            MarketLink("", AppSource.GITHUB, repoSlug = "owner/player"),
            MarketLinkParser.parseText(text),
        )
    }
}
