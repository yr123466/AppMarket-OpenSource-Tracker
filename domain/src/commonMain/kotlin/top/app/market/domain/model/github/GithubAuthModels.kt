package top.app.market.domain.model.github

/**
 * 一次 GitHub Device Flow 授权会话。
 *
 * Device Flow 不需要 client_secret，也不需要回调地址，是客户端应用唯一可行的 OAuth 形态：
 * 先申请设备码，把 [userCode] 展示给用户，让用户在浏览器里输入，再按 [intervalSeconds] 轮询换 token。
 */
data class GithubDeviceCode(
    /** 轮询用的设备码，不展示给用户。 */
    val deviceCode: String,
    /** 展示给用户的 8 位码，形如 `WDJB-MJHT`。 */
    val userCode: String,
    /** 用户输入 user code 的地址，通常是 `https://github.com/login/device`。 */
    val verificationUri: String,
    /** 设备码有效期（秒），GitHub 目前固定 900。 */
    val expiresInSeconds: Int,
    /** 轮询间隔（秒），GitHub 目前默认 5；被限速时会通过 [GithubAuthPoll.SlowDown] 抬高。 */
    val intervalSeconds: Int,
)

/** Device Flow 单次轮询的结果。 */
sealed interface GithubAuthPoll {
    /** 用户还没完成授权，按间隔继续轮询。 */
    data object Pending : GithubAuthPoll

    /** 用户点得太快，GitHub 要求放慢；下次轮询间隔应至少加 5 秒。 */
    data class SlowDown(val intervalSeconds: Int) : GithubAuthPoll

    /** 用户在授权页点了取消。 */
    data object Denied : GithubAuthPoll

    /** 设备码已过期，需要重新申请。 */
    data object Expired : GithubAuthPoll

    /** 授权成功。 */
    data class Authorized(val account: GithubAccount) : GithubAuthPoll
}

/** 已授权的 GitHub 账号。 */
data class GithubAccount(
    val login: String,
    val name: String = "",
)

/**
 * 当前的开源来源鉴权状态。
 *
 * GitHub 匿名接口按 IP 限流 60 次/小时，跟踪仓库一多必然打满；带 token 后按账号计费，上限 5000 次/小时。
 * 因此这个状态直接决定「检查更新」能否覆盖到全部仓库。
 */
data class GithubAuthStatus(
    /** 走 Device Flow 授权得到的账号；手动填 token 时为 null。 */
    val account: GithubAccount? = null,
    /** 是否已经配置了可用的 token（授权或手动填写都算）。 */
    val hasToken: Boolean = false,
    /** 是否内置了 OAuth App 的 client_id；未配置时只能手动填 token。 */
    val deviceFlowAvailable: Boolean = false,
)
