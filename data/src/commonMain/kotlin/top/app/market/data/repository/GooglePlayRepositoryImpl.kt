package top.app.market.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.app.market.data.remote.play.GooglePlayApi
import top.app.market.domain.model.market.AppDetail
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.SourceUpdateCheck
import top.app.market.domain.repository.GooglePlayRepository
import top.app.market.domain.repository.InstalledPackagesRepository

internal class GooglePlayRepositoryImpl(
    private val api: GooglePlayApi,
    private val installedPackages: InstalledPackagesRepository,
) : GooglePlayRepository {

    override suspend fun appDetail(packageName: String): AppDetail = withContext(Dispatchers.Default) {
        val detail = api.appDetail(packageName)
        val installed = runCatching { installedPackages.installedPackage(packageName) }.getOrNull()
        if (installed == null) detail else detail.copy(app = detail.app.mergedWithInstalled(installed))
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        api.checkUpdates(installedPackages.installed())
    }

    override suspend fun checkUpdate(
        packageName: String,
        versionCode: Long,
        versionName: String,
    ): SourceUpdateCheck = withContext(Dispatchers.Default) {
        api.checkUpdate(packageName, versionName)
    }
}
