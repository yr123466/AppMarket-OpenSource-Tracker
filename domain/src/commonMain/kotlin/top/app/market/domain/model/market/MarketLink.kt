package top.app.market.domain.model.market

/**
 * 从商店链接解析出的应用定位。[source] 为 null 表示链接不指向任何已接入的商店（如 Google Play、酷安），
 * 由调用方决定默认来源；[storeAppId] 仅在链接只带站内 id（TapTap、华为 `C` 开头的应用 id）时有值，
 * 此时 [packageName] 为空。
 *
 * 开源分发来源（GitHub / GitLab）用 [repoSlug] 定位，此时 [packageName] 同样为空
 * ——包名要等拉取最新发布、从 APK 资产名里还原出来。
 */
data class MarketLink(
    val packageName: String,
    val source: AppSource?,
    val storeAppId: Long = 0L,
    /** GitHub 的 `owner/repo` 或 GitLab 的 `namespace/project`；其他来源为空。 */
    val repoSlug: String = "",
) {
    /** AppMarket 统一格式，可被本应用的链接解析与剪贴板识别还原。 */
    fun toUnifiedUri(): String = buildString {
        append(UNIFIED_SCHEME).append("://details")
        val params = buildList {
            if (packageName.isNotBlank()) add("id=$packageName")
            source?.let { add("source=${it.token}") }
            if (storeAppId > 0L) add("appId=$storeAppId")
            if (repoSlug.isNotBlank()) add("repo=${repoSlug.encodeRepoParam()}")
        }
        if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
    }

    companion object {
        /** 与华为应用市场的 `appmarket://details?id=` 兼容：不带 `source` 时按华为处理。 */
        const val UNIFIED_SCHEME = "appmarket"
    }
}

/** 仓库 slug 里的 `/` 要转义，否则统一格式的查询串会被拆成两段。 */
private fun String.encodeRepoParam(): String = replace("/", "%2F")

/**
 * 统一的商店链接解析：支持 AppMarket 统一格式、各家商店的 scheme 与网页链接，
 * 也能从一段分享文字中找出第一个可识别的链接。
 */
object MarketLinkParser {

    /** 从任意文本（如剪贴板、分享文案）中找出第一个可识别的商店链接。 */
    fun parseText(text: String): MarketLink? {
        if (text.isBlank()) return null
        return UriInText.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '!', '?') }
            .firstNotNullOfOrNull(::parse)
    }

    /**
     * 找出文本中需要联网展开的短链接。只认已知的短链域名，避免去访问剪贴板里的任意网址。
     * 展开后的链接再交给 [parse]。
     */
    fun findShortLink(text: String): String? {
        if (text.isBlank()) return null
        return UriInText.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', '!', '?') }
            .firstOrNull(::isShortLink)
    }

    private fun isShortLink(uri: String): Boolean {
        val parsed = ParsedUri.of(uri) ?: return false
        return (parsed.scheme == "http" || parsed.scheme == "https") &&
                parsed.host.matches(*ShortLinkHosts) && parsed.pathSegments.isNotEmpty()
    }

    /** 解析单个 URI。 */
    fun parse(uri: String): MarketLink? {
        val parsed = ParsedUri.of(uri.trim()) ?: return null
        // 开源分发来源用「仓库」或「包名页」定位，不适用商店的包名查询串规则，先单独处理
        parsed.openSourceLink()?.let { return it }
        val source = parsed.source() ?: if (parsed.isKnownStoreWithoutSource()) null else return null
        val packageName = parsed.packageName()
        if (packageName != null) {
            return MarketLink(packageName, source, parsed.tapTapAppId().takeIf { source == AppSource.TAPTAP } ?: 0L)
        }
        // TapTap、华为的分享链接只带站内 id，详情页可按 id 加载
        val storeAppId = when (source) {
            AppSource.TAPTAP -> parsed.tapTapAppId()
            AppSource.HUAWEI -> parsed.huaweiAppId()
            else -> null
        } ?: return null
        return MarketLink(packageName = "", source = source, storeAppId = storeAppId)
    }

    /**
     * GitHub / GitLab / F-Droid 链接。
     *
     * 前两者给出 `owner/repo`、`namespace/project`（GitLab 支持子组，故不能只取两段）；
     * F-Droid 给出包名。三者都无法从链接直接拿到版本，需要随后拉取发布信息。
     */
    private fun ParsedUri.openSourceLink(): MarketLink? {
        if (scheme != "http" && scheme != "https") {
            // 统一格式自带的 repo 参数
            if (scheme == MarketLink.UNIFIED_SCHEME) {
                val slug = query("repo")?.trim('/').orEmpty()
                val fromToken = query("source")?.let(AppSource::fromToken)
                if (slug.isNotBlank() && fromToken in AppSource.RepoSlugSources) {
                    return MarketLink("", fromToken, repoSlug = slug)
                }
            }
            return null
        }
        return when {
            host.matches("github.com") -> pathSegments.toRepoSlug(GithubReservedSegments)
                ?.let { MarketLink("", AppSource.GITHUB, repoSlug = it) }

            host.matches("gitlab.com") -> pathSegments.toGitlabSlug()
                ?.let { MarketLink("", AppSource.GITLAB, repoSlug = it) }

            host.matches("f-droid.org", "www.f-droid.org") -> pathSegments.toFdroidPackage()
                ?.let { MarketLink(it, AppSource.FDROID) }

            else -> null
        }
    }

    /** 取前两段作为 `owner/repo`，并排除站点自身的功能路径。 */
    private fun List<String>.toRepoSlug(reserved: Set<String>): String? {
        if (size < 2) return null
        val owner = this[0]
        val repo = this[1].removeSuffix(".git")
        if (owner.lowercase() in reserved) return null
        if (repo.lowercase() in ReservedRepoNames) return null
        if (repo.isBlank()) return null
        return "$owner/$repo"
    }

    /**
     * GitLab 支持子组，`group/subgroup/project` 都是 slug 的一部分；
     * 因此截断到第一个 `-` 段（`/-/releases`、`/-/tree/main` 等页面内锚点）为止。
     */
    private fun List<String>.toGitlabSlug(): String? {
        val head = takeWhile { it != "-" }.filter { it.isNotBlank() }
        if (head.size < 2) return null
        if (head.first().lowercase() in GitlabReservedSegments) return null
        return head.joinToString("/").removeSuffix(".git")
    }

    /** `/{lang}/packages/{包名}` 或 `/packages/{包名}`。 */
    private fun List<String>.toFdroidPackage(): String? {
        val index = indexOfFirst { it.equals("packages", ignoreCase = true) }
        if (index < 0) return null
        return getOrNull(index + 1)?.asPackageName()
    }

    private fun ParsedUri.source(): AppSource? {
        when (scheme) {
            "appmarket" -> return query("source")?.let(AppSource::fromToken) ?: AppSource.HUAWEI
            "mimarket" -> return AppSource.XIAOMI
            "hiapp", "hwmarket" -> return AppSource.HUAWEI
            "vivomarket" -> return AppSource.VIVO
            "oppomarket", "heytapmarket", "oaps" -> return AppSource.OPPO
            "honormarket", "hnappmarket" -> return AppSource.HONOR
            "samsungapps" -> return AppSource.SAMSUNG
            "taptap" -> return AppSource.TAPTAP
            "wandoujia" -> return AppSource.WANDOUJIA
        }
        if (scheme != "http" && scheme != "https") return null
        return when {
            host.matches("app.mi.com", "app.xiaomi.com") -> AppSource.XIAOMI
            host.matches("appgallery.huawei.com", "appgallery.cloud.huawei.com", "appstore.huawei.com") ->
                AppSource.HUAWEI
            host.matches("vivo.com.cn", "vivo.com") -> AppSource.VIVO
            host.matches("heytap.com", "oppomobile.com", "heytapmobi.com") -> AppSource.OPPO
            host.matches("hihonor.com", "honor.com") -> AppSource.HONOR
            host.matches("galaxystore.samsung.com", "apps.samsung.com") -> AppSource.SAMSUNG
            host.matches("taptap.cn", "taptap.com", "taptap.io") -> AppSource.TAPTAP
            host.matches("wandoujia.com") -> AppSource.WANDOUJIA
            else -> null
        }
    }

    /** 不对应已接入商店、但链接里带包名的常见来源（通用 market://、Google Play、酷安）。 */
    private fun ParsedUri.isKnownStoreWithoutSource(): Boolean = when (scheme) {
        "market" -> true
        "http", "https" -> host.matches("play.google.com", "market.android.com", "coolapk.com")
        else -> false
    }

    private fun ParsedUri.packageName(): String? {
        PackageKeys.firstNotNullOfOrNull { key -> query(key)?.asPackageName() }?.let { return it }
        // 包名放在路径里的链接：三星 ProductDetail/<pkg>、豌豆荚 apps/<pkg>、酷安 apk/<pkg> 等
        return pathSegments.firstNotNullOfOrNull { it.asPackageName() }
    }

    private fun ParsedUri.tapTapAppId(): Long? {
        listOf("app_id", "appId", "id").firstNotNullOfOrNull { query(it)?.toLongOrNull() }
            ?.takeIf { it > 0L }
            ?.let { return it }
        val index = pathSegments.indexOfFirst { it.equals("app", ignoreCase = true) }
        return pathSegments.getOrNull(index + 1)?.toLongOrNull()?.takeIf { index >= 0 && it > 0L }
    }

    /** 华为应用 id 形如 `C100404489`；返回去掉 `C` 的数字部分。路径里必须带 `C` 前缀，避免把任意数字当成 id。 */
    private fun ParsedUri.huaweiAppId(): Long? {
        listOf("appId", "appid", "app_id", "id").firstNotNullOfOrNull { query(it)?.asHuaweiAppId(prefixRequired = false) }
            ?.let { return it }
        return pathSegments.firstNotNullOfOrNull { it.asHuaweiAppId(prefixRequired = true) }
    }

    private fun String.asHuaweiAppId(prefixRequired: Boolean): Long? {
        val value = trim()
        val hasPrefix = value.startsWith("C") || value.startsWith("c")
        if (prefixRequired && !hasPrefix) return null
        return value.removePrefix("C").removePrefix("c").toLongOrNull()?.takeIf { it > 0L }
    }

    private fun String.matches(vararg domains: String): Boolean =
        domains.any { this == it || endsWith(".$it") }

    private val ShortLinkHosts = arrayOf("url.cloud.huawei.com")

    /** github.com 的第一段若是站点功能页，就不是 `owner/repo`。 */
    private val GithubReservedSegments = setOf(
        "about", "apps", "collections", "contact", "customer-stories", "enterprise", "events",
        "explore", "features", "issues", "join", "login", "logout", "marketplace", "new",
        "notifications", "orgs", "pricing", "pulls", "search", "security", "settings", "signup",
        "site", "sponsors", "topics", "trending", "users", "watching", "codespaces", "dashboard",
    )

    /** GitHub 的第二段是仓库名；这些是仓库页面内的固定子路径，不代表仓库名。 */
    private val ReservedRepoNames = setOf("releases", "tags", "tree", "blob", "commits", "actions", "wiki")

    /** gitlab.com 的第一段若是站点功能页，就不是 `namespace/project`。 */
    private val GitlabReservedSegments = setOf(
        "admin", "api", "dashboard", "explore", "groups", "help", "profile", "projects",
        "public", "search", "snippets", "uploads", "users", "assets", "favicon.ico",
    )

    private val PackageKeys = listOf(
        "id", "packageName", "packagename", "package_name", "package", "pkg", "pkgName", "pkgname",
        "pName", "pname", "appPackage", "apppkg",
    )

    private val UriInText = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>，。、；！？）)\]】」』]+""")
}

/** 合法的 Android 包名：至少两段，每段以字母开头；排除 `detail.html` 这类文件名。 */
internal fun String.asPackageName(): String? {
    val value = trim()
    if (value.length !in 3..255) return null
    val parts = value.split('.')
    if (parts.size < 2) return null
    if (parts.any { part -> part.isEmpty() || !part.first().isLetter() || !part.all { it.isLetterOrDigit() || it == '_' } }) {
        return null
    }
    if (parts.last().lowercase() in FileExtensions) return null
    return value
}

private val FileExtensions = setOf("html", "htm", "php", "jsp", "asp", "aspx", "as", "do", "apk", "json", "shtml")

/** 极简 URI 拆解：只取本解析器需要的 scheme / host / 路径段 / 查询参数（含 # 后的参数）。 */
private class ParsedUri(
    val scheme: String,
    val host: String,
    val pathSegments: List<String>,
    private val params: Map<String, String>,
) {
    fun query(key: String): String? =
        params[key] ?: params.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    companion object {
        fun of(raw: String): ParsedUri? {
            val schemeEnd = raw.indexOf(':')
            if (schemeEnd <= 0) return null
            val scheme = raw.substring(0, schemeEnd).lowercase()
            if (!scheme.first().isLetter()) return null
            var rest = raw.substring(schemeEnd + 1).removePrefix("//")
            val fragment = rest.substringAfter('#', "")
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', "")
            val authorityAndPath = rest.substringBefore('?')
            val host = authorityAndPath.substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
            val path = authorityAndPath.substringAfter('/', "")
            // 单页应用常把参数放在 # 之后，如 #/details?appId=、#page=detail&id=
            val fragmentQuery = fragment.substringAfter('?', fragment)
            val params = LinkedHashMap<String, String>()
            (query.split('&') + fragmentQuery.split('&')).forEach { pair ->
                val key = pair.substringBefore('=').trim()
                if (key.isEmpty() || '=' !in pair) return@forEach
                params.putIfAbsent(percentDecode(key), percentDecode(pair.substringAfter('=')))
            }
            val segments = (path.split('/') + fragment.substringBefore('?').split('/'))
                .map(::percentDecode)
                .filter(String::isNotBlank)
            return ParsedUri(scheme, host, segments, params)
        }

        private fun percentDecode(value: String): String {
            if ('%' !in value && '+' !in value) return value
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    val byte = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (byte != null) {
                        bytes += byte.toByte()
                        i += 3
                        continue
                    }
                }
                if (c == '+') bytes += ' '.code.toByte()
                else c.toString().encodeToByteArray().forEach { bytes += it }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
