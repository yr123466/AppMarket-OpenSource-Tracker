package top.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.app.market.domain.model.github.GithubAccount
import top.app.market.domain.model.github.GithubAuthPoll
import top.app.market.domain.model.github.GithubAuthStatus
import top.app.market.domain.model.github.GithubDeviceCode
import top.app.market.domain.repository.GithubAuthRepository

/** 授权流程所处的阶段。 */
sealed interface GithubAuthPhase {
    /** 空闲：可以发起授权。 */
    data object Idle : GithubAuthPhase

    /** 正在申请设备码。 */
    data object RequestingCode : GithubAuthPhase

    /** 已拿到设备码，等待用户在浏览器里完成授权。 */
    data class Waiting(val deviceCode: GithubDeviceCode) : GithubAuthPhase

    /** 授权成功。 */
    data class Done(val account: GithubAccount) : GithubAuthPhase

    /** 失败，可重试。 */
    data class Failed(val message: String) : GithubAuthPhase
}

@Immutable
data class GithubAuthUiState(
    val loading: Boolean = true,
    val status: GithubAuthStatus = GithubAuthStatus(),
    val phase: GithubAuthPhase = GithubAuthPhase.Idle,
    /** 设备码剩余有效秒数，用于页面上的倒计时。 */
    val secondsLeft: Int = 0,
    val manualToken: String = "",
    val manualTokenBusy: Boolean = false,
    val manualTokenError: String = "",
    /** 一次性提示（保存成功等）。 */
    val message: String = "",
)

/**
 * GitHub 授权页的状态机。
 *
 * 轮询节奏完全由 GitHub 说了算：设备码带一个 `interval`，`slow_down` 时还必须继续放慢。
 * 因此这里用「1 秒一跳、累加到点才真正请求」的循环，既能驱动倒计时，也保证不会比 GitHub 要求更频繁。
 * 单次网络抖动不算失败（地铁里刷一下不该让用户重来），连续失败 3 次才中断。
 */
class GithubAuthViewModel(
    private val repository: GithubAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GithubAuthUiState())
    val uiState: StateFlow<GithubAuthUiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            val status = runCatchingCancellable { repository.status() }.getOrNull()
            _uiState.update { it.copy(loading = false, status = status ?: GithubAuthStatus()) }
        }
    }

    fun startAuthorization() {
        if (_uiState.value.phase is GithubAuthPhase.Waiting) return
        pollJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(phase = GithubAuthPhase.RequestingCode, secondsLeft = 0, message = "") }
            runCatchingCancellable { repository.requestDeviceCode() }.fold(
                onSuccess = { code ->
                    _uiState.update {
                        it.copy(phase = GithubAuthPhase.Waiting(code), secondsLeft = code.expiresInSeconds)
                    }
                    poll(code)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(phase = GithubAuthPhase.Failed(error.message ?: "申请设备码失败"))
                    }
                },
            )
        }
    }

    fun cancelAuthorization() {
        pollJob?.cancel()
        pollJob = null
        _uiState.update { it.copy(phase = GithubAuthPhase.Idle, secondsLeft = 0) }
    }

    fun signOut() {
        pollJob?.cancel()
        pollJob = null
        viewModelScope.launch {
            runCatchingCancellable { repository.signOut() }
            _uiState.update {
                it.copy(
                    status = it.status.copy(account = null, hasToken = false),
                    phase = GithubAuthPhase.Idle,
                    secondsLeft = 0,
                    message = "",
                )
            }
        }
    }

    fun setManualToken(value: String) {
        _uiState.update { it.copy(manualToken = value, manualTokenError = "", message = "") }
    }

    fun saveManualToken() {
        val token = _uiState.value.manualToken.trim()
        if (token.isBlank()) {
            _uiState.update { it.copy(manualTokenError = "令牌不能为空") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(manualTokenBusy = true, manualTokenError = "", message = "") }
            repository.saveManualToken(token).fold(
                onSuccess = {
                    val status = runCatchingCancellable { repository.status() }.getOrNull()
                    _uiState.update {
                        it.copy(
                            manualTokenBusy = false,
                            manualToken = "",
                            status = status ?: it.status.copy(hasToken = true),
                            message = "访问令牌已保存",
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(manualTokenBusy = false, manualTokenError = error.message ?: "令牌无效或网络异常")
                    }
                },
            )
        }
    }

    private fun poll(code: GithubDeviceCode) {
        pollJob = viewModelScope.launch {
            var interval = code.intervalSeconds.coerceAtLeast(5)
            var elapsed = 0
            var nextPollAt = interval
            var consecutiveFailures = 0
            while (isActive) {
                delay(1_000)
                elapsed += 1
                _uiState.update { it.copy(secondsLeft = (code.expiresInSeconds - elapsed).coerceAtLeast(0)) }
                if (elapsed >= code.expiresInSeconds) {
                    fail("设备码已过期，请重新获取")
                    return@launch
                }
                if (elapsed < nextPollAt) continue

                val outcome = runCatchingCancellable { repository.poll(code.deviceCode) }
                val result = outcome.getOrNull()
                if (result == null) {
                    // 单次网络抖动继续等；连续失败才中断，避免用户在地铁里被迫重来
                    consecutiveFailures += 1
                    nextPollAt = elapsed + interval
                    if (consecutiveFailures >= 3) {
                        fail(outcome.exceptionOrNull()?.message ?: "网络异常，请重试")
                        return@launch
                    }
                    continue
                }
                consecutiveFailures = 0

                when (result) {
                    GithubAuthPoll.Pending -> nextPollAt = elapsed + interval

                    is GithubAuthPoll.SlowDown -> {
                        interval = result.intervalSeconds.coerceAtLeast(interval + 5)
                        nextPollAt = elapsed + interval
                    }

                    GithubAuthPoll.Denied -> {
                        fail("已取消授权")
                        return@launch
                    }

                    GithubAuthPoll.Expired -> {
                        fail("设备码已过期，请重新获取")
                        return@launch
                    }

                    is GithubAuthPoll.Authorized -> {
                        _uiState.update {
                            it.copy(
                                phase = GithubAuthPhase.Done(result.account),
                                secondsLeft = 0,
                                status = it.status.copy(account = result.account, hasToken = true),
                                message = "",
                            )
                        }
                        return@launch
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(phase = GithubAuthPhase.Failed(message), secondsLeft = 0) }
    }
}
