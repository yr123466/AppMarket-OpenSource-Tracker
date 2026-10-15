package top.app.market.data.local.preferences

import top.app.market.data.local.StringPreferenceKey

/**
 * 开源分发来源的偏好键。
 *
 * GitHub 匿名访问限流为 60 次/小时/IP，跟踪仓库一多必然打满；GitLab 同理。
 * 两个可选 token 让用户把上限提到 5000 次/小时。为空时全部走匿名请求。
 */
object OpenSourcePreferenceKeys {
    private const val NS = "open_source"

    val GithubToken = StringPreferenceKey(NS, "github_token")
    val GitlabToken = StringPreferenceKey(NS, "gitlab_token")

    /** GitHub / GitLab 的「包名 → 仓库」跟踪表，JSON 数组。 */
    val TrackedRepos = StringPreferenceKey(NS, "tracked_repos")

    /** 上次同步 F-Droid 索引的时间戳（毫秒）。 */
    val FdroidIndexSyncedAt = StringPreferenceKey(NS, "fdroid_index_synced_at")
}
