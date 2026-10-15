package top.app.market.viewmodel

import top.app.market.domain.model.market.AppSource
import top.app.market.domain.model.market.MarketAppInfo
import top.app.market.domain.model.market.mergeUpdateSources
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateSourceMergeTest {
    @Test
    fun keepsHighestVersionForEachPackage() {
        val xiaomi = app("a", versionCode = 10, source = AppSource.XIAOMI)
        val wandoujia = app("a", versionCode = 12, source = AppSource.WANDOUJIA)
        val second = app("b", versionCode = 3, source = AppSource.WANDOUJIA)

        val merged = mergeUpdateSources(listOf(xiaomi), listOf(wandoujia, second))

        assertEquals(listOf("a", "b"), merged.map { it.packageName })
        assertEquals(12L, merged.first().versionCode)
        assertEquals(AppSource.WANDOUJIA, merged.first().source)
    }

    @Test
    fun fallsBackToVersionNameWhenVersionCodeMissing() {
        // Google Play 不公开 versionCode：双方都为 0 时按 versionName 语义化比较（1.10.0 > 1.2.0）
        val older = app("c", versionCode = 0, source = AppSource.PLAY).copy(versionName = "1.2.0")
        val newer = app("c", versionCode = 0, source = AppSource.PLAY).copy(versionName = "1.10.0")

        val merged = mergeUpdateSources(listOf(older), listOf(newer))

        assertEquals("1.10.0", merged.single().versionName)
    }

    private fun app(packageName: String, versionCode: Long, source: AppSource) = MarketAppInfo(
        appId = versionCode,
        packageName = packageName,
        displayName = packageName,
        publisherName = "",
        versionName = versionCode.toString(),
        versionCode = versionCode,
        icon = "",
        apkSize = 1L,
        ratingScore = 0.0,
        source = source,
    )
}
