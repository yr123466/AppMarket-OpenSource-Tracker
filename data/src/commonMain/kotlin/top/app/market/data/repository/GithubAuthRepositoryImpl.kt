package top.app.market.data.repository

import kotlinx.coroutines.CancellationException
import top.app.market.data.remote.github.GithubAuthApi
import top.app.market.data.remote.github.TokenPollOutcome
import top.app.market.data.remote.opensource.OpenSourceTokenStore
import top.app.market.domain.model.github.GithubAccount
import top.app.market.domain.model.github.GithubAuthPoll
import top.app.market.domain.model.github.GithubAuthStatus
import top.app.market.domain.model.github.GithubDeviceCode
import top.app.market.domain.repository.GithubAuthRepository

/**
 * GitHub 授权落地：token 与登录名都写进 [OpenSourceTokenStore]。
 *
 * 之所以复用同一条存储，是因为 [top.app.market.data.remote.github.GithubApi] 已经会在每次请求前
 * 读取它并带上 `Authorization: Bearer`，授权成功后无需改任何调用点，限额立刻从 60 次/小时升到 5000 次/小时。
 */
internal class GithubAuthRepositoryImpl(
    private val api: GithubAuthApi,
    private val tokens: OpenSourceTokenStore,
) : GithubAuthRepository {

    override val deviceFlowAvailable: Boolean get() = api.isConfigured

    override suspend fun status(): GithubAuthStatus {
        val token = tokens.github()
        val login = tokens.githubLogin()
        return GithubAuthStatus(
            account = GithubAccount(login = login).takeIf { login.isNotBlank() && token.isNotBlank() },
            hasToken = token.isNotBlank(),
            deviceFlowAvailable = deviceFlowAvailable,
        )
    }

    override suspend fun requestDeviceCode(): GithubDeviceCode = api.requestDeviceCode()

    override suspend fun poll(deviceCode: String): GithubAuthPoll =
        when (val outcome = api.pollAccessToken(deviceCode)) {
            TokenPollOutcome.Pending -> GithubAuthPoll.Pending
            is TokenPollOutcome.SlowDown -> GithubAuthPoll.SlowDown(outcome.intervalSeconds)
            TokenPollOutcome.Denied -> GithubAuthPoll.Denied
            TokenPollOutcome.Expired -> GithubAuthPoll.Expired
            is TokenPollOutcome.Success -> {
                // token 先落盘再取登录名：即使 /user 这一步失败，限额提升也已经生效
                tokens.setGithub(outcome.token)
                val account = api.fetchAccount(outcome.token)
                tokens.setGithubLogin(account.login)
                GithubAuthPoll.Authorized(account)
            }
        }

    override suspend fun saveManualToken(token: String): Result<Unit> {
        val trimmed = token.trim()
        if (trimmed.isBlank()) return Result.failure(IllegalArgumentException("令牌不能为空"))
        return try {
            val account = api.fetchAccount(trimmed)
            tokens.setGithub(trimmed)
            tokens.setGithubLogin(account.login)
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    override suspend fun signOut() {
        tokens.clearGithub()
    }
}
