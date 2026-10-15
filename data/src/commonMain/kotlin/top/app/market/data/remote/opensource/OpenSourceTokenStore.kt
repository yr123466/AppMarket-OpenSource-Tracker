package top.app.market.data.remote.opensource

import top.app.market.data.local.PreferencesDataSource
import top.app.market.data.local.preferences.OpenSourcePreferenceKeys
import kotlinx.coroutines.CancellationException
import top.app.market.data.platform.debugLog

/**
 * GitHub / GitLab 的可选访问令牌。
 *
 * 两个接口在匿名状态下的限流都很紧（GitHub 60 次/小时/IP），跟踪十几个仓库就会打满，
 * 因此允许用户填一个只读 token。读取失败一律退化为匿名，不影响主流程。
 */
internal class OpenSourceTokenStore(
    private val preferences: PreferencesDataSource,
) {
    suspend fun github(): String = read(OpenSourcePreferenceKeys.GithubToken)

    suspend fun gitlab(): String = read(OpenSourcePreferenceKeys.GitlabToken)

    suspend fun setGithub(token: String) =
        preferences.put(OpenSourcePreferenceKeys.GithubToken, token.trim())

    suspend fun setGitlab(token: String) =
        preferences.put(OpenSourcePreferenceKeys.GitlabToken, token.trim())

    private suspend fun read(key: top.app.market.data.local.StringPreferenceKey): String = try {
        preferences.read(key)?.trim().orEmpty()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        debugLog("OpenSourceToken") { "Failed to read token: ${error.message}" }
        ""
    }
}
