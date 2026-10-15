package top.app.market.domain.model.market

/**
 * 单包更新检查（「包名检测」）的三态结果。
 *
 * 必须区分 [UpToDate] 与 [NotFound]：前者说明这个源**确实收录**了该应用、只是版本不比本地新，
 * 后者说明这个源里根本没有它。UI 对这两种情况的提示完全不同。
 */
sealed interface SourceUpdateCheck {
    /** 有可用更新。 */
    data class Available(val app: MarketAppInfo) : SourceUpdateCheck

    /** 该源收录了这个应用，但本地版本已是最新。 */
    data object UpToDate : SourceUpdateCheck

    /** 该源未收录这个包名（GitHub / GitLab 还可能是尚未跟踪该仓库）。 */
    data object NotFound : SourceUpdateCheck
}
