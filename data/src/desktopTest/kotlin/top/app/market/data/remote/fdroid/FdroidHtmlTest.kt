package top.app.market.data.remote.fdroid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-Droid 网页解析的离线回归测试。
 *
 * 样例取自 `search.f-droid.org` 与 `f-droid.org/{lang}/packages/{包名}/` 的真实响应
 * （已裁掉无关节点）。页面改版时这里会先红，而不是等到用户搜索不出结果。
 */
class FdroidHtmlTest {

    @Test
    fun parsesSearchResultCards() {
        val items = FdroidHtml.parseSearch(SEARCH_PAGE)
        assertEquals(listOf("eu.bubu1.fdroidclassic", "org.fdroid.fdroid"), items.map { it.packageName })

        val first = items.first()
        assertEquals("F-Droid Classic", first.displayName)
        assertEquals("F-Droid client with the classic UI", first.summary)
        assertEquals("GPL-3.0-or-later", first.license)
        assertEquals("https://f-droid.org/assets/ic_repo_app_default.png", first.iconUrl)

        assertEquals("F-Droid", items[1].displayName)
        assertEquals("The app store that respects freedom and privacy", items[1].summary)
    }

    @Test
    fun searchParsingToleratesMissingLicenseAndDeduplicates() {
        val html = """
            <a class="package-header" href="https://f-droid.org/en/packages/com.example.app">
              <img class="package-icon" src="https://example.org/icon.png" />
              <div class="package-info">
                <h4 class="package-name">Example</h4>
                <div class="package-desc"><span class="package-summary">Just an example</span></div>
              </div>
            </a>
            <a class="package-header" href="https://f-droid.org/en/packages/com.example.app">
              <h4 class="package-name">Example again</h4>
            </a>
        """.trimIndent()
        val items = FdroidHtml.parseSearch(html)
        assertEquals(1, items.size)
        assertEquals("", items.first().license)
    }

    @Test
    fun parsesPackageDetailPage() {
        val detail = FdroidHtml.parseDetail(DETAIL_PAGE, "org.fdroid.fdroid")

        assertEquals("org.fdroid.fdroid", detail.packageName)
        assertEquals("F-Droid", detail.displayName)
        assertEquals("The app store that respects freedom and privacy", detail.summary)
        assertEquals("2.0.2", detail.latestVersionName)
        assertEquals(2000052L, detail.latestVersionCode)
        assertEquals("https://f-droid.org/repo/org.fdroid.fdroid_2000052.apk", detail.latestApkUrl)
        assertEquals(11L * 1024 * 1024, detail.latestApkSize)
        assertEquals("GNU General Public License v3.0 or later", detail.license)
        assertEquals("https://f-droid.org", detail.website)
        assertEquals("https://gitlab.com/fdroid/fdroidclient", detail.sourceCode)
        assertEquals(2, detail.screenshots.size)
        assertTrue(detail.description.startsWith("F-Droid is an installable catalogue"))
        assertTrue(detail.changeLog.contains("UI rewritten from scratch"))
    }

    @Test
    fun detailParsingSurvivesMissingOptionalNodes() {
        val html = """<h3 class="package-name">Bare</h3>"""
        val detail = FdroidHtml.parseDetail(html, "com.example.bare")
        assertEquals("Bare", detail.displayName)
        assertEquals("", detail.summary)
        assertEquals(0L, detail.latestVersionCode)
        assertEquals("", detail.latestApkUrl)
        assertEquals(0L, detail.latestApkSize)
        assertTrue(detail.screenshots.isEmpty())
    }

    @Test
    fun parsesDisplaySizes() {
        assertEquals(11L * 1024 * 1024, parseDisplaySize("11 MiB"))
        assertEquals((1.2 * 1024 * 1024 * 1024).toLong(), parseDisplaySize("1.2 GiB"))
        assertEquals(512L * 1024, parseDisplaySize("512 KiB"))
        assertEquals(0L, parseDisplaySize(""))
        assertEquals(0L, parseDisplaySize("unknown"))
    }

    @Test
    fun decodesHtmlEntitiesAndStripsTags() {
        val html = """<div class="package-description" dir="auto">Tom &amp; Jerry<br>say &#39;hi&#39;</div>"""
        val detail = FdroidHtml.parseDetail(html, "com.example.app")
        assertEquals("Tom & Jerry\nsay 'hi'", detail.description)
    }

    private companion object {
        val SEARCH_PAGE = """
            <a class="package-header" href="https://f-droid.org/en/packages/eu.bubu1.fdroidclassic">
              <img class="package-icon" src="https://f-droid.org/assets/ic_repo_app_default.png" />
              <div class="package-info">
                <h4 class="package-name">
                  F-Droid Classic
                </h4>
                <div class="package-desc">
                  <span class="package-summary">F-Droid client with the classic UI</span><span class="package-license">GPL-3.0-or-later</span>
                </div>
              </div>
            </a>
            <a class="package-header" href="https://f-droid.org/en/packages/org.fdroid.fdroid">
              <img class="package-icon" src="https://ftp.fau.de/fdroid/repo/org.fdroid.fdroid/en-US/icon_x=.png" />
              <div class="package-info">
                <h4 class="package-name">
                  F-Droid
                </h4>
                <div class="package-desc">
                  <span class="package-summary">The app store that respects freedom and privacy</span><span class="package-license">GPL-3.0-or-later</span>
                </div>
              </div>
            </a>
        """.trimIndent()

        val DETAIL_PAGE = """
            <header class="package-header">
              <img class="package-icon" src="https://f-droid.org/repo/org.fdroid.fdroid/en-US/icon_x=.png" alt="package icon" />
              <div class="package-title">
                <h3 class="package-name">
                  F-Droid
                </h3>
                <div class="package-summary">
                  The app store that respects freedom and privacy
                </div>
              </div>
            </header>
            <div class="package-description" dir="auto">F-Droid is an installable catalogue of libre software apps for Android.<br><br>It connects to any F-Droid compatible repositories.</div>
            <ul>
              <li class="package-link" id="license">License: <a href="https://spdx.org/licenses/GPL-3.0-or-later.html">GNU General Public License v3.0 or later</a></li>
              <li class="package-link" id="website"> <a href="https://f-droid.org">Website</a> </li>
              <li class="package-link" id="source_code"> <a href="https://gitlab.com/fdroid/fdroidclient">Source Code</a> </li>
            </ul>
            <div id="screenshots" class="js_slides screenshots">
              <ul class="slides js_slides gallery">
                <li class="js_slide screenshot"><img src="https://f-droid.org/repo/org.fdroid.fdroid/en-US/phoneScreenshots/01-latest.png" alt="app screenshot" /></li>
                <li class="js_slide screenshot"><img src="https://f-droid.org/repo/org.fdroid.fdroid/en-US/phoneScreenshots/02-categories.png" alt="app screenshot" /></li>
              </ul>
            </div>
            <div class="package-versions">
              <ul class="package-versions-list">
                <div class="package-whats-new">
                  <div class="new-in-version">New in version 2.0.2</div>
                  <div dir="auto"> * UI rewritten from scratch with Kotlin compose<br>* improved search </div>
                </div>
                <li class="package-version" id="latest">
                  <div class="package-version-header">
                    <a name="2.0.2"></a>
                    <a name="2000052"></a>
                    <b>Version 2.0.2</b> (2000052) <span class="beta-badge">beta</span> Added on Oct 12, 2026
                  </div>
                  <p class="package-version-requirement"> This version requires Android 7.0 or newer. </p>
                  <p class="package-version-download">
                    <b> <a href="https://f-droid.org/repo/org.fdroid.fdroid_2000052.apk"> Download APK </a> </b> 11 MiB
                    <a href="https://f-droid.org/repo/org.fdroid.fdroid_2000052.apk.asc">PGP Signature</a>
                  </p>
                </li>
                <li class="package-version" >
                  <div class="package-version-header">
                    <b>Version 2.0.1</b> (2000051) Added on Sep 29, 2026
                  </div>
                  <p class="package-version-download">
                    <b> <a href="https://f-droid.org/repo/org.fdroid.fdroid_2000051.apk"> Download APK </a> </b> 11 MiB
                  </p>
                </li>
              </ul>
            </div>
        """.trimIndent()
    }
}
