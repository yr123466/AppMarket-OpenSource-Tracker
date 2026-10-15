package top.app.market.data.remote.fdroid

import kotlinx.serialization.Serializable

/**
 * `https://f-droid.org/api/v1/packages/{包名}` 的响应。
 *
 * 这是 F-Droid 相对其他源最大的优势：只要知道包名就能拿到建议版本与全部历史版本，
 * 不需要用户先「添加」任何东西，也不受 HTML 改版影响。
 */
@Serializable
internal data class FdroidPackageVersions(
    val packageName: String = "",
    val suggestedVersionCode: Long = 0L,
    val packages: List<FdroidVersion> = emptyList(),
)

@Serializable
internal data class FdroidVersion(
    val versionName: String = "",
    val versionCode: Long = 0L,
)

/** 详情页解析结果。 */
internal data class FdroidPackageDetail(
    val packageName: String,
    val displayName: String,
    val summary: String,
    val description: String,
    val iconUrl: String,
    val license: String,
    val website: String,
    val sourceCode: String,
    val issueTracker: String,
    val latestVersionName: String,
    val latestVersionCode: Long,
    val latestApkUrl: String,
    val latestApkSize: Long,
    val changeLog: String,
    val screenshots: List<String>,
)
