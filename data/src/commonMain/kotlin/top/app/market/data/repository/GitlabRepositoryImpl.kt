package top.app.market.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.app.market.data.remote.gitlab.GitlabApi
import top.app.market.data.remote.gitlab.normalizedGitlabSlug
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.model.tracked.TrackedRepo
import top.app.market.domain.repository.GitlabRepository
import top.app.market.domain.repository.InstalledPackagesRepository
import top.app.market.domain.repository.TrackedRepoRepository

internal class GitlabRepositoryImpl(
    private val api: GitlabApi,
    private val installedPackages: InstalledPackagesRepository,
    private val trackedRepos: TrackedRepoRepository,
) : GitlabRepository {

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long): AppDetail = withContext(Dispatchers.Default) {
        val project = api.projectById(appId)
        val detail = api.appDetail(project, api.latestRelease(project.slug))
        registerTracking(project.slug, detail.app)
        detail.withInstalledState()
    }

    override suspend fun appDetailBySlug(slug: String): AppDetail = withContext(Dispatchers.Default) {
        val project = api.projectBySlug(slug.normalizedGitlabSlug())
        val detail = api.appDetail(project, api.latestRelease(project.slug))
        registerTracking(project.slug, detail.app)
        detail.withInstalledState()
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val tracked = trackedRepos.tracked(AppSource.GITLAB)
        if (tracked.isEmpty()) return@withContext emptyList()
        api.checkUpdates(installedPackages.installed(), tracked)
    }

    override suspend fun checkUpdate(
        packageName: String,
        versionCode: Long,
        versionName: String,
    ): SourceUpdateCheck = withContext(Dispatchers.Default) {
        val tracked = trackedRepos.find(packageName, AppSource.GITLAB) ?: return@withContext SourceUpdateCheck.NotFound
        api.checkUpdate(tracked, localPackage(packageName, versionCode, versionName))
    }

    override suspend fun track(slug: String, app: MarketAppInfo) = withContext(Dispatchers.Default) {
        trackedRepos.track(
            TrackedRepo(
                packageName = app.packageName,
                slug = slug.normalizedGitlabSlug(),
                source = AppSource.GITLAB,
                appId = app.appId,
                displayName = app.displayName,
                icon = app.icon,
            )
        )
    }

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
