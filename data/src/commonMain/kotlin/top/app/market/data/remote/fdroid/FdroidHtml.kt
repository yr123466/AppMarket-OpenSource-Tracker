package top.app.market.data.remote.fdroid

/**
 * F-Droid 网页的极简 HTML 解析。
 *
 * F-Droid 没有搜索 API（`search.f-droid.org` 是服务端渲染的 HTML），详情页也只有网页，
 * 所以这里用正则提取固定 class 的节点。选择器都锚定在官方模板的稳定 class 上，
 * 并且每个字段都允许缺失 —— 页面改版时退化为字段为空，而不是整次请求失败。
 */
internal object FdroidHtml {

    private val SearchItemPattern =
        Regex("""<a class="package-header" href="([^"]+)">([\s\S]*?)</a>""")
    private val PackageNameInHref = Regex("""packages/([^"?#/]+)""")
    private val IconPattern = Regex("""<img class="package-icon" src="([^"]*)"""")
    private val TitlePattern = Regex("""<h[34] class="package-name">([\s\S]*?)</h[34]>""")
    private val SummaryPattern = Regex("""<div class="package-summary">([\s\S]*?)</div>""")
    private val InlineSummaryPattern = Regex("""<span class="package-summary">([\s\S]*?)</span>""")
    private val InlineLicensePattern = Regex("""<span class="package-license">([\s\S]*?)</span>""")
    private val DescriptionPattern = Regex("""<div class="package-description"[^>]*>([\s\S]*?)</div>""")
    private val LicenseLinkPattern = Regex("""<li class="package-link" id="license">([\s\S]*?)</li>""")
    private val LinkByIdPattern = { id: String ->
        Regex("""<li class="package-link" id="$id">[\s\S]*?<a href="([^"]*)"""")
    }

    private val LatestVersionBlockPattern =
        Regex("""<li class="package-version"\s+id="latest">([\s\S]*?)</li>""")
    private val AnyVersionBlockPattern = Regex("""<li class="package-version"\s*>([\s\S]*?)</li>""")
    private val VersionHeaderPattern = Regex("""<b>Version\s*([^<]*)</b>\s*\((\d{1,12})\)""")
    private val ApkHrefPattern = Regex("""<a href="([^"]+\.apk)">""")
    private val ApkSizePattern = Regex("""</b>\s*([0-9.]+\s*[KMGT]?i?B)""")
    private val ChangeLogPattern = Regex("""<div class="package-whats-new">[\s\S]*?<div dir="auto">([\s\S]*?)</div>""")
    private val ScreenshotPattern = Regex("""<li class="js_slide screenshot"><img src="([^"]*)"""")
    private val TagPattern = Regex("<[^>]+>")

    /** 解析 `search.f-droid.org` 的结果页。 */
    fun parseSearch(html: String): List<FdroidSearchItem> =
        SearchItemPattern.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1]
            val packageName = PackageNameInHref.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val block = match.groupValues[2]
            FdroidSearchItem(
                packageName = packageName,
                displayName = TitlePattern.find(block)?.groupValues?.get(1)?.cleanText().orEmpty()
                    .ifBlank { packageName },
                summary = InlineSummaryPattern.find(block)?.groupValues?.get(1)?.cleanText().orEmpty(),
                iconUrl = IconPattern.find(block)?.groupValues?.get(1).orEmpty(),
                license = InlineLicensePattern.find(block)?.groupValues?.get(1)?.cleanText().orEmpty(),
            )
        }.distinctBy { it.packageName }.toList()

    /** 解析 `f-droid.org/{lang}/packages/{包名}/` 详情页。 */
    fun parseDetail(html: String, packageName: String): FdroidPackageDetail {
        val versionBlock = LatestVersionBlockPattern.find(html)?.groupValues?.get(1)
            ?: AnyVersionBlockPattern.find(html)?.groupValues?.get(1)
            ?: ""
        val header = VersionHeaderPattern.find(versionBlock)
        val sizeText = ApkSizePattern.find(versionBlock)?.groupValues?.get(1).orEmpty()
        return FdroidPackageDetail(
            packageName = packageName,
            displayName = TitlePattern.find(html)?.groupValues?.get(1)?.cleanText().orEmpty().ifBlank { packageName },
            summary = (SummaryPattern.find(html) ?: InlineSummaryPattern.find(html))
                ?.groupValues?.get(1)?.cleanText().orEmpty(),
            description = DescriptionPattern.find(html)?.groupValues?.get(1)?.cleanText().orEmpty(),
            iconUrl = IconPattern.find(html)?.groupValues?.get(1).orEmpty(),
            license = LicenseLinkPattern.find(html)?.groupValues?.get(1)?.cleanText()
                ?.removePrefix("License:")?.trim().orEmpty(),
            website = LinkByIdPattern("website").find(html)?.groupValues?.get(1).orEmpty(),
            sourceCode = LinkByIdPattern("source_code").find(html)?.groupValues?.get(1).orEmpty(),
            issueTracker = LinkByIdPattern("issue_tracker").find(html)?.groupValues?.get(1).orEmpty(),
            latestVersionName = header?.groupValues?.get(1)?.cleanText().orEmpty(),
            latestVersionCode = header?.groupValues?.get(2)?.toLongOrNull() ?: 0L,
            latestApkUrl = ApkHrefPattern.find(versionBlock)?.groupValues?.get(1).orEmpty(),
            latestApkSize = parseDisplaySize(sizeText),
            changeLog = ChangeLogPattern.find(html)?.groupValues?.get(1)?.cleanText().orEmpty(),
            screenshots = ScreenshotPattern.findAll(html).map { it.groupValues[1] }.toList(),
        )
    }

    /** 去掉标签、还原实体、压掉多余空行。 */
    internal fun String.cleanText(): String = decodeEntities()
        .let { TagPattern.replace(it.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n"), "") }
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()

    private fun String.decodeEntities(): String {
        if ('&' !in this) return this
        var result = this
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&middot;", "·")
            .replace("&reg;", "®")
            .replace("&copy;", "©")
        // 剩余的数字实体 &#123; / &#x1F600;
        result = NumericEntity.replace(result) { match ->
            val value = match.groupValues[1]
            val code = if (value.startsWith("x") || value.startsWith("X")) {
                value.drop(1).toIntOrNull(16)
            } else {
                value.toIntOrNull()
            }
            code?.takeIf { it in 1..0x10FFFF }?.let(::codePointToString) ?: match.value
        }
        return result
    }

    /** 跨平台地把码点还原成字符串；`Character.toChars` 只在 JVM 上有，common 代码不能用。 */
    private fun codePointToString(code: Int): String =
        if (code <= 0xFFFF) {
            code.toChar().toString()
        } else {
            val offset = code - 0x10000
            charArrayOf(
                (0xD800 + (offset shr 10)).toChar(),
                (0xDC00 + (offset and 0x3FF)).toChar(),
            ).concatToString()
        }

    private val NumericEntity = Regex("""&#(x?[0-9A-Fa-f]{1,6});""")
}

internal data class FdroidSearchItem(
    val packageName: String,
    val displayName: String,
    val summary: String,
    val iconUrl: String,
    val license: String,
)

/** `11 MiB` / `1.2 GiB` / `512 KiB` → 字节数。 */
internal fun parseDisplaySize(raw: String): Long {
    val clean = raw.trim().replace(" ", "")
    if (clean.isEmpty()) return 0L
    val unit = clean.takeLastWhile { it.isLetter() }.uppercase()
    val number = clean.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: return 0L
    val factor = when (unit) {
        "B" -> 1L
        "KB", "KIB" -> 1024L
        "MB", "MIB" -> 1024L * 1024
        "GB", "GIB" -> 1024L * 1024 * 1024
        "TB", "TIB" -> 1024L * 1024 * 1024 * 1024
        else -> return 0L
    }
    return (number * factor).toLong()
}
