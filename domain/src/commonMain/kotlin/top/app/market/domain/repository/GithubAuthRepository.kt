package top.app.market.domain.repository

import top.app.market.domain.model.github.GithubAuthPoll
import top.app.market.domain.model.github.GithubAuthStatus
import top.app.market.domain.model.github.GithubDeviceCode

/**
 * GitHub 账号授权，用来把接口限额从匿名 60 次/小时提升到账号 5000 次/小时。
 *
 * 走 OAuth Device Flow：申请设备码 → 用户在浏览器输入 user code → 轮询换取 token。
 * 拿到 token 后写进 [OpenSourceTokenStore] 同一条存储，GitHub 相关的所有请求自动带上。
 */
interface GithubAuthRepository {
    /** 是否内置了 OAuth App 的 client_id。未配置时无法发起授权，只能手动填 token。 */
    val deviceFlowAvailable: Boolean

    /** 读取本地鉴权状态，不发起网络请求。 */
    suspend fun status(): GithubAuthStatus

    /** 申请设备码。未配置 client_id 时抛 [IllegalStateException]。 */
    suspend fun requestDeviceCode(): GithubDeviceCode

    /** 轮询一次授权结果；返回 [GithubAuthPoll.Authorized] 时 token 已写入本地。 */
    suspend fun poll(deviceCode: String): GithubAuthPoll

    /**
     * 手动保存一个 Personal Access Token（未配置 OAuth App 时的兜底）。
     *
     * 会先请求 `/user` 验证有效性，通过后连同登录名一起保存；失败返回错误信息。
     */
    suspend fun saveManualToken(token: String): Result<Unit>

    /** 清除本地 token 与账号信息，回到匿名访问。 */
    suspend fun signOut()
}
