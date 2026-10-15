package top.app.market.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.app.market.data.remote.fdroid.FdroidApi
import top.app.market.domain.model.download.DownloadMeta
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SearchPage
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.repository.FdroidRepository
import top.app.market.domain.repository.InstalledPackagesRepository

internal class FdroidRepositoryImpl(
    private val api: FdroidApi,
    private val installedPackages: InstalledPackagesRepository,
) : FdroidRepository {

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail = withContext(Dispatchers.Default) {
        val detail = api.appDetail(packageName)
        val installed = runCatching { installedPackages.installedPackage(packageName) }.getOrNull()
        if (installed == null) detail else detail.copy(app = detail.app.mergedWithInstalled(installed))
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    /**
     * F-Droid 是唯一「零配置」的更新源：直接把全部已安装包名逐个反查官方仓库，
     * 不需要用户事先添加任何东西，也不需要跟踪表。
     */
    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        api.checkUpdates(installedPackages.installed())
    }

    override suspend fun checkUpdate(
        packageName: String,
        versionCode: Long,
        versionName: String,
    ): SourceUpdateCheck = withContext(Dispatchers.Default) {
        api.checkUpdate(packageName, versionCode, versionName)
    }
}
