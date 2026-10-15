package top.app.market.data.remote.gitlab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GitlabProject(
    val id: Long = 0L,
    val name: String = "",
    val path: String = "",
    @SerialName("path_with_namespace") val pathWithNamespace: String = "",
    @SerialName("name_with_namespace") val nameWithNamespace: String = "",
    val description: String? = null,
    @SerialName("web_url") val webUrl: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("star_count") val starCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("last_activity_at") val lastActivityAt: String? = null,
    val topics: List<String> = emptyList(),
    val archived: Boolean = false,
    val namespace: GitlabNamespace? = null,
    val license: GitlabLicense? = null,
) {
    val slug: String get() = pathWithNamespace.ifBlank { path }
}

@Serializable
internal data class GitlabNamespace(
    val id: Long = 0L,
    val name: String = "",
    val path: String = "",
    @SerialName("full_path") val fullPath: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
internal data class GitlabLicense(
    val key: String? = null,
    val name: String? = null,
    val nickname: String? = null,
)

@Serializable
internal data class GitlabRelease(
    val name: String = "",
    @SerialName("tag_name") val tagName: String = "",
    val description: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("released_at") val releasedAt: String? = null,
    @SerialName("upcoming_release") val upcomingRelease: Boolean = false,
    val assets: GitlabAssets? = null,
)

@Serializable
internal data class GitlabAssets(
    val count: Int = 0,
    val links: List<GitlabAssetLink> = emptyList(),
)

@Serializable
internal data class GitlabAssetLink(
    val id: Long = 0L,
    val name: String = "",
    val url: String = "",
    @SerialName("direct_asset_url") val directAssetUrl: String? = null,
    @SerialName("link_type") val linkType: String? = null,
) {
    /** 直链优先用 `direct_asset_url`，回退到 `url`。 */
    val downloadUrl: String get() = directAssetUrl?.takeIf { it.isNotBlank() } ?: url
}

@Serializable
internal data class GitlabTag(
    val name: String = "",
)

/** GitLab 错误体：`{"message": "404 Project Not Found"}` 或 `{"error": "..."}`。 */
@Serializable
internal data class GitlabError(
    val message: String = "",
    val error: String = "",
)
