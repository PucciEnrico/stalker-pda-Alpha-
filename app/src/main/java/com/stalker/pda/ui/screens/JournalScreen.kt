package com.stalker.pda.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stalker.pda.audio.AudioPlayer
import com.stalker.pda.audio.AudioRecorder
import com.stalker.pda.data.Attachments
import com.stalker.pda.data.model.DiaryEntry
import com.stalker.pda.data.model.DiaryEntryType
import com.stalker.pda.data.model.parseGeo
import com.stalker.pda.ui.components.BreadcrumbBar
import com.stalker.pda.ui.components.EmptyHint
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PanelHeader
import com.stalker.pda.ui.components.PdaButton
import com.stalker.pda.ui.components.PdaDialog
import com.stalker.pda.ui.components.PdaTextField
import com.stalker.pda.ui.components.PhotoView
import com.stalker.pda.ui.components.TreeCategoryRow
import com.stalker.pda.ui.components.TreeLeafRow
import com.stalker.pda.ui.components.hasKey
import com.stalker.pda.ui.components.toggleKey
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Cat(val key: String, val label: String, val type: DiaryEntryType)

private val CATS = listOf(
    Cat("text", "Особисті нотатки", DiaryEntryType.TEXT),
    Cat("audio", "Аудіозаписи", DiaryEntryType.AUDIO_LOG),
    Cat("photo", "Фото", DiaryEntryType.PHOTO),
    Cat("file", "Документи", DiaryEntryType.FILE),
    Cat("geo", "Геомітки", DiaryEntryType.GEO)
)

private val ICONS = listOf(
    "📁", "📂", "🗂", "📌", "🎯", "💀", "☢", "⚠", "🔫", "🧭", "🗺", "🏚", "📡", "🔬", "💊", "🍖", "🔧", "💰"
)

private fun folderPath(state: PdaUiState, id: String?): String {
    val parts = ArrayList<String>()
    var cur = state.folders.find { it.id == id }
    var guard = 0
    while (cur != null && guard++ < 20) {
        parts.add(0, "${cur.icon} ${cur.name}")
        cur = state.folders.find { it.id == cur?.parentId }
    }
    return parts.joinToString(" / ")
}

/** Журнал: your own folders (nested, with any content) + the type categories. */
@Composable
fun JournalScreen(state: PdaUiState, vm: PdaViewModel) {
    val ctx = LocalContext.current
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    var expanded by rememberSaveable { mutableStateOf("text|audio|photo|file|geo") }
    var draft by rememberSaveable { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordError by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0.3f) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var showFolderDialog by remember { mutableStateOf(false) }
    val recorder = remember { AudioRecorder() }
    DisposableEffect(Unit) { onDispose { recorder.release() } }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            level = (recorder.amplitude() / 12000f).coerceIn(0.05f, 1f)
            kotlinx.coroutines.delay(60)
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingPhoto
        if (ok && f != null) vm.addDiaryEntry(f.absolutePath, DiaryEntryType.PHOTO, null, "Фото") else f?.delete()
    }
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Attachments.importUri(ctx, uri)?.let { (file, name, mime) ->
                val type = if (mime.startsWith("image/")) DiaryEntryType.PHOTO else DiaryEntryType.FILE
                vm.addDiaryEntry(file.absolutePath, type, null, name)
            }
        }
    }

    val selected = state.journal.find { it.id == state.selectedJournalId }
    val targetFolder = state.folders.find { it.id == state.selectedFolderId }

    SplitPane(
        leftWeight = 0.35f,
        left = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("Журнал") {
                        Text(
                            "[⌂]", style = PdaType.Title.copy(color = PdaColors.Amber),
                            modifier = Modifier.clickable { vm.selectFolder(null) }.padding(horizontal = 4.dp)
                        )
                        Text(
                            "[+]", style = PdaType.Title.copy(color = PdaColors.Green),
                            modifier = Modifier.clickable { showFolderDialog = true }.padding(horizontal = 4.dp)
                        )
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        folderTree(state, null, 0, expanded, fmt, vm) { expanded = toggleKey(expanded, it) }
                        CATS.forEach { cat ->
                            val list = state.journal.filter { it.type == cat.type && it.folderId == null }
                            item(key = "cat_${cat.key}") {
                                TreeCategoryRow("${cat.label} (${list.size})", hasKey(expanded, cat.key)) {
                                    expanded = toggleKey(expanded, cat.key)
                                }
                            }
                            if (hasKey(expanded, cat.key)) {
                                items(list.size, key = { "e_${list[it].id}" }) { i ->
                                    val e = list[i]
                                    TreeLeafRow(leafLabel(e, fmt), e.id == state.selectedJournalId) { vm.selectJournal(e.id) }
                                }
                            }
                        }
                    }
                }
            }
        },
        right = {
            Column(Modifier.fillMaxSize()) {
                val path = when {
                    selected != null && selected.folderId != null ->
                        "/ ${folderPath(state, selected.folderId)} / ${fmt.format(Date(selected.createdAt))}"
                    selected != null ->
                        "/ ${CATS.first { it.type == selected.type }.label} / ${fmt.format(Date(selected.createdAt))}"
                    targetFolder != null -> "/ ${folderPath(state, targetFolder.id)}"
                    else -> "/ Журнал"
                }
                BreadcrumbBar(path)
                Panel(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        selected == null && targetFolder != null -> EmptyHint(
                            "Тека «${targetFolder.name}»: усе, що додаси нижче (текст, звук, фото, файл, геомітка), потрапить сюди"
                        )
                        selected == null -> EmptyHint("Оберіть запис")
                        selected.type == DiaryEntryType.AUDIO_LOG -> AudioLogPane(selected)
                        selected.type == DiaryEntryType.PHOTO -> Column(Modifier.fillMaxSize().padding(6.dp)) {
                            PhotoView(selected.content, Modifier.weight(1f).fillMaxWidth(), 1400)
                            if (selected.title.isNotBlank()) Text(selected.title, style = PdaType.Tiny)
                        }
                        selected.type == DiaryEntryType.FILE -> Column(Modifier.fillMaxSize().padding(8.dp)) {
                            Text("Документ", style = PdaType.Small.copy(color = PdaColors.Green))
                            Spacer(Modifier.height(4.dp))
                            Text(selected.title.ifBlank { File(selected.content).name }, style = PdaType.Body)
                            Spacer(Modifier.height(6.dp))
                            PdaButton("► Відкрити", PdaColors.Green, { Attachments.openFile(ctx, selected.content) })
                        }
                        selected.type == DiaryEntryType.GEO -> GeoPane(selected, vm)
                        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
                            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(selected.createdAt))
                            Text("[$stamp]", style = PdaType.Tiny)
                            Spacer(Modifier.height(4.dp))
                            Text(selected.content, style = PdaType.Body.copy(lineHeight = PdaType.Terminal.lineHeight))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Додається в: " + (if (targetFolder != null) folderPath(state, targetFolder.id) else "за типами"),
                    style = PdaType.Tiny.copy(color = PdaColors.Amber)
                )
                PdaTextField(draft, { draft = it }, "Нова нотатка (час додається автоматично)...", Modifier.fillMaxWidth().height(40.dp))
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PdaButton(
                        "► Нотатка", PdaColors.Green,
                        {
                            if (draft.isNotBlank()) {
                                vm.addDiaryEntry(draft.trim(), DiaryEntryType.TEXT, null)
                                draft = ""
                            }
                        },
                        enabled = draft.isNotBlank()
                    )
                    PdaButton(
                        if (isRecording) "■ Стоп" else "● Звук",
                        if (isRecording) PdaColors.Red else PdaColors.Amber,
                        {
                            if (!isRecording) {
                                recordError = !recorder.start(ctx)
                                isRecording = !recordError
                            } else {
                                isRecording = false
                                recorder.stop()?.let { (file, sec) -> vm.addDiaryEntry(file, DiaryEntryType.AUDIO_LOG, sec) }
                            }
                        }
                    )
                    PdaButton("📷", PdaColors.Amber, {
                        val f = Attachments.newPhotoFile(ctx)
                        pendingPhoto = f
                        camera.launch(Attachments.uriFor(ctx, f))
                    })
                    PdaButton("📎", PdaColors.Amber, { attach.launch("*/*") })
                    PdaButton("📍", PdaColors.Cyan, {
                        if (!vm.addGeoEntry()) Toast.makeText(ctx, "GPS ще не визначив позицію", Toast.LENGTH_SHORT).show()
                    })
                    if (recordError) Text("Мікрофон недоступний", style = PdaType.Tiny.copy(color = PdaColors.Red))
                }
                if (isRecording) {
                    Spacer(Modifier.height(4.dp))
                    OscilloscopeDisplay(true, Modifier.fillMaxWidth().height(34.dp), level)
                }
            }
        }
    )

    if (showFolderDialog) {
        var name by remember { mutableStateOf("") }
        var icon by remember { mutableStateOf(ICONS.first()) }
        PdaDialog(title = "Нова тека", onDismiss = { showFolderDialog = false }) {
            if (targetFolder != null) {
                Text("Усередині: ${folderPath(state, targetFolder.id)}", style = PdaType.Tiny.copy(color = PdaColors.Amber))
            }
            PdaTextField(name, { name = it }, "Назва (напр. Х-16)", Modifier.fillMaxWidth(), singleLine = true)
            Text("Іконка:", style = PdaType.Small)
            ICONS.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { ic ->
                        PdaButton(ic, if (ic == icon) PdaColors.Green else PdaColors.TextGrey, { icon = ic })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdaButton("Створити", PdaColors.Green, {
                    vm.addFolder(name, icon)
                    showFolderDialog = false
                }, enabled = name.isNotBlank())
                PdaButton("Скасувати", PdaColors.TextGrey, { showFolderDialog = false })
            }
        }
    }
}

private fun LazyListScope.folderTree(
    state: PdaUiState,
    parentId: String?,
    depth: Int,
    expanded: String,
    fmt: SimpleDateFormat,
    vm: PdaViewModel,
    onToggle: (String) -> Unit
) {
    state.folders.filter { it.parentId == parentId }.forEach { f ->
        val key = "f_${f.id}"
        val open = hasKey(expanded, key)
        item(key = key) {
            TreeCategoryRow("${f.icon} ${f.name}", open, depth) {
                vm.selectFolder(f.id)
                onToggle(key)
            }
        }
        if (open) {
            folderTree(state, f.id, depth + 1, expanded, fmt, vm, onToggle)
            val entries = state.journal.filter { it.folderId == f.id }
            items(entries.size, key = { "fe_${entries[it].id}" }) { i ->
                val e = entries[i]
                TreeLeafRow(leafLabel(e, fmt), e.id == state.selectedJournalId, depth + 1) { vm.selectJournal(e.id) }
            }
        }
    }
}

private fun leafLabel(e: DiaryEntry, fmt: SimpleDateFormat): String {
    val time = fmt.format(Date(e.createdAt))
    return when (e.type) {
        DiaryEntryType.TEXT -> "$time ${e.content.take(14)}"
        DiaryEntryType.AUDIO_LOG -> "$time аудіо ${e.durationSeconds ?: 0}с"
        DiaryEntryType.PHOTO -> "$time ${e.title.ifBlank { "фото" }.take(14)}"
        DiaryEntryType.FILE -> e.title.ifBlank { File(e.content).name }.take(22)
        DiaryEntryType.GEO -> "$time ${e.content.take(16)}"
    }
}

@Composable
private fun GeoPane(entry: DiaryEntry, vm: PdaViewModel) {
    val ctx = LocalContext.current
    val geo = parseGeo(entry.content)
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Геомітка (натисни координати, щоб скопіювати)", style = PdaType.Small.copy(color = PdaColors.Cyan))
        Text(
            entry.content,
            style = PdaType.Display.copy(color = PdaColors.TextWhite, fontSize = 18.sp),
            modifier = Modifier
                .background(PdaColors.PanelAlt)
                .border(1.dp, PdaColors.BorderBright)
                .clickable {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("coords", entry.content))
                    Toast.makeText(ctx, "Скопійовано: ${entry.content}", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        if (geo != null) PdaButton("◉ Показати на карті", PdaColors.Cyan, { vm.openMapAt(geo.lat, geo.lng, "Геомітка") })
    }
}

@Composable
private fun AudioLogPane(entry: DiaryEntry) {
    var playing by remember(entry.id) { mutableStateOf(false) }
    val player = remember { AudioPlayer() }
    DisposableEffect(entry.id) { onDispose { player.stop() } }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("Аудіозапис, ${entry.durationSeconds ?: 0} с", style = PdaType.Small.copy(color = PdaColors.Green))
        Spacer(Modifier.height(6.dp))
        OscilloscopeDisplay(playing, Modifier.fillMaxWidth().height(70.dp))
        Spacer(Modifier.height(6.dp))
        PdaButton(
            if (playing) "■ Стоп" else "► Відтворити",
            PdaColors.Green,
            {
                if (playing) {
                    player.stop()
                    playing = false
                } else {
                    playing = player.play(entry.content) { playing = false }
                }
            }
        )
    }
}
