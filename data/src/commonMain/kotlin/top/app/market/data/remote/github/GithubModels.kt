package top.app.market.data.remote.github

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GithubSearchResponse(
    @SerialName("total_count") val totalCount: Int = 0,
    val items: List<GithubRepo> = emptyList(),
)

@Serializable
internal data class GithubRepo(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("full_name") val fullName: String = "",
    val description: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("stargazers_count") val stargazersCount: Int = 0,
    @SerialName("forks_count") val forksCount: Int = 0,
    val language: String? = null,
    val topics: List<String> = emptyList(),
    val owner: GithubOwner? = null,
    val license: GithubLicense? = null,
    @SerialName("pushed_at") val pushedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("default_branch") val defaultBranch: String? = null,
    val archived: Boolean = false,
    val fork: Boolean = false,
) {
    val slug: String get() = fullName.ifBlank { name }
}

@Serializable
internal data class GithubOwner(
    val login: String = "",
    @SerialName("avatar_url") val avatarUrl: String = "",
)

@Serializable
internal data class GithubLicense(
    @SerialName("spdx_id") val spdxId: String? = null,
    val name: String? = null,
)

@Serializable
internal data class GithubRelease(
    val id: Long = 0L,
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
internal data class GithubAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String = "",
    @SerialName("content_type") val contentType: String? = null,
    @SerialName("download_count") val downloadCount: Long = 0L,
)

@Serializable
internal data class GithubTag(
    val name: String = "",
)

/** GitHub 错误体：`{"message": "...", "documentation_url": "..."}`。 */
@Serializable
internal data class GithubError(
    val message: String = "",
)
