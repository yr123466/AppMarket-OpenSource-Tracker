package top.app.market.data.remote.github

import top.app.market.domain.exception.MarketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Device Flow 的响应解析。
 *
 * 这套接口的特殊之处在于：失败时依然是 HTTP 200，状态全靠 body 里的 `error` 字段区分，
 * 因此解析分支是整条授权链路最容易出错的地方，逐条钉住。
 */
class GithubAuthApiTest {

    @Test
    fun `设备码响应按 GitHub 实际字段解析`() {
        val body = """
            {
              "device_code": "3584d83530557fdd1f46af8289938c8ef79f9dc5",
              "user_code": "WDJB-MJHT",
              "verification_uri": "https://github.com/login/device",
              "expires_in": 900,
              "interval": 5
            }
        """.trimIndent()

        val code = parseDeviceCodeResponse(body)

        assertEquals("3584d83530557fdd1f46af8289938c8ef79f9dc5", code.deviceCode)
        assertEquals("WDJB-MJHT", code.userCode)
        assertEquals("https://github.com/login/device", code.verificationUri)
        assertEquals(900, code.expiresInSeconds)
        assertEquals(5, code.intervalSeconds)
    }

    @Test
    fun `缺少 interval 与 expires_in 时回落到 GitHub 的默认值`() {
        val body = """{"device_code":"abc","user_code":"WDJB-MJHT","verification_uri":"https://github.com/login/device"}"""

        val code = parseDeviceCodeResponse(body)

        assertEquals(900, code.expiresInSeconds)
        assertEquals(5, code.intervalSeconds)
    }

    @Test
    fun `缺少 verification_uri 时用调用方给的兜底地址`() {
        val body = """{"device_code":"abc","user_code":"WDJB-MJHT"}"""

        val code = parseDeviceCodeResponse(body, fallbackVerificationUri = "https://example.com/device")

        assertEquals("https://example.com/device", code.verificationUri)
    }

    @Test
    fun `client_id 无效时抛出带描述的异常`() {
        val body = """{"error":"invalid_client","error_description":"The client_id is not valid."}"""

        val error = assertFailsWith<MarketException> { parseDeviceCodeResponse(body) }

        assertTrue(error.message.orEmpty().contains("The client_id is not valid."), error.message.orEmpty())
    }

    @Test
    fun `authorization_pending 映射为 Pending`() {
        val body = """{"error":"authorization_pending","error_description":"The authorization request is still pending."}"""

        assertIs<TokenPollOutcome.Pending>(parseTokenResponse(body))
    }

    @Test
    fun `slow_down 采用响应里的新间隔`() {
        val body = """{"error":"slow_down","error_description":"Too many requests.","interval":10}"""

        val outcome = assertIs<TokenPollOutcome.SlowDown>(parseTokenResponse(body))

        assertEquals(10, outcome.intervalSeconds)
    }

    @Test
    fun `slow_down 未带间隔时回落到 10 秒`() {
        val body = """{"error":"slow_down"}"""

        val outcome = assertIs<TokenPollOutcome.SlowDown>(parseTokenResponse(body))

        assertEquals(10, outcome.intervalSeconds)
    }

    @Test
    fun `access_denied 与 expired_token 分别映射`() {
        assertIs<TokenPollOutcome.Denied>(parseTokenResponse("""{"error":"access_denied"}"""))
        assertIs<TokenPollOutcome.Expired>(parseTokenResponse("""{"error":"expired_token"}"""))
    }

    @Test
    fun `拿到 access_token 时返回 Success`() {
        val body = """
            {
              "access_token": "gho_16C7e42F292c6912E7710c838347Ae178B4a",
              "token_type": "bearer",
              "scope": "read:user"
            }
        """.trimIndent()

        val outcome = assertIs<TokenPollOutcome.Success>(parseTokenResponse(body))

        assertEquals("gho_16C7e42F292c6912E7710c838347Ae178B4a", outcome.token)
    }

    @Test
    fun `未知错误不会被当成待授权`() {
        val body = """{"error":"unsupported_grant_type","error_description":"Bad grant type."}"""

        val error = assertFailsWith<MarketException> { parseTokenResponse(body) }

        assertTrue(error.message.orEmpty().contains("Bad grant type."), error.message.orEmpty())
    }

    @Test
    fun `既无 token 也无 error 时视为响应异常`() {
        assertFailsWith<MarketException> { parseTokenResponse("""{"token_type":"bearer"}""") }
    }

    @Test
    fun `账号响应解析出登录名`() {
        val body = """{"login":"yr123466","id":1234567,"name":"Open Source Tracker"}"""

        val account = parseAccountResponse(body)

        assertEquals("yr123466", account.login)
        assertEquals("Open Source Tracker", account.name)
    }

    @Test
    fun `账号响应缺少 login 时抛出异常`() {
        assertFailsWith<MarketException> { parseAccountResponse("""{"id":1234567}""") }
    }
}
