package top.app.market.data.repository

import top.app.market.domain.model.installed.InstalledPackage
import top.app.market.domain.model.market.MarketAppInfo

/**
 * 把本地已安装信息合并进远端条目。
 *
 * 各来源的仓库实现都要做这一步，集中在这里避免每加一个源就抄一遍字段搬运。
 */
internal fun MarketAppInfo.mergedWithInstalled(installed: InstalledPackage): MarketAppInfo = copy(
    isSystemApp = installed.isSystemApp,
    installedVersionName = installed.versionName,
    installedVersionCode = installed.versionCode,
    installedOldApkHash = installed.oldApkHash,
    installedBaseApkPath = installed.baseApkPath,
    installedSplits = installed.splits,
)

/**
 * 把「包名 + 版本」包装成更新检查所需的已安装包描述。
 *
 * 「按包名检测」入口只有这三个字段可用（拿不到签名、APK 路径等），
 * 但对版本号比较来说已经足够。
 */
internal fun localPackage(packageName: String, versionCode: Long, versionName: String): InstalledPackage =
    InstalledPackage(
        packageName = packageName,
        versionCode = versionCode,
        isSystemApp = false,
        versionName = versionName,
    )
