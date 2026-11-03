package com.stalker.pda.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.stalker.pda.R
import com.stalker.pda.data.model.CallPhase
import com.stalker.pda.data.model.CallState
import com.stalker.pda.data.model.IncomingQuest
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaViewModel
import kotlinx.coroutines.delay

private val modal = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false
)

/** Incoming / outgoing / active voice call (local network). */
@Composable
fun CallOverlay(call: CallState, avatarPath: String?, vm: PdaViewModel) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var speaker by remember { mutableStateOf(false) }

    LaunchedEffect(call.phase) {
        while (true) {
            now = System.currentTimeMillis()
            if (call.phase != CallPhase.ACTIVE) playSound(ctx, R.raw.beep_pda) // ringing
            delay(if (call.phase == CallPhase.ACTIVE) 1000L else 1500L)
        }
    }

    Dialog(onDismissRequest = {}, properties = modal) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .background(PdaColors.PanelBg)
                .border(1.dp, PdaColors.BorderBright)
        ) {
            PanelHeader("Дзвінок")
            Column(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AvatarView(call.peerId, avatarPath, Modifier.size(width = 64.dp, height = 52.dp))
                Text(call.peerName, style = PdaType.Display)
                val label = when (call.phase) {
                    CallPhase.INCOMING -> "Вхідний дзвінок..."
                    CallPhase.OUTGOING -> "Виклик..."
                    CallPhase.ACTIVE -> {
                        val secs = ((now - call.startedAt) / 1000).coerceAtLeast(0)
                        "Розмова %d:%02d".format(secs / 60, secs % 60)
                    }
                }
                Text(label, style = PdaType.Small.copy(color = PdaColors.Amber))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (call.phase) {
                        CallPhase.INCOMING -> {
                            PdaButton("✆ ПРИЙНЯТИ", PdaColors.Green, { vm.acceptCall() })
                            PdaButton("✖ ВІДХИЛИТИ", PdaColors.Red, { vm.declineCall() })
                        }
                        CallPhase.OUTGOING -> PdaButton("✖ СКИНУТИ", PdaColors.Red, { vm.endCall("скасовано") })
                        CallPhase.ACTIVE -> {
                            PdaButton(
                                if (speaker) "Гучний" else "Вухо", PdaColors.Cyan,
                                {
                                    speaker = !speaker
                                    vm.setSpeaker(speaker)
                                }
                            )
                            PdaButton("✖ ЗАВЕРШИТИ", PdaColors.Red, { vm.endCall() })
                        }
                    }
                }
            }
        }
    }
}

/** A friend offers you a quest. */
@Composable
fun IncomingQuestDialog(q: IncomingQuest, vm: PdaViewModel) {
    Dialog(onDismissRequest = {}, properties = modal) {
        Column(
            modifier = Modifier
                .width(380.dp)
                .background(PdaColors.PanelBg)
                .border(1.dp, PdaColors.BorderBright)
        ) {
            PanelHeader("Нове завдання від ${q.fromName}")
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(q.task.title, style = PdaType.Title.copy(color = PdaColors.Amber))
                Text(
                    q.task.description.ifBlank { "Без опису" },
                    style = PdaType.Small.copy(color = PdaColors.TextWhite),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Text("Етапів: ${q.task.subTasks.size}", style = PdaType.Tiny)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdaButton("✓ ПРИЙНЯТИ", PdaColors.Green, { vm.acceptQuest() })
                    PdaButton("✗ ВІДХИЛИТИ", PdaColors.Red, { vm.declineQuest() })
                }
            }
        }
    }
}
