package com.stalker.pda.ui.screens

import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stalker.pda.audio.AudioPlayer
import com.stalker.pda.audio.AudioRecorder
import com.stalker.pda.data.Attachments
import com.stalker.pda.data.model.Contact
import com.stalker.pda.data.model.ContactOnlineStatus
import com.stalker.pda.data.model.ContactType
import com.stalker.pda.data.model.GeoTag
import com.stalker.pda.data.model.SubTask
import com.stalker.pda.data.model.Task
import com.stalker.pda.data.model.TaskStatus
import com.stalker.pda.data.model.targetGeo
import com.stalker.pda.ui.components.AvatarView
import com.stalker.pda.ui.components.EmptyHint
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PanelHeader
import com.stalker.pda.ui.components.PdaButton
import com.stalker.pda.ui.components.PdaDialog
import com.stalker.pda.ui.components.PdaTextField
import com.stalker.pda.ui.components.PhotoDialog
import com.stalker.pda.ui.components.PhotoView
import com.stalker.pda.ui.components.tapAndDouble
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import com.stalker.pda.viewmodel.StageDraft
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun SubTask.hasDetails() = description.isNotBlank() || audioPath != null || photoPath != null

/** Left: task list. Right: the selected task (description, stages, files) - no map here. */
@Composable
fun TasksScreen(state: PdaUiState, vm: PdaViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var assignFor by remember { mutableStateOf<Task?>(null) }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var stageSel by rememberSaveable { mutableStateOf("") } // "taskId|stageId"
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) { onDispose { player.stop() } }

    val visible = state.tasks.filter { it.status == state.taskFilter }
    val selected = state.tasks.find { it.id == state.selectedTaskId }
    val selectedStageId = if (selected != null && stageSel.startsWith(selected.id + "|")) stageSel.substringAfter("|") else null
    val listState = rememberLazyListState()

    val toggleAudio: (String) -> Unit = { path ->
        if (playingPath == path) {
            player.stop()
            playingPath = null
        } else if (player.play(path) { playingPath = null }) {
            playingPath = path
        }
    }

    SplitPane(
        leftWeight = 0.38f,
        left = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("Завдання") {
                        Text(
                            "[+]",
                            style = PdaType.Title.copy(color = PdaColors.Green),
                            modifier = Modifier.clickable { showNew = true }.padding(horizontal = 4.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf(
                            TaskStatus.ACTIVE to "Активні",
                            TaskStatus.DONE to "Виконані",
                            TaskStatus.FAILED to "Провалені"
                        ).forEach { (status, label) ->
                            Text(
                                label,
                                style = PdaType.Small.copy(
                                    color = if (state.taskFilter == status) PdaColors.Amber else PdaColors.TextGrey
                                ),
                                modifier = Modifier.clickable { vm.setTaskFilter(status) }
                            )
                        }
                    }
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Список порожній", style = PdaType.Small.copy(color = PdaColors.TextDim))
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(visible, key = { it.id }) { task ->
                                TaskRow(
                                    task = task,
                                    selected = task.id == state.selectedTaskId,
                                    playingPath = playingPath,
                                    onTap = {
                                        stageSel = ""
                                        vm.selectTask(task.id)
                                    },
                                    onDouble = {
                                        vm.selectTask(task.id)
                                        vm.focusTaskOnMap(task)
                                    },
                                    onStage = { sub ->
                                        vm.selectTask(task.id)
                                        stageSel = task.id + "|" + sub.id
                                        vm.selectStage(task.id, sub.id)
                                    },
                                    onGeo = { sub -> sub.geoTag?.let { vm.openMapAt(it.lat, it.lng, sub.title) } },
                                    onPhoto = { photoPath = it },
                                    onAudio = toggleAudio
                                )
                            }
                        }
                    }
                }
            }
        },
        right = {
            if (selected == null) {
                Panel(Modifier.fillMaxSize()) { EmptyHint("Оберіть завдання зі списку") }
            } else {
                TaskDetailPane(
                    task = selected,
                    stageId = selectedStageId,
                    playingPath = playingPath,
                    vm = vm,
                    onStage = { id -> stageSel = if (id == null) "" else selected.id + "|" + id },
                    onAudio = toggleAudio,
                    onPhoto = { photoPath = it },
                    onAssign = { assignFor = selected }
                )
            }
        }
    )

    if (showNew) {
        NewTaskDialog(
            selfLoc = state.selfLocation,
            onDismiss = { showNew = false },
            onCreate = { title, desc, geo, icon, audio, deadline, stages ->
                vm.addTask(title, desc, geo, icon, audio, deadline, stages)
                showNew = false
            }
        )
    }
    assignFor?.let { task ->
        AssignDialog(
            task = task,
            friends = state.contacts.filter { it.type == ContactType.FRIEND },
            onPick = { id ->
                vm.assignTask(task.id, id)
                assignFor = null
            },
            onDismiss = { assignFor = null }
        )
    }
    photoPath?.let { PhotoDialog(it) { photoPath = null } }
}

// ───────────── left list (kept as it was) ─────────────

@Composable
private fun TaskRow(
    task: Task,
    selected: Boolean,
    playingPath: String?,
    onTap: () -> Unit,
    onDouble: () -> Unit,
    onStage: (SubTask) -> Unit,
    onGeo: (SubTask) -> Unit,
    onPhoto: (String) -> Unit,
    onAudio: (String) -> Unit
) {
    val fmt = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
    val currentSubId = task.subTasks.firstOrNull { !it.isDone }?.id

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) PdaColors.SelectBar else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().tapAndDouble(onTap, onDouble),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarView(task.id, task.imageUri, Modifier.size(width = 34.dp, height = 26.dp))
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = PdaType.Body.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fmt.format(Date(task.createdAt)), style = PdaType.Tiny)
                if (task.fromName != null) Text("від ${task.fromName}", style = PdaType.Tiny.copy(color = PdaColors.Amber))
            }
        }
        task.subTasks.forEach { sub ->
            val color = when {
                sub.isDone -> PdaColors.TextDim
                sub.id == currentSubId -> PdaColors.Green
                else -> PdaColors.TextGrey
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).clickable { onStage(sub) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (sub.isDone) "●" else if (sub.id == currentSubId) "◉" else "○", style = PdaType.Small.copy(color = color))
                    Spacer(Modifier.width(4.dp))
                    Text(sub.title, style = PdaType.Small.copy(color = color), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (sub.geoTag != null) {
                    Text("◎", style = PdaType.Body.copy(color = PdaColors.Cyan), modifier = Modifier.clickable { onGeo(sub) }.padding(horizontal = 4.dp))
                }
                sub.audioPath?.let { path ->
                    Text(
                        if (playingPath == path) "■" else "♪", style = PdaType.Body.copy(color = PdaColors.Amber),
                        modifier = Modifier.clickable { onAudio(path) }.padding(horizontal = 4.dp)
                    )
                }
                sub.photoPath?.let { path ->
                    Text("▣", style = PdaType.Body.copy(color = PdaColors.Green), modifier = Modifier.clickable { onPhoto(path) }.padding(horizontal = 4.dp))
                }
            }
        }
        task.geoTag?.let { geo ->
            Text("◉ ${geo.label.ifBlank { "геомітка" }}", style = PdaType.Tiny.copy(color = PdaColors.Cyan))
        }
    }
}

// ───────────── right: task menu ─────────────

@Composable
private fun DeadlineText(deadline: Long) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            now = System.currentTimeMillis()
        }
    }
    val left = deadline - now
    if (left <= 0) {
        Text("Строк вичерпано", style = PdaType.Small.copy(color = PdaColors.Red))
    } else {
        val mins = left / 60_000
        val text = when {
            mins >= 1440 -> "${mins / 1440} д ${(mins % 1440) / 60} г"
            mins >= 60 -> "${mins / 60} г ${mins % 60} хв"
            else -> "$mins хв"
        }
        Text(
            "До ${fmt.format(Date(deadline))} · залишилось $text",
            style = PdaType.Small.copy(color = if (left < 3_600_000) PdaColors.Red else PdaColors.Amber)
        )
    }
}

@Composable
private fun GeoChip(geo: GeoTag, label: String, vm: PdaViewModel) {
    Text(
        "◎ ${"%.5f, %.5f".format(Locale.US, geo.lat, geo.lng)}  (1 тап: ціль, 2 тапи: карта)",
        style = PdaType.Small.copy(color = PdaColors.Cyan),
        modifier = Modifier
            .tapAndDouble(
                onTap = { vm.setTarget(geo.lat, geo.lng, label) },
                onDouble = { vm.openMapAt(geo.lat, geo.lng, label) }
            )
            .padding(vertical = 3.dp)
    )
}

@Composable
private fun FilesRow(audio: String?, photo: String?, playingPath: String?, onAudio: (String) -> Unit, onPhoto: (String) -> Unit) {
    if (audio == null && photo == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        photo?.let { p ->
            PhotoView(p, Modifier.size(width = 96.dp, height = 72.dp).clickable { onPhoto(p) }, 400)
        }
        audio?.let { a ->
            PdaButton(if (playingPath == a) "■ Стоп" else "♪ Аудіо", PdaColors.Amber, { onAudio(a) })
        }
    }
}

@Composable
private fun TaskDetailPane(
    task: Task,
    stageId: String?,
    playingPath: String?,
    vm: PdaViewModel,
    onStage: (String?) -> Unit,
    onAudio: (String) -> Unit,
    onPhoto: (String) -> Unit,
    onAssign: () -> Unit
) {
    val stage = task.subTasks.find { it.id == stageId }
    val collapseTask = stage != null && stage.hasDetails()
    val active = task.status == TaskStatus.ACTIVE

    Column(Modifier.fillMaxSize()) {
        PanelHeader(task.title)
        Panel(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AvatarView(
                        task.id, task.imageUri,
                        Modifier.size(width = 72.dp, height = 56.dp).clickable { task.imageUri?.let(onPhoto) }
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        task.deadline?.let { DeadlineText(it) }
                        if (task.fromName != null) Text("Від: ${task.fromName}", style = PdaType.Small.copy(color = PdaColors.Amber))
                        Text("Нагорода: +${task.xpReward} XP", style = PdaType.Tiny.copy(color = PdaColors.Amber))
                        Text(
                            when (task.status) {
                                TaskStatus.ACTIVE -> "Статус: активне"
                                TaskStatus.DONE -> "Статус: виконано"
                                TaskStatus.FAILED -> "Статус: провалено"
                            },
                            style = PdaType.Tiny
                        )
                    }
                }

                if (collapseTask) {
                    Text(
                        "▸ Опис завдання (натисни, щоб розгорнути)",
                        style = PdaType.Small.copy(color = PdaColors.Green),
                        modifier = Modifier.clickable { onStage(null) }
                    )
                } else {
                    if (task.description.isNotBlank()) Text(task.description, style = PdaType.Body.copy(lineHeight = PdaType.Terminal.lineHeight))
                    FilesRow(task.audioPath, null, playingPath, onAudio, onPhoto)
                    task.geoTag?.let { GeoChip(it, task.title, vm) }
                }

                if (task.subTasks.isNotEmpty()) {
                    Text("Етапи", style = PdaType.Title)
                    task.subTasks.forEachIndexed { i, st ->
                        val isSel = st.id == stageId
                        val previousDone = task.subTasks.take(i).all { it.isDone }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSel) PdaColors.SelectBar else Color.Transparent)
                                .padding(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().tapAndDouble(
                                    onTap = {
                                        onStage(st.id)
                                        vm.selectStage(task.id, st.id)
                                    },
                                    onDouble = {
                                        onStage(st.id)
                                        st.geoTag?.let { vm.openMapAt(it.lat, it.lng, st.title) }
                                    }
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (st.isDone) "●" else if (previousDone) "◉" else "○",
                                    style = PdaType.Body.copy(color = if (st.isDone) PdaColors.TextDim else if (previousDone) PdaColors.Green else PdaColors.TextGrey)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${i + 1}. ${st.title}",
                                    style = PdaType.Body.copy(color = if (st.isDone) PdaColors.TextDim else PdaColors.TextWhite),
                                    modifier = Modifier.weight(1f)
                                )
                                if (st.geoTag != null) Text("◎", style = PdaType.Body.copy(color = PdaColors.Cyan))
                                if (st.audioPath != null) Text(" ♪", style = PdaType.Body.copy(color = PdaColors.Amber))
                                if (st.photoPath != null) Text(" ▣", style = PdaType.Body.copy(color = PdaColors.Green))
                            }
                            if (isSel) {
                                Column(Modifier.padding(start = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (st.description.isNotBlank()) Text(st.description, style = PdaType.Small.copy(color = PdaColors.TextWhite))
                                    FilesRow(st.audioPath, st.photoPath, playingPath, onAudio, onPhoto)
                                    st.geoTag?.let { GeoChip(it, st.title, vm) }
                                    if (active && !st.isDone) {
                                        if (previousDone) {
                                            PdaButton("✓ ВИКОНАНО ЕТАП", PdaColors.Green, { vm.completeStage(task.id, st.id) })
                                        } else {
                                            Text("Спершу виконай попередні етапи", style = PdaType.Tiny.copy(color = PdaColors.TextDim))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (active) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PdaButton("✓ ВИКОНАНО", PdaColors.Green, { vm.completeTask(task.id) })
                PdaButton("✗ ПРОВАЛЕНО", PdaColors.Red, { vm.failTask(task.id) })
                PdaButton("→ ДРУГУ", PdaColors.Amber, onAssign)
                if (task.targetGeo() != null) PdaButton("◉ НА КАРТІ", PdaColors.Cyan, { vm.focusTaskOnMap(task) })
            }
        }
    }
}

@Composable
private fun AssignDialog(task: Task, friends: List<Contact>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    PdaDialog(title = "Надіслати завдання другу", onDismiss = onDismiss) {
        Text(task.title, style = PdaType.Title.copy(color = PdaColors.Amber))
        if (friends.isEmpty()) Text("Друзів немає. Додай друга у вкладці «Контакти».", style = PdaType.Small)
        friends.forEach { f ->
            val online = f.onlineStatus != ContactOnlineStatus.OFFLINE
            PdaButton(
                "${f.callsign}: ${if (online) "у мережі" else "не в мережі"}",
                PdaColors.Green, { onPick(f.id) }, Modifier.fillMaxWidth(), enabled = online
            )
        }
        PdaButton("Скасувати", PdaColors.TextGrey, onDismiss)
    }
}

// ───────────── new task ─────────────

private val DEADLINES = listOf<Pair<Int?, String>>(
    null to "без терміну", 30 to "30 хв", 60 to "1 год", 360 to "6 год",
    1440 to "1 доба", 4320 to "3 доби", 10080 to "7 діб"
)

@Composable
private fun NewTaskDialog(
    selfLoc: Location?,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String?, String?, Long?, List<StageDraft>) -> Unit
) {
    val ctx = LocalContext.current
    var main by remember { mutableStateOf(StageDraft("main")) }
    var deadlineMin by remember { mutableStateOf<Int?>(null) }
    val stages = remember { mutableStateListOf<StageDraft>() }
    val recorder = remember { AudioRecorder() }
    var recKey by remember { mutableStateOf<String?>(null) }
    var photoCb by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { onDispose { recorder.release() } }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingFile
        if (ok && f != null) photoCb?.invoke(f.absolutePath)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Attachments.importUri(ctx, uri)?.let { (f, _, _) -> photoCb?.invoke(f.absolutePath) }
    }
    val here = selfLoc?.let { "%.5f, %.5f".format(Locale.US, it.latitude, it.longitude) }

    val toggleRec: (String, (String) -> Unit) -> Unit = { key, done ->
        if (recKey == key) {
            recorder.stop()?.let { (p, _) -> done(p) }
            recKey = null
        } else if (recKey == null && recorder.start(ctx)) {
            recKey = key
        }
    }
    val pickPhoto: (Boolean, (String) -> Unit) -> Unit = { useCamera, done ->
        photoCb = done
        if (useCamera) {
            val f = Attachments.newPhotoFile(ctx)
            pendingFile = f
            camera.launch(Attachments.uriFor(ctx, f))
        } else {
            gallery.launch("image/*")
        }
    }

    PdaDialog(title = "Нове завдання", onDismiss = onDismiss) {
        DraftEditor(main, { main = it }, "Назва завдання", "іконка", here, "main", recKey, toggleRec, pickPhoto)

        Text("Строк виконання (після нього завдання провалюється саме)", style = PdaType.Small)
        DEADLINES.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (min, label) ->
                    PdaButton(label, if (deadlineMin == min) PdaColors.Amber else PdaColors.TextGrey, { deadlineMin = min })
                }
            }
        }

        Text("Етапи (виконуються по черзі)", style = PdaType.Title)
        stages.forEachIndexed { i, st ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PdaColors.PanelAlt)
                    .border(1.dp, PdaColors.Border)
                    .padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DraftEditor(st, { stages[i] = it }, "Етап ${i + 1}", "фото", here, st.id, recKey, toggleRec, pickPhoto)
                PdaButton("✕ Видалити етап", PdaColors.Red, {
                    if (recKey == st.id) {
                        recorder.release()
                        recKey = null
                    }
                    stages.removeAt(i)
                })
            }
        }
        PdaButton("+ Додати етап", PdaColors.Amber, { stages.add(StageDraft(UUID.randomUUID().toString())) })

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PdaButton(
                "Створити", PdaColors.Green,
                {
                    val deadline = deadlineMin?.let { System.currentTimeMillis() + it * 60_000L }
                    onCreate(main.title, main.description, main.geoText, main.photoPath, main.audioPath, deadline, stages.toList())
                },
                enabled = main.title.isNotBlank()
            )
            PdaButton("Скасувати", PdaColors.TextGrey, onDismiss)
        }
    }
}

/** Title, description, geotag (typed or one-tap "I am here"), audio and photo - same for a task and for a stage. */
@Composable
private fun DraftEditor(
    d: StageDraft,
    onChange: (StageDraft) -> Unit,
    titleHint: String,
    photoLabel: String,
    here: String?,
    key: String,
    recKey: String?,
    onRec: (String, (String) -> Unit) -> Unit,
    onPhoto: (Boolean, (String) -> Unit) -> Unit
) {
    PdaTextField(d.title, { onChange(d.copy(title = it)) }, titleHint, Modifier.fillMaxWidth(), singleLine = true)
    PdaTextField(d.description, { onChange(d.copy(description = it)) }, "Опис", Modifier.fillMaxWidth().height(44.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        PdaTextField(d.geoText, { onChange(d.copy(geoText = it)) }, "Геотег: lat, lng", Modifier.weight(1f), singleLine = true)
        PdaButton("◉ Я тут", PdaColors.Cyan, { onChange(d.copy(geoText = here ?: d.geoText)) }, enabled = here != null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        val recording = recKey == key
        PdaButton(
            text = if (recording) "■ Стоп" else if (d.audioPath != null) "● Перезаписати" else "● Аудіо",
            color = if (recording) PdaColors.Red else PdaColors.Amber,
            onClick = { onRec(key) { path -> onChange(d.copy(audioPath = path)) } }
        )
        PdaButton("📷 $photoLabel", PdaColors.Amber, { onPhoto(true) { path -> onChange(d.copy(photoPath = path)) } })
        PdaButton("📎", PdaColors.Amber, { onPhoto(false) { path -> onChange(d.copy(photoPath = path)) } })
        if (d.audioPath != null) Text("♪", style = PdaType.Body.copy(color = PdaColors.Green))
        d.photoPath?.let { PhotoView(it, Modifier.size(width = 40.dp, height = 30.dp), 200) }
    }
}
