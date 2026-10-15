package top.app.market.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.app.market.platform.UiPlatform
import top.app.market.resources.Res
import top.app.market.resources.github_auth
import top.app.market.resources.github_auth_cancel
import top.app.market.resources.github_auth_copied
import top.app.market.resources.github_auth_copy
import top.app.market.resources.github_auth_device_hint
import top.app.market.resources.github_auth_expires_in
import top.app.market.resources.github_auth_manual
import top.app.market.resources.github_auth_manual_hint
import top.app.market.resources.github_auth_manual_summary
import top.app.market.resources.github_auth_open
import top.app.market.resources.github_auth_requesting
import top.app.market.resources.github_auth_retry
import top.app.market.resources.github_auth_save
import top.app.market.resources.github_auth_sign_in
import top.app.market.resources.github_auth_sign_out
import top.app.market.resources.github_auth_state_signed_in
import top.app.market.resources.github_auth_state_signed_out
import top.app.market.resources.github_auth_state_token
import top.app.market.resources.github_auth_succeeded
import top.app.market.resources.github_auth_summary
import top.app.market.resources.github_auth_unavailable
import top.app.market.resources.github_auth_waiting
import top.app.market.ui.component.AppButton
import top.app.market.ui.component.AppTextButton
import top.app.market.ui.component.MarketScaffold
import top.app.market.ui.component.PageVerticalPadding
import top.app.market.viewmodel.GithubAuthPhase
import top.app.market.viewmodel.GithubAuthViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * GitHub 授权页。
 *
 * 主路径是 OAuth Device Flow：点「使用 GitHub 登录」→ 拿到 8 位设备码 → 用户在浏览器输入 → 自动轮询完成。
 * 兜底路径是手动填 Personal Access Token，给没有内置 OAuth App 的构建留一条路。
 */
@Composable
fun GithubAuthScreen(
    viewModel: GithubAuthViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val platform = koinInject<UiPlatform>()
    val uriHandler = LocalUriHandler.current
    val copiedNotice = stringResource(Res.string.github_auth_copied)

    MarketScaffold(
        title = stringResource(Res.string.github_auth),
        onBack = onBack,
    ) { innerPadding, backdropModifier, scrollBehavior ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropModifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
            ),
        ) {
            item(key = "status") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.github_auth),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        val account = state.status.account
                        val stateText = when {
                            account != null -> stringResource(
                                Res.string.github_auth_state_signed_in,
                                account.login,
                            )

                            state.status.hasToken -> stringResource(Res.string.github_auth_state_token)
                            else -> stringResource(Res.string.github_auth_state_signed_out)
                        }
                        Text(
                            text = stateText,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(Res.string.github_auth_summary),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(2.dp))
                        if (state.status.hasToken) {
                            AppTextButton(
                                text = stringResource(Res.string.github_auth_sign_out),
                                onClick = viewModel::signOut,
                            )
                        } else if (state.status.deviceFlowAvailable) {
                            AppButton(
                                text = stringResource(Res.string.github_auth_sign_in),
                                onClick = viewModel::startAuthorization,
                                enabled = state.phase !is GithubAuthPhase.RequestingCode,
                            )
                        } else {
                            Text(
                                text = stringResource(Res.string.github_auth_unavailable),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            when (val phase = state.phase) {
                GithubAuthPhase.Idle -> Unit

                GithubAuthPhase.RequestingCode -> item(key = "requesting") {
                    AuthCard {
                        Text(
                            text = stringResource(Res.string.github_auth_requesting),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                is GithubAuthPhase.Waiting -> item(key = "waiting") {
                    AuthCard {
                        Text(
                            text = stringResource(
                                Res.string.github_auth_device_hint,
                                phase.deviceCode.verificationUri,
                            ),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = phase.deviceCode.userCode,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuixTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = stringResource(
                                Res.string.github_auth_expires_in,
                                formatCountdown(state.secondsLeft),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppButton(
                                text = stringResource(Res.string.github_auth_copy),
                                onClick = {
                                    if (platform.writeClipboard(phase.deviceCode.userCode)) {
                                        platform.showToast(copiedNotice)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            AppButton(
                                text = stringResource(Res.string.github_auth_open),
                                onClick = { uriHandler.openUri(phase.deviceCode.verificationUri) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = stringResource(Res.string.github_auth_waiting),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        AppTextButton(
                            text = stringResource(Res.string.github_auth_cancel),
                            onClick = viewModel::cancelAuthorization,
                        )
                    }
                }

                is GithubAuthPhase.Done -> item(key = "done") {
                    AuthCard {
                        Text(
                            text = stringResource(Res.string.github_auth_succeeded, phase.account.login),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }

                is GithubAuthPhase.Failed -> item(key = "failed") {
                    AuthCard {
                        Text(
                            text = phase.message,
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.error,
                        )
                        AppTextButton(
                            text = stringResource(Res.string.github_auth_retry),
                            onClick = viewModel::startAuthorization,
                        )
                    }
                }
            }

            item(key = "manual") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.github_auth_manual),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(Res.string.github_auth_manual_summary),
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextField(
                            value = state.manualToken,
                            onValueChange = viewModel::setManualToken,
                            label = stringResource(Res.string.github_auth_manual_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.manualTokenError.isNotBlank()) {
                            Text(
                                text = state.manualTokenError,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.error,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppButton(
                                text = stringResource(Res.string.github_auth_save),
                                onClick = viewModel::saveManualToken,
                                enabled = !state.manualTokenBusy,
                            )
                            if (state.message.isNotBlank()) {
                                Text(
                                    text = state.message,
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthCard(content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

/** 设备码剩余时间，形如 `12:34`。 */
private fun formatCountdown(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}
