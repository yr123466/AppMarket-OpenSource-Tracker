package top.app.market.domain.model.market

/**
 * 按包名合并两个来源的更新结果：同一包名保留版本更高的一条。
 *
 * 版本比较优先使用 [MarketAppInfo.versionCode]；当双方都没有 versionCode（例如 Google Play
 * 不公开 versionCode，仅能拿到 versionName）时，回退到 [compareVersionName] 做语义化比较。
 * 顺序稳定：先出现的来源作为基准，后续来源只有「更新」时才覆盖。
 */
fun mergeUpdateSources(
    primary: List<MarketAppInfo>,
    secondary: List<MarketAppInfo>,
): List<MarketAppInfo> {
    if (primary.isEmpty()) return secondary
    if (secondary.isEmpty()) return primary
    val merged = LinkedHashMap<String, MarketAppInfo>(primary.size + secondary.size)
    primary.forEach { merged[it.packageName] = it }
    secondary.forEach { incoming ->
        val current = merged[incoming.packageName]
        if (current == null || isNewerVersion(incoming, current)) merged[incoming.packageName] = incoming
    }
    return merged.values.toList()
}

/** [candidate] 是否比 [current] 版本更高。 */
fun isNewerVersion(candidate: MarketAppInfo, current: MarketAppInfo): Boolean {
    val candidateCode = candidate.versionCode
    val currentCode = current.versionCode
    return when {
        candidateCode > 0L && currentCode > 0L -> candidateCode > currentCode
        candidateCode <= 0L && currentCode <= 0L -> compareVersionName(candidate.versionName, current.versionName) > 0
        else -> candidateCode > currentCode
    }
}

/**
 * 语义化版本比较：按 `.` `-` `_` 分段，逐段做数值比较，非数字段退化为字典序。
 * 返回 >0 表示 [a] 更新，<0 表示 [b] 更新，0 表示相等。
 */
fun compareVersionName(a: String, b: String): Int {
    if (a == b) return 0
    val partsA = a.split('.', '-', '_').filter { it.isNotEmpty() }
    val partsB = b.split('.', '-', '_').filter { it.isNotEmpty() }
    val size = maxOf(partsA.size, partsB.size)
    for (index in 0 until size) {
        val segA = partsA.getOrNull(index) ?: "0"
        val segB = partsB.getOrNull(index) ?: "0"
        val numA = segA.toLongOrNull()
        val numB = segB.toLongOrNull()
        val cmp = if (numA != null && numB != null) numA.compareTo(numB) else segA.compareTo(segB)
        if (cmp != 0) return cmp
    }
    return 0
}
