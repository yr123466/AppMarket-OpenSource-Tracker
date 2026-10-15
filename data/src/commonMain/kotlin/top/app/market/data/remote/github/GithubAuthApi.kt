package top.app.market.data.remote.github

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.app.market.domain.exception.MarketException
import top.app.market.domain.model.github.GithubAccount
import top.app.market.domain.model.github.GithubDeviceCode

private const val UserAgent = "AppMarket-Android"
private const val ApiVersion = "2022-11-28"

private val AuthJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal data class GithubAuthConfig(
    /** OAuth 端点宿主，与 REST API 不同域。 */
    val oauthBaseUrl: String = "https://github.com",
    val apiBaseUrl: String = "https://api.github.com",
    val clientId: String = GithubOAuthConfig.CLIENT_ID,
    val scope: String = GithubOAuthConfig.SCOPE,
)

/** 轮询换 token 的中间结果；[Success] 拿到的还只是 token，登录名要再请求一次 `/user`。 */
internal sealed interface TokenPollOutcome {
    data object Pending : TokenPollOutcome

    data class SlowDown(val intervalSeconds: Int) : TokenPollOutcome

    data object Denied : TokenPollOutcome

    data object Expired : TokenPollOutcome

    data class Success(val token: String) : TokenPollOutcome
}

/**
 * GitHub OAuth Device Flow 客户端。
 *
 * 只用两个不需要 client_secret 的端点：`POST /login/device/code` 申请设备码，
 * `POST /login/oauth/access_token` 轮询换 token。两者都挂在 github.com 而不是 api.github.com，
 * 且必须显式带 `Accept: application/json` —— 否则 GitHub 返回的是 form-urlencoded。
 *
 * 错误语义特殊：端点失败时通常仍是 HTTP 200，靠 body 里的 `error` 字段区分
 * （`authorization_pending` / `slow_down` / `access_denied` / `expired_token`），
 * 因此解析逻辑必须逐字段读，不能只看状态码。
 */
internal class GithubAuthApi(
    private val client: HttpClient,
    private val config: GithubAuthConfig = GithubAuthConfig(),
) {
    val isConfigured: Boolean get() = config.clientId.isNotBlank()

    suspend fun requestDeviceCode(): GithubDeviceCode {
        check(isConfigured) { "未配置 GitHub OAuth App 的 client_id" }
        val response = client.submitForm(
            url = "${config.oauthBaseUrl}/login/device/code",
            formParameters = Parameters.build {
                append("client_id", config.clientId)
                if (config.scope.isNotBlank()) append("scope", config.scope)
            },
        ) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, UserAgent)
        }
        return parseDeviceCodeResponse(
            body = response.requireBody("申请 GitHub 设备码"),
            fallbackVerificationUri = "${config.oauthBaseUrl}/login/device",
        )
    }

    suspend fun pollAccessToken(deviceCode: String): TokenPollOutcome {
        check(isConfigured) { "未配置 GitHub OAuth App 的 client_id" }
        val response = client.submitForm(
            url = "${config.oauthBaseUrl}/login/oauth/access_token",
            formParameters = Parameters.build {
                append("client_id", config.clientId)
                append("device_code", deviceCode)
                append("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            },
        ) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, UserAgent)
        }
        return parseTokenResponse(response.requireBody("换取 GitHub 访问令牌"))
    }

    suspend fun fetchAccount(token: String): GithubAccount {
        val response = client.get("${config.apiBaseUrl}/user") {
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", ApiVersion)
            header(HttpHeaders.UserAgent, UserAgent)
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        return parseAccountResponse(response.requireBody("读取 GitHub 账号"))
    }

    private suspend fun HttpResponse.requireBody(label: String): String {
        val body = bodyAsText()
        if (status.isSuccess()) return body
        val detail = runCatching { AuthJson.decodeFromString<AuthErrorDto>(body).errorDescription }.getOrNull()
        throw MarketException("$label 失败（HTTP ${status.value}）：${detail.orEmpty().ifBlank { "请稍后重试" }}")
    }
}

internal fun parseDeviceCodeResponse(
    body: String,
    fallbackVerificationUri: String = "https://github.com/login/device",
): GithubDeviceCode {
    val dto = AuthJson.decodeFromString<DeviceCodeDto>(body)
    if (dto.deviceCode.isBlank()) {
        val reason = dto.errorDescription.ifBlank { dto.error.ifBlank { "响应缺少 device_code" } }
        throw MarketException("申请 GitHub 设备码失败：$reason")
    }
    return GithubDeviceCode(
        deviceCode = dto.deviceCode,
        userCode = dto.userCode,
        verificationUri = dto.verificationUri.ifBlank { fallbackVerificationUri },
        expiresInSeconds = dto.expiresIn.takeIf { it > 0 } ?: 900,
        intervalSeconds = dto.interval.takeIf { it > 0 } ?: 5,
    )
}

internal fun parseTokenResponse(body: String): TokenPollOutcome {
    val dto = AuthJson.decodeFromString<TokenDto>(body)
    if (dto.accessToken.isNotBlank()) return TokenPollOutcome.Success(dto.accessToken)
    return when (dto.error) {
        "authorization_pending" -> TokenPollOutcome.Pending
        // GitHub 要求把轮询间隔至少加 5 秒，并会在响应里带上新的 interval
        "slow_down" -> TokenPollOutcome.SlowDown(dto.interval.takeIf { it > 0 } ?: 10)
        "access_denied" -> TokenPollOutcome.Denied
        "expired_token" -> TokenPollOutcome.Expired
        "" -> throw MarketException("换取 GitHub 访问令牌失败：响应既没有 access_token 也没有 error")
        else -> throw MarketException("GitHub 授权失败：${dto.errorDescription.ifBlank { dto.error }}")
    }
}

internal fun parseAccountResponse(body: String): GithubAccount {
    val dto = AuthJson.decodeFromString<AccountDto>(body)
    if (dto.login.isBlank()) throw MarketException("读取 GitHub 账号失败：响应缺少 login")
    return GithubAccount(login = dto.login, name = dto.name)
}

@Serializable
private data class DeviceCodeDto(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    val interval: Int = 0,
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

@Serializable
private data class TokenDto(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    val scope: String = "",
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
    val interval: Int = 0,
)

@Serializable
private data class AccountDto(
    val login: String = "",
    val name: String = "",
)

@Serializable
private data class AuthErrorDto(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)
