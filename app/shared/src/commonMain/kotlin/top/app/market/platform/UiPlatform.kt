package top.app.market.platform

interface UiPlatform {
    val packageInstallationSupported: Boolean
    fun showToast(message: String)
    fun openAppSettings(): Boolean
    fun openUnknownSourcesSettings(): Boolean
    fun openInOtherAppStore(packageName: String): Boolean = false
    fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit): Boolean
    fun requestPostNotificationsPermission(onResult: () -> Unit): Boolean
    suspend fun saveImageToPictures(url: String, fileName: String): ImageSaveResult

    /** 分享一段文本：移动端拉起系统分享面板，桌面端复制到剪贴板。 */
    fun shareText(text: String): ShareResult = ShareResult.Unsupported

    /** 读取剪贴板文本；剪贴板自上次读取后没有变化时返回 null，避免重复读取（Android 每次读取会提示用户）。 */
    suspend fun readNewClipboardText(): String? = null

    /**
     * 主动读取剪贴板文本，供用户手动触发的「从链接导入」使用。
     * 与 [readNewClipboardText] 不同：这里不做「内容是否变化」的判断，每次点击都取当前内容。
     */
    suspend fun readClipboardText(): String? = null

    /** 写入剪贴板，返回是否成功。用于复制 GitHub 设备码这类短文本。 */
    fun writeClipboard(text: String): Boolean = false
}
