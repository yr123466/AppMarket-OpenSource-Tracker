package top.app.market.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.app.market.data.remote.github.GithubApi
import top.app.market.data.remote.github.normalizedSlug
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.model.tracked.TrackedRepo
import top.app.market.domain.repository.GithubRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.TrackedRepoRepository

internal class GithubRepositoryImpl(
    private val api: GithubApi,
    private val installedPackages: InstalledPackagesRepository,
    private val trackedRepos: TrackedRepoRepository,
) : GithubRepository {

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long): AppDetail = withContext(Dispatchers.Default) {
        val repo = api.repoById(appId)
        val release = api.latestRelease(repo.slug)
        val detail = api.appDetail(repo, release)
        registerTracking(repo.slug, detail.app)
        detail.withInstalledState()
    }

    override suspend fun appDetailBySlug(slug: String): AppDetail = withContext(Dispatchers.Default) {
        val repo = api.repoBySlug(slug.normalizedSlug())
        val release = api.latestRelease(repo.slug)
        val detail = api.appDetail(repo, release)
        registerTracking(repo.slug, detail.app)
        detail.withInstalledState()
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val tracked = trackedRepos.tracked(AppSource.GITHUB)
        if (tracked.isEmpty()) return@withContext emptyList()
        api.checkUpdates(installedPackages.installed(), tracked)
    }

    override suspend fun checkUpdate(
        packageName: String,
        versionCode: Long,
        versionName: String,
    ): SourceUpdateCheck = withContext(Dispatchers.Default) {
        val tracked = trackedRepos.find(packageName, AppSource.GITHUB) ?: return@withContext SourceUpdateCheck.NotFound
        api.checkUpdate(tracked, localPackage(packageName, versionCode, versionName))
    }

    override suspend fun track(slug: String, app: MarketAppInfo) = withContext(Dispatchers.Default) {
        trackedRepos.track(
            TrackedRepo(
                packageName = app.packageName,
                slug = slug.normalizedSlug(),
                source = AppSource.GITHUB,
                appId = app.appId,
                displayName = app.displayName,
                icon = app.icon,
            )
        )
    }

    /**
     * 解析出包名后自动登记跟踪，让后续更新检查能覆盖到它。
     *
     * GitHub 没有「按包名反查仓库」的接口，这条记录就是唯一的反向索引；
     * 用户在详情页看到过哪个开源应用，就把它纳入更新检查范围。
     */
    private suspend fun registerTracking(slug: String, app: MarketAppInfo) {
        if (app.packageName.isBlank() || slug.isBlank()) return
        runCatching { track(slug, app) }
    }

    private suspend fun AppDetail.withInstalledState(): AppDetail {
        if (app.packageName.isBlank()) return this
        val installed = runCatching { installedPackages.installedPackage(app.packageName) }.getOrNull() ?: return this
        return copy(app = app.mergedWithInstalled(installed))
    }
}
