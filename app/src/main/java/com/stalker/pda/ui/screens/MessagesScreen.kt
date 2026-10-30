package com.stalker.pda.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.stalker.pda.data.model.LogMessage
import com.stalker.pda.data.model.LogType
import com.stalker.pda.ui.components.AvatarView
import com.stalker.pda.ui.components.EmptyHint
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Історія повідомлень: everything that happened, newest first, with the avatar of the person or the task icon. */
@Composable
fun MessagesScreen(state: PdaUiState, vm: PdaViewModel) {
    LaunchedEffect(state.messages.size) { vm.markMessagesSeen() }
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }

    Panel(Modifier.fillMaxSize().padding(6.dp)) {
        if (state.messages.isEmpty()) {
            EmptyHint("Повідомлень немає")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.messages, key = { it.id }) { m ->
                    val avatar = when (m.refKind) {
                        "contact" -> state.contacts.find { it.id == m.refId }?.avatarPath
                        "task" -> state.tasks.find { it.id == m.refId }?.imageUri
                        "self" -> state.userProfile.avatarPath
                        else -> null
                    }
                    LogRow(m, fmt.format(Date(m.time)), avatar)
                }
            }
        }
    }
}

@Composable
private fun LogRow(m: LogMessage, time: String, avatarPath: String?) {
    val (label, color) = when (m.type) {
        LogType.SYSTEM -> "Система" to PdaColors.TextGrey
        LogType.NEW_TASK -> "Нове завдання:" to PdaColors.Amber
        LogType.TASK_DONE -> "Виконано:" to PdaColors.Green
        LogType.TASK_FAILED -> "Провалено:" to PdaColors.Red
        LogType.MESSAGE -> "Повідомлення" to PdaColors.Cyan
        LogType.NEARBY -> "Контакт поруч" to PdaColors.Orange
        LogType.QUEST -> "Завдання (мережа)" to PdaColors.Amber
        LogType.CALL -> "Дзвінок" to PdaColors.Cyan
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (m.refId != null) {
            AvatarView(m.refId, avatarPath, Modifier.size(width = 30.dp, height = 24.dp))
        } else {
            Box(Modifier.size(width = 30.dp, height = 24.dp).background(color.copy(alpha = 0.25f)))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, style = PdaType.Small.copy(color = color))
                Text(time, style = PdaType.Tiny.copy(color = Color(0xFF8A8D96)))
            }
            Text(m.text, style = PdaType.Body)
        }
    }
}
