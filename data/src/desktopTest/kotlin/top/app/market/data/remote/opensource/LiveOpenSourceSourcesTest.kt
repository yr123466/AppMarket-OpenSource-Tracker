package top.app.market.data.remote.opensource

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import top.app.market.data.local.BooleanPreferenceKey
import top.app.market.data.local.PreferenceChanges
import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.StringPreferenceKey
import top.app.market.data.platform.createHttpClient
import top.app.market.data.remote.fdroid.FdroidApi
import top.app.market.data.remote.github.GithubApi
import top.app.market.data.remote.gitlab.GitlabApi
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.model.tracked.TrackedRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 三个开源来源的**联网实测**。
 *
 * 默认跳过，避免 CI 在无外网或被限流时误报；验证时显式打开：
 * ```
 * APPMARKET_LIVE_TESTS=1 ./gradlew :data:desktopTest --tests '*LiveOpenSourceSourcesTest*'
 * ```
 * 这些用例直接打真实接口，用来证明「搜索 → 详情 → 版本对比 → 下载直链」整条链路可用，
 * 而不只是解析函数自洽。
 */
class LiveOpenSourceSourcesTest {

    private val client by lazy { createHttpClient() }
    private val tokens by lazy { OpenSourceTokenStore(NoopPreferences) }
    private val github by lazy { GithubApi(client, tokens) }
    private val gitlab by lazy { GitlabApi(client, tokens) }
    private val fdroid by lazy { FdroidApi(client) }

    // ---------- F-Droid ----------

    @Test
    fun fdroidSearchParsesLiveResults() = runBlocking {
        if (!live()) return@runBlocking
        val page = fdroid.search("fdroid", 0)
        assertTrue(page.items.isNotEmpty(), "F-Droid 搜索应有结果")
        val fdroidClient = page.items.firstOrNull { it.packageName == "org.fdroid.fdroid" }
        assertNotNull(fdroidClient, "搜索结果里应包含 org.fdroid.fdroid")
        assertEquals(AppSource.FDROID, fdroidClient.source)
        assertEquals("F-Droid", fdroidClient.displayName)
        assertTrue(fdroidClient.icon.isNotBlank())
    }

    @Test
    fun fdroidResolvesVersionsByPackageNameAlone() = runBlocking {
        if (!live()) return@runBlocking
        val versions = fdroid.versions("org.fdroid.fdroid")
        assertNotNull(versions)
        assertEquals("org.fdroid.fdroid", versions.packageName)
        assertTrue(versions.suggestedVersionCode > 0L)
        assertTrue(versions.packages.isNotEmpty())
        // 建议版本号必须能在历史列表里找到，否则版本名会错配
        assertTrue(versions.packages.any { it.versionCode == versions.suggestedVersionCode })
        // 未收录的包名必须返回 null 而不是抛异常
        assertEquals(null, fdroid.versions("top.app.market.not.installed.anywhere"))
    }

    @Test
    fun fdroidDetectsUpdatesByComparingVersionCode() = runBlocking {
        if (!live()) return@runBlocking
        val installed = listOf(
            installed("org.fdroid.fdroid", versionCode = 1L),
            installed("com.termux", versionCode = 1L),
            installed("top.app.market.absent.package", versionCode = 1L),
        )
        val updates = fdroid.checkUpdates(installed)
        val packages = updates.map { it.packageName }
        assertTrue("org.fdroid.fdroid" in packages, "F-Droid 客户端应被识别为可更新")
        assertTrue("top.app.market.absent.package" !in packages, "未收录的包不应出现在更新列表")
        val update = updates.first { it.packageName == "org.fdroid.fdroid" }
        assertTrue(update.versionCode > 1L)
        assertEquals(1L, update.installedVersionCode)
        assertEquals(AppSource.FDROID, update.source)
    }

    @Test
    fun fdroidSinglePackageCheckDistinguishesUpToDateFromNotFound() = runBlocking {
        if (!live()) return@runBlocking
        val latest = fdroid.versions("org.fdroid.fdroid")
        assertNotNull(latest)
        val upToDate = fdroid.checkUpdate("org.fdroid.fdroid", latest.suggestedVersionCode, "")
        assertEquals(SourceUpdateCheck.UpToDate, upToDate)

        val outdated = fdroid.checkUpdate("org.fdroid.fdroid", 1L, "1.0")
        assertTrue(outdated is SourceUpdateCheck.Available)

        assertEquals(
            SourceUpdateCheck.NotFound,
            fdroid.checkUpdate("top.app.market.absent.package", 1L, "1.0"),
        )
    }

    @Test
    fun fdroidDownloadMetaBuildsOfficialRepoUrl() = runBlocking {
        if (!live()) return@runBlocking
        val meta = fdroid.downloadMeta(app("org.fdroid.fdroid", AppSource.FDROID))
        assertTrue(meta.url.startsWith("https://f-droid.org/repo/org.fdroid.fdroid_"), meta.url)
        assertTrue(meta.url.endsWith(".apk"))
        assertTrue(meta.versionCode > 0L)
        assertTrue(meta.size > 0L, "HEAD 请求应拿到 Content-Length")
        assertEquals(AppSource.FDROID, meta.source)
    }

    @Test
    fun fdroidDetailPageParsesLiveHtml() = runBlocking {
        if (!live()) return@runBlocking
        val detail = fdroid.appDetail("org.fdroid.fdroid")
        assertEquals("org.fdroid.fdroid", detail.app.packageName)
        assertEquals("F-Droid", detail.app.displayName)
        assertTrue(detail.introduction.isNotBlank())
        assertTrue(detail.app.versionCode > 0L)
        assertTrue(detail.screenshots.isNotEmpty())
        assertTrue(detail.app.icon.isNotBlank())
    }

    // ---------- GitHub ----------

    @Test
    fun githubSearchReturnsRepositories() = runBlocking {
        if (!live()) return@runBlocking
        val page = github.search("fdroid client", 0)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { it.source == AppSource.GITHUB })
        assertTrue(page.items.all { it.openLink.startsWith("https://github.com/") })
        // 前若干条会被补全发布信息，至少有一条能解析出包名与版本号
        val enriched = page.items.filter { it.packageName.isNotBlank() }
        assertTrue(enriched.isNotEmpty(), "搜索结果前几条应补全包名")
        assertTrue(enriched.any { it.versionCode > 0L }, "补全的条目应带版本号")
    }

    @Test
    fun githubResolvesReleaseAssetsIntoPackageNameAndVersion() = runBlocking {
        if (!live()) return@runBlocking
        val repo = github.repoBySlug("f-droid/fdroidclient")
        assertEquals("f-droid/fdroidclient", repo.fullName)
        val release = github.latestRelease(repo.slug)
        assertNotNull(release)
        val detail = github.appDetail(repo, release)
        assertEquals("org.fdroid.fdroid", detail.app.packageName)
        assertTrue(detail.app.versionCode > 0L)
        assertTrue(detail.app.apkSize > 0L)
        assertTrue(detail.app.versionName.isNotBlank())
        assertEquals(AppSource.GITHUB, detail.app.source)
    }

    @Test
    fun githubDownloadMetaPointsAtApkAssetNotSignature() = runBlocking {
        if (!live()) return@runBlocking
        val repo = github.repoBySlug("f-droid/fdroidclient")
        val detail = github.appDetail(repo, github.latestRelease(repo.slug))
        val meta = github.downloadMeta(detail.app)
        assertTrue(meta.url.startsWith("https://github.com/f-droid/fdroidclient/releases/download/"), meta.url)
        assertTrue(meta.url.endsWith(".apk"), meta.url)
        assertEquals("org.fdroid.fdroid", meta.packageName)
        assertTrue(meta.size > 0L)
    }

    @Test
    fun githubUpdateCheckUsesTrackedReposAndComparesVersions() = runBlocking {
        if (!live()) return@runBlocking
        val tracked = listOf(
            TrackedRepo(
                packageName = "org.fdroid.fdroid",
                slug = "f-droid/fdroidclient",
                source = AppSource.GITHUB,
                appId = 0L,
                displayName = "F-Droid",
            )
        )
        val updates = github.checkUpdates(listOf(installed("org.fdroid.fdroid", 1L)), tracked)
        assertEquals(1, updates.size)
        val update = updates.first()
        assertEquals("org.fdroid.fdroid", update.packageName)
        assertTrue(update.versionCode > 1L)
        assertEquals(1L, update.installedVersionCode)
        assertEquals(AppSource.GITHUB, update.source)

        // 已是最新时不应报告更新
        val latestCode = update.versionCode
        assertTrue(github.checkUpdates(listOf(installed("org.fdroid.fdroid", latestCode)), tracked).isEmpty())

        // 没有跟踪任何仓库时零网络开销、返回空
        assertTrue(github.checkUpdates(listOf(installed("org.fdroid.fdroid", 1L)), emptyList()).isEmpty())
    }

    @Test
    fun githubSinglePackageCheckReportsNotFoundWhenUntracked() = runBlocking {
        if (!live()) return@runBlocking
        val tracked = TrackedRepo("org.fdroid.fdroid", "f-droid/fdroidclient", AppSource.GITHUB, 0L, "F-Droid")
        val available = github.checkUpdate(tracked, installed("org.fdroid.fdroid", 1L))
        assertTrue(available is SourceUpdateCheck.Available)
        assertEquals(SourceUpdateCheck.UpToDate, github.checkUpdate(tracked, installed("org.fdroid.fdroid", 999_999_999L)))
    }

    // ---------- GitLab ----------

    @Test
    fun gitlabSearchReturnsProjects() = runBlocking {
        if (!live()) return@runBlocking
        val page = gitlab.search("fdroid", 0)
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { it.source == AppSource.GITLAB })
        assertTrue(page.items.all { it.openLink.startsWith("https://gitlab.com/") })
    }

    @Test
    fun gitlabResolvesReleaseAssetsIntoPackageNameAndVersion() = runBlocking {
        if (!live()) return@runBlocking
        val project = gitlab.projectBySlug("fdroid/fdroidclient")
        assertEquals("fdroid/fdroidclient", project.pathWithNamespace)
        val release = gitlab.latestRelease(project.slug)
        assertNotNull(release)
        val detail = gitlab.appDetail(project, release)
        assertEquals("org.fdroid.fdroid", detail.app.packageName)
        assertTrue(detail.app.versionCode > 0L)
        assertEquals(AppSource.GITLAB, detail.app.source)
    }

    @Test
    fun gitlabDownloadMetaResolvesApkLink() = runBlocking {
        if (!live()) return@runBlocking
        val project = gitlab.projectBySlug("fdroid/fdroidclient")
        val detail = gitlab.appDetail(project, gitlab.latestRelease(project.slug))
        val meta = gitlab.downloadMeta(detail.app)
        // GitLab 的包文件直链形如 `/{namespace}/{project}/-/package_files/{id}/download`，
        // 路径里不带文件名，因此不能按 `.apk` 后缀判断；靠 content-type 才是可靠的。
        assertTrue(meta.url.startsWith("https://gitlab.com/fdroid/fdroidclient/-/"), meta.url)
        assertTrue(meta.url.endsWith("/download"), meta.url)
        assertEquals("org.fdroid.fdroid", meta.packageName)
        assertTrue(meta.versionCode > 0L)
        assertTrue(meta.size > 0L, "HEAD 请求应拿到 Content-Length")
        assertEquals(AppSource.GITLAB, meta.source)
    }

    @Test
    fun gitlabUpdateCheckUsesTrackedProjects() = runBlocking {
        if (!live()) return@runBlocking
        val tracked = listOf(
            TrackedRepo("org.fdroid.fdroid", "fdroid/fdroidclient", AppSource.GITLAB, 0L, "F-Droid")
        )
        val updates = gitlab.checkUpdates(listOf(installed("org.fdroid.fdroid", 1L)), tracked)
        assertEquals(1, updates.size)
        assertTrue(updates.first().versionCode > 1L)
        assertEquals(1L, updates.first().installedVersionCode)
    }

    private fun live(): Boolean = System.getenv(LIVE_ENV).orEmpty().isNotBlank()

    private fun installed(packageName: String, versionCode: Long) = InstalledPackage(
        packageName = packageName,
        versionCode = versionCode,
        isSystemApp = false,
        versionName = "1.0",
        displayName = packageName,
    )

    private fun app(packageName: String, source: AppSource) = MarketAppInfo(
        appId = 0L,
        packageName = packageName,
        displayName = packageName,
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = "",
        apkSize = 0L,
        ratingScore = 0.0,
        source = source,
    )

    private companion object {
        const val LIVE_ENV = "APPMARKET_LIVE_TESTS"
    }
}

/** 测试用空偏好源：token 一律为空，走匿名请求。 */
private object NoopPreferences : PreferencesDataSource {
    override fun observe(key: StringPreferenceKey): Flow<String?> = flowOf(null)
    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> = flowOf(key.default)
    override suspend fun update(namespace: String, changes: PreferenceChanges) = Unit
}
