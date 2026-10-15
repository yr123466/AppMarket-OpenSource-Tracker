package top.app.market.domain.model.tracked

import top.app.market.domain.model.market.AppSource

/**
 * 一条「本地包名 → 开源仓库」的跟踪记录。
 *
 * GitHub / GitLab 都没有「按包名反查仓库」的接口，因此更新检查依赖这张本地映射表：
 * 用户在本应用内打开过某个开源应用的详情页（或直接粘贴仓库链接）时写入，
 * 之后的更新检查再按 [slug] 逐个查询最新发布并对比版本号。
 */
data class TrackedRepo(
    val packageName: String,
    /** GitHub 为 `owner/repo`，GitLab 为 `namespace/project`。 */
    val slug: String,
    val source: AppSource,
    /** 远端仓库数字 id（GitHub repo id / GitLab project id），用于详情页跳转。 */
    val appId: Long = 0L,
    val displayName: String = "",
    val icon: String = "",
    val addedAt: Long = 0L,
)
