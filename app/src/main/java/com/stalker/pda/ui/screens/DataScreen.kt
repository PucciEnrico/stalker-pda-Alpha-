package com.stalker.pda.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stalker.pda.data.model.ContactType
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PanelHeader
import com.stalker.pda.ui.components.PdaButton
import com.stalker.pda.ui.components.PdaDialog
import com.stalker.pda.ui.components.PdaTextField
import com.stalker.pda.ui.components.AvatarView
import com.stalker.pda.data.Attachments
import com.stalker.pda.data.SampleData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.stalker.pda.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Дані: player card + statistics (left), friends with "date added" (right). */
@Composable
fun DataScreen(state: PdaUiState, vm: PdaViewModel) {
    val p = state.userProfile
    var editing by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmImport by remember { mutableStateOf(false) }
    var restartNeeded by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Backup.export(ctx, uri) }
            Toast.makeText(ctx, if (ok) "Копію збережено" else "Не вдалося зберегти копію", Toast.LENGTH_LONG).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { Backup.stage(ctx, uri) }
            if (ok) restartNeeded = true
            else Toast.makeText(ctx, "Це не резервна копія ПДА", Toast.LENGTH_LONG).show()
        }
    }
    var pendingAvatar by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingAvatar
        if (ok && f != null) vm.setAvatar(f.absolutePath)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Attachments.importUri(ctx, uri)?.let { (f, _, _) -> vm.setAvatar(f.absolutePath) }
    }
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val friends = state.contacts.filter { it.type == ContactType.FRIEND }

    SplitPane(
        leftWeight = 0.35f,
        left = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PanelHeader(p.callsign) {
                        Text(
                            "[✎]",
                            style = PdaType.Title.copy(color = PdaColors.Amber),
                            modifier = Modifier.clickable { editing = true }.padding(horizontal = 4.dp)
                        )
                    }
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        state.nickConflict?.let { taken ->
                            Text(
                                "Нік «$taken» уже зайнятий у мережі серверів. Натисни [✎] і обери інший.",
                                style = PdaType.Small.copy(color = PdaColors.Red)
                            )
                        }
                        Row {
                            AvatarView("self", p.avatarPath, Modifier.size(width = 64.dp, height = 52.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("ранг", style = PdaType.Tiny.copy(color = PdaColors.TextDim))
                                Text(p.rank.label, style = PdaType.Small.copy(color = PdaColors.TextWhite))
                                Text("група", style = PdaType.Tiny.copy(color = PdaColors.TextDim))
                                Text(p.faction, style = PdaType.Small.copy(color = PdaColors.TextWhite))
                                Text("репутація", style = PdaType.Tiny.copy(color = PdaColors.TextDim))
                                Text(p.reputation, style = PdaType.Small.copy(color = PdaColors.TextWhite))
                            }
                        }
                        if (p.motto.isNotBlank()) {
                            Text("«${p.motto}»", style = PdaType.Small.copy(color = PdaColors.Amber))
                        }
                        Text("Статистика", style = PdaType.Title)
                        StatLine("Виконані завдання", "${p.completedTasks}")
                        StatLine("Провалені завдання", "${p.failedTasks}")
                        StatLine("Успішність", "%.0f%%".format(p.taskSuccessRate * 100))
                        StatLine("Пройдено пішки", "%.2f км".format(p.kmWalked))
                        StatLine("Унікальні зустрічі", "${p.uniqueEncounters}")
                        StatLine("Усього XP", "${p.totalXp}")

                        Text("Резервна копія", style = PdaType.Title)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PdaButton("⬆ Експорт", PdaColors.Green, {
                                exportLauncher.launch("pda-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date()) + ".zip")
                            })
                            PdaButton("⬇ Імпорт", PdaColors.Amber, { confirmImport = true })
                        }
                        Text(
                            "У копії твій ID, ключ сервера, друзі, завдання, журнал і файли. Зберігай її приватно.",
                            style = PdaType.Tiny.copy(color = PdaColors.TextDim)
                        )
                    }
                }
            }
        },
        right = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("Друзі: дата додавання")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text("##", style = PdaType.Tiny, modifier = Modifier.width(24.dp))
                        Text("Позивний", style = PdaType.Tiny, modifier = Modifier.weight(1f))
                        Text("Додано", style = PdaType.Tiny)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(friends.size) { i ->
                            val f = friends[i]
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                                Text("$i", style = PdaType.Small, modifier = Modifier.width(24.dp))
                                Text(f.callsign, style = PdaType.Body, modifier = Modifier.weight(1f))
                                Text(dateFmt.format(Date(f.addedAt)), style = PdaType.Small.copy(color = PdaColors.TextWhite))
                            }
                        }
                    }
                }
            }
        }
    )

    if (confirmImport) {
        PdaDialog(title = "Імпорт даних", onDismiss = { confirmImport = false }) {
            Text(
                "Усі поточні дані на цьому телефоні буде замінено даними з копії (разом з ID і ніком із копії). Продовжити?",
                style = PdaType.Small.copy(color = PdaColors.TextWhite)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdaButton("Обрати файл", PdaColors.Amber, {
                    confirmImport = false
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                })
                PdaButton("Скасувати", PdaColors.TextGrey, { confirmImport = false })
            }
        }
    }
    if (restartNeeded) {
        PdaDialog(title = "Імпорт готовий", onDismiss = {}) {
            Text(
                "Дані підготовлено. ПДА перезапуститься, щоб їх застосувати. Якщо він не відкрився сам, відкрий його вручну.",
                style = PdaType.Small.copy(color = PdaColors.TextWhite)
            )
            PdaButton("Перезапустити", PdaColors.Green, { Backup.restartApp(ctx) })
        }
    }

    if (editing) {
        var name by remember { mutableStateOf(p.callsign) }
        var faction by remember { mutableStateOf(p.faction) }
        var motto by remember { mutableStateOf(p.motto) }
        PdaDialog(title = "Профіль", onDismiss = { editing = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                AvatarView("self", p.avatarPath, Modifier.size(width = 64.dp, height = 52.dp))
                PdaButton("📷 Фото", PdaColors.Amber, {
                    val f = Attachments.newPhotoFile(ctx)
                    pendingAvatar = f
                    camera.launch(Attachments.uriFor(ctx, f))
                })
                PdaButton("📎 Галерея", PdaColors.Amber, { gallery.launch("image/*") })
                if (p.avatarPath != null) PdaButton("✕", PdaColors.Red, { vm.removeAvatar() })
            }
            PdaTextField(name, { name = it }, "Позивний", Modifier.fillMaxWidth(), singleLine = true)
            PdaTextField(faction, { faction = it }, "Угруповання", Modifier.fillMaxWidth(), singleLine = true)
            SampleData.FACTIONS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { f -> PdaButton(f, PdaColors.Cyan, { faction = f }) }
                }
            }
            PdaTextField(motto, { motto = it }, "Девіз / статус", Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdaButton("Зберегти", PdaColors.Green, {
                    vm.setProfile(name, faction, motto)
                    editing = false
                }, enabled = name.isNotBlank())
                PdaButton("Скасувати", PdaColors.TextGrey, { editing = false })
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = PdaType.Small)
        Text(value, style = PdaType.Small.copy(color = PdaColors.TextWhite))
    }
}
