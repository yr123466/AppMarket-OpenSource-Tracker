package top.app.market.data.remote.opensource

/**
 * GitHub / GitLab 发布资产的公共解析。
 *
 * 开源 Android 项目（F-Droid、IzzyOnDroid 系发布脚本）普遍把 APK 命名为
 * `{applicationId}_{versionCode}.apk`，例如 `org.fdroid.fdroid_1018050.apk`。
 * 有了这个约定，无需下载并解析 APK 就能同时拿到**包名**与**版本号**，
 * 这正是开源源能够参与「本地应用检测 + 版本号对比」的关键。
 */
internal data class ApkAssetIdentity(
    val packageName: String,
    val versionCode: Long,
)

/** `com.example.app_1234.apk`；包名至少两段，版本号 1~12 位数字。 */
private val ApkAssetPattern =
    Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)_(\d{1,12})\.apk$""")

/** 版本号缺失时的退化形式：`com.example.app.apk`。 */
private val ApkAssetWithoutVersionPattern =
    Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)\.apk$""")

private val AbiPreference = listOf("universal", "arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/** 签名与增量补丁文件不是可安装包，必须排除。 */
private val NonApkSuffixes = listOf(".asc", ".idsig", ".sha256", ".sha512", ".sig", ".json", ".txt", ".md5")

internal fun parseApkAssetName(name: String): ApkAssetIdentity? {
    ApkAssetPattern.matchEntire(name)?.let { match ->
        val versionCode = match.groupValues[2].toLongOrNull() ?: return null
        if (versionCode <= 0L) return null
        return ApkAssetIdentity(match.groupValues[1], versionCode)
    }
    val packageOnly = ApkAssetWithoutVersionPattern.matchEntire(name)?.groupValues?.get(1) ?: return null
    return ApkAssetIdentity(packageOnly, 0L)
}

/** 该资产是否是可安装的 APK（排除签名、idsig、校验和等旁挂文件）。 */
internal fun isInstallableApk(name: String): Boolean {
    val lower = name.lowercase()
    if (NonApkSuffixes.any { lower.endsWith(it) }) return false
    return lower.endsWith(".apk")
}

/**
 * 从一组发布资产里挑出最合适的一个 APK。
 *
 * 优先「通用包」，其次按 ABI 常见度排序，最后取体积最大的（多 ABI 合一包通常最大）。
 * 完全不含 ABI 信息的名字排在最前，因为它对任何设备都能装。
 */
internal fun <T> pickBestApk(
    assets: List<T>,
    nameOf: (T) -> String,
    sizeOf: (T) -> Long,
): T? {
    val candidates = assets.filter { isInstallableApk(nameOf(it)) }
    if (candidates.isEmpty()) return null
    return candidates.sortedWith(
        compareByDescending<T> { abiRank(nameOf(it)) }
            .thenByDescending { sizeOf(it) }
    ).first()
}

/** 越小越优先；无 ABI 标记视为通用包，rank 最大。 */
private fun abiRank(name: String): Int {
    val lower = name.lowercase()
    val index = AbiPreference.indexOfFirst { lower.contains(it) }
    if (index >= 0) return AbiPreference.size - index
    return AbiPreference.size + 1
}

/**
 * 语义化版本比较，用于资产名没有版本号、只能拿 tag 比较的场景。
 *
 * `v1.10.0` > `1.9.2`；预发布后缀（`-rc1`/`-beta`）低于同号正式版。
 * 返回负数表示 [a] 更旧，0 表示相同，正数表示 [a] 更新。
 */
internal fun compareVersionNames(a: String, b: String): Int {
    val left = parseVersion(a)
    val right = parseVersion(b)
    val maxSegments = maxOf(left.numbers.size, right.numbers.size)
    for (i in 0 until maxSegments) {
        val l = left.numbers.getOrElse(i) { 0 }
        val r = right.numbers.getOrElse(i) { 0 }
        if (l != r) return l.compareTo(r)
    }
    // 正式版 > 预发布版
    if (left.prerelease == right.prerelease) return 0
    if (left.prerelease.isEmpty()) return 1
    if (right.prerelease.isEmpty()) return -1
    return left.prerelease.compareTo(right.prerelease)
}

private data class ParsedVersion(val numbers: List<Long>, val prerelease: String)

private val PrereleaseMarker =
    Regex("""[-\s]*(alpha|beta|rc|dev|snapshot|pre|preview|nightly)""", RegexOption.IGNORE_CASE)

private fun parseVersion(raw: String): ParsedVersion {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V")
    if (trimmed.isEmpty()) return ParsedVersion(emptyList(), "")
    val marker = PrereleaseMarker.find(trimmed)
    val numericPart = if (marker != null) trimmed.substring(0, marker.range.first) else trimmed
    val prerelease = if (marker != null) trimmed.substring(marker.range.first).trimStart('-', ' ', '.') else ""
    val numbers = Regex("""\d+""").findAll(numericPart).mapNotNull { it.value.toLongOrNull() }.toList()
    return ParsedVersion(numbers, prerelease)
}

/** 把 tag 归一化成展示用版本名：去掉前导 `v`，其余原样保留。 */
internal fun normalizeReleaseVersion(tag: String): String =
    tag.trim().removePrefix("v").removePrefix("V").ifBlank { tag.trim() }
