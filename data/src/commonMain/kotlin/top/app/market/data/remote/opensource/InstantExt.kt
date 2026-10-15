package top.app.market.data.remote.opensource

import kotlinx.datetime.Instant

/**
 * 把 GitHub / GitLab / F-Droid 返回的各种 ISO-8601 时间戳（`2024-01-01T00:00:00Z`、
 * 带毫秒、带时区偏移）统一转成 epoch 毫秒，用于「更新时间」展示。
 *
 * 解析失败或为空一律返回 `0`（语义：未知），调用方按 0 不显示即可，不抛异常。
 */
internal fun String?.toEpochMillisOrNull(): Long {
    val raw = this?.trim() ?: return 0L
    if (raw.isEmpty()) return 0L
    return runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull() ?: 0L
}

/**
 * F-Droid `packages[].added` 历史上是 epoch 秒，新版本是 epoch 毫秒。
 * 用量级自动归一化，避免把秒当成毫秒（会显示成 1970 年）。
 */
internal fun Long.normalizeFdroidAdded(): Long {
    if (this <= 0L) return 0L
    // 毫秒量级（≥ 2001 年）直接采用；否则按秒处理。
    return if (this > 1_000_000_000_000L) this else this * 1000L
}
