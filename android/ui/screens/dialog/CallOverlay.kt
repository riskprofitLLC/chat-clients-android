package chat.client.ui.screens.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.client.R
import chat.client.domain.CallStateMachine
import chat.client.service.CallManager
import chat.client.ui.components.ChatAvatar
import chat.client.ui.theme.Size
import chat.client.ui.theme.Spacing
import kotlinx.coroutines.delay

// Фаза GB: экран звонка — аватар/имя/статус/таймер, кнопки принять/
// отклонить/завершить, микрофон/спикер. Живёт поверх всего UI как
// оверлей ChatApp (звонок не теряется при выходе из чата).
@Composable
fun CallOverlay(
    manager: chat.client.service.CallManager,
    peerName: String,
    modifier: Modifier = Modifier,
) {
    val state by manager.phase.collectAsState()
    val micMuted by manager.micMuted.collectAsState()
    val speakerOn by manager.speakerOn.collectAsState()
    // FGS microphone требует runtime RECORD_AUDIO: запрашиваем на accept (A8)
    val ctx = LocalContext.current
    var micGranted by remember {
        mutableStateOf(
            ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var pendingAccept by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted && pendingAccept) {
            pendingAccept = false
            manager.acceptIncoming()
        }
    }
    if (state.phase == CallStateMachine.Phase.IDLE) return

    CallOverlayContent(
        state = state,
        peerName = peerName,
        micMuted = micMuted,
        speakerOn = speakerOn,
        onAccept = {
            if (micGranted) {
                manager.acceptIncoming()
            } else {
                // без RECORD_AUDIO FGS microphone падает SecurityException
                pendingAccept = true
                micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
        onDecline = { manager.declineIncoming() },
        onHangUp = { manager.hangUp() },
        onToggleMic = { manager.toggleMic() },
        onToggleSpeaker = { manager.toggleSpeaker() },
        modifier = modifier,
    )
}

// GD: чистый контент экрана звонка — стейт и колбэки параметрами,
// UI-тесты гоняют без живого CallManager
@Composable
fun CallOverlayContent(
    state: CallStateMachine.State,
    peerName: String,
    micMuted: Boolean,
    speakerOn: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onHangUp: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val peer = state.peer ?: return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Spacer(Modifier.height(Spacing.xxxl))
        ChatAvatar(peer, isGroup = false, Size.avatarChat)
        Spacer(Modifier.height(Spacing.l))
        Text(
            peerName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.s))
        if (state.phase == CallStateMachine.Phase.ACTIVE) {
            CallTimer(startedAt = state.startedAt)
        } else {
            val endedSuffix = if (state.phase == CallStateMachine.Phase.ENDED) {
                val res = chat.client.domain.CallEndText.suffixRes(state.endReason)
                if (res != 0) stringResource(res) else ""
            } else ""
            Text(
                stringResource(
                    when (state.phase) {
                        CallStateMachine.Phase.RINGING -> R.string.call_incoming_title
                        CallStateMachine.Phase.ENDED -> R.string.call_ended_snack
                        else -> R.string.call_connecting
                    },
                    // суффикс причины только у ENDED: у остальных фаз
                    // форматная строка его игнорирует
                    endedSuffix,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))

        if (state.phase == CallStateMachine.Phase.RINGING) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxxl),
                modifier = Modifier.padding(bottom = Spacing.xxxl),
            ) {
                CallRoundButton(
                    container = MaterialTheme.colorScheme.errorContainer,
                    desc = stringResource(R.string.call_decline),
                    tag = "call_decline",
                    onClick = onDecline,
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                CallRoundButton(
                    container = MaterialTheme.colorScheme.primary,
                    desc = stringResource(R.string.call_accept),
                    tag = "call_accept",
                    onClick = onAccept,
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = Spacing.xxxl),
            ) {
                if (state.phase != CallStateMachine.Phase.ENDED) {
                    CallRoundButton(
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        desc = stringResource(R.string.call_mute),
                        tag = "call_mute",
                        onClick = onToggleMic,
                    ) {
                        Icon(
                            if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    CallRoundButton(
                        container = MaterialTheme.colorScheme.surfaceContainerHighest,
                        desc = stringResource(R.string.call_speaker),
                        tag = "call_speaker",
                        onClick = onToggleSpeaker,
                    ) {
                        Icon(
                            if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                CallRoundButton(
                    container = MaterialTheme.colorScheme.error,
                    desc = stringResource(R.string.call_hang_up),
                    tag = "call_hangup",
                    onClick = onHangUp,
                ) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}

// таймер активного разговора
@Composable
private fun CallTimer(startedAt: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
    Text(
        "%d:%02d".format(elapsed / 60, elapsed % 60),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CallRoundButton(
    container: androidx.compose.ui.graphics.Color,
    desc: String,
    tag: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(container)
            .clickable { onClick() }
            .semantics { contentDescription = desc }
            .testTag(tag),
    ) {
        content()
    }
}
