package top.app.market.domain.repository

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.tracked.TrackedRepo

/**
 * GitHub / GitLab 的「包名 → 仓库」跟踪表。
 *
 * 这两个源没有按包名反查的接口，所以更新检查必须依赖本表。F-Droid 不需要它
 * （官方仓库有 `api/v1/packages/{包名}` 可以直接反查）。
 */
interface TrackedRepoRepository {
    /** 某个源已跟踪的全部仓库。 */
    suspend fun tracked(source: AppSource): List<TrackedRepo>

    /** 写入或更新一条跟踪记录（按 packageName + source 去重）。 */
    suspend fun track(repo: TrackedRepo)

    /** 移除一条跟踪记录。 */
    suspend fun untrack(packageName: String, source: AppSource)

    /** 查询某个已安装包名对应的仓库，未跟踪时返回 null。 */
    suspend fun find(packageName: String, source: AppSource): TrackedRepo?
}
