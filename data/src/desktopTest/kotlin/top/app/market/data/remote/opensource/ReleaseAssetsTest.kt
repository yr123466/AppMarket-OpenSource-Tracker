package top.app.market.data.remote.opensource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseAssetsTest {

    @Test
    fun parsesPackageNameAndVersionCodeFromApkAssetName() {
        // F-Droid / IzzyOnDroid 系发布脚本的通行命名
        val identity = parseApkAssetName("org.fdroid.fdroid_1018050.apk")
        assertEquals("org.fdroid.fdroid", identity?.packageName)
        assertEquals(1018050L, identity?.versionCode)

        val second = parseApkAssetName("com.example.my_app_42.apk")
        assertEquals("com.example.my_app", second?.packageName)
        assertEquals(42L, second?.versionCode)
    }

    @Test
    fun fallsBackToPackageNameOnlyWhenVersionCodeIsMissing() {
        val identity = parseApkAssetName("com.example.app.apk")
        assertEquals("com.example.app", identity?.packageName)
        assertEquals(0L, identity?.versionCode)
    }

    @Test
    fun rejectsNonApkAndMalformedNames() {
        assertNull(parseApkAssetName("org.fdroid.fdroid_1018050.apk.asc"))
        assertNull(parseApkAssetName("org.fdroid.fdroid_1018050.apk.idsig"))
        assertNull(parseApkAssetName("app-release.apk"))
        assertNull(parseApkAssetName("org.fdroid.fdroid_0.apk"))
        assertNull(parseApkAssetName("checksums.txt"))
    }

    @Test
    fun detectsInstallableApkAndExcludesSidecarFiles() {
        assertTrue(isInstallableApk("app-release.apk"))
        assertTrue(isInstallableApk("APP.APK"))
        assertFalse(isInstallableApk("app-release.apk.asc"))
        assertFalse(isInstallableApk("app-release.apk.idsig"))
        assertFalse(isInstallableApk("app-release.apk.sha256"))
        assertFalse(isInstallableApk("app-release.aab"))
    }

    @Test
    fun prefersUniversalThenArm64ThenLargest() {
        val assets = listOf(
            "app_1_armeabi-v7a.apk" to 3_000_000L,
            "app_1_arm64-v8a.apk" to 5_000_000L,
            "app_1_x86.apk" to 4_000_000L,
        )
        assertEquals(
            "app_1_arm64-v8a.apk",
            pickBestApk(assets, { it.first }, { it.second })?.first,
        )

        val withUniversal = assets + ("app_1_universal.apk" to 9_000_000L)
        assertEquals(
            "app_1_universal.apk",
            pickBestApk(withUniversal, { it.first }, { it.second })?.first,
        )
    }

    @Test
    fun ignoresSignatureFilesWhenPickingApk() {
        val assets = listOf(
            "app_1.apk.asc" to 659L,
            "app_1.apk.idsig" to 107_998L,
            "app_1.apk" to 12_688_611L,
        )
        assertEquals("app_1.apk", pickBestApk(assets, { it.first }, { it.second })?.first)
    }

    @Test
    fun returnsNullWhenNoApkIsPresent() {
        val assets = listOf("source.tar.gz" to 1L, "checksums.txt" to 2L)
        assertNull(pickBestApk(assets, { it.first }, { it.second }))
    }

    @Test
    fun comparesSemanticVersionsNumericallyNotLexically() {
        // 字符串比较会把 1.10 判成小于 1.9，这里必须按数字段比
        assertTrue(compareVersionNames("1.10.0", "1.9.2") > 0)
        assertTrue(compareVersionNames("v2.0.0", "1.99.99") > 0)
        assertTrue(compareVersionNames("1.0", "1.0.0") == 0)
        assertTrue(compareVersionNames("1.0.1", "1.0") > 0)
        assertTrue(compareVersionNames("0.9", "1.0") < 0)
    }

    @Test
    fun treatsPrereleaseAsOlderThanTheStableRelease() {
        assertTrue(compareVersionNames("1.0.0", "1.0.0-rc1") > 0)
        assertTrue(compareVersionNames("2.0-rc1", "1.9.9") > 0)
        assertTrue(compareVersionNames("1.0.0-beta2", "1.0.0-alpha1") > 0)
    }

    @Test
    fun normalizesReleaseTagsForDisplay() {
        assertEquals("1.18.0", normalizeReleaseVersion("v1.18.0"))
        assertEquals("2.3.5", normalizeReleaseVersion(" 2.3.5 "))
        assertEquals("release-2024", normalizeReleaseVersion("release-2024"))
    }
}
