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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stalker.pda.audio.AudioPlayer
import com.stalker.pda.audio.AudioRecorder
import com.stalker.pda.data.Attachments
import com.stalker.pda.data.model.ChatMessage
import com.stalker.pda.data.model.Contact
import com.stalker.pda.data.model.ContactOnlineStatus
import com.stalker.pda.data.model.ContactPrefs
import com.stalker.pda.data.model.ContactType
import com.stalker.pda.data.model.StalkerRank
import com.stalker.pda.net.LanNode
import com.stalker.pda.ui.components.AvatarView
import com.stalker.pda.ui.components.EmptyHint
import com.stalker.pda.ui.components.Panel
import com.stalker.pda.ui.components.PanelHeader
import com.stalker.pda.ui.components.PdaButton
import com.stalker.pda.ui.components.PdaDialog
import com.stalker.pda.ui.components.PdaTextField
import com.stalker.pda.ui.components.PhotoDialog
import com.stalker.pda.ui.components.PhotoView
import com.stalker.pda.ui.theme.PdaColors
import com.stalker.pda.ui.theme.PdaType
import com.stalker.pda.viewmodel.PdaUiState
import com.stalker.pda.viewmodel.PdaViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Контакти: people on the network (left); chat / profile / privacy (right). */
@Composable
fun ContactsScreen(state: PdaUiState, vm: PdaViewModel) {
    var filter by rememberSaveable { mutableStateOf(0) } // 0 = all, 1 = friends, 2 = strangers
    var profileFor by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val shown = when (filter) {
        1 -> state.contacts.filter { it.type == ContactType.FRIEND }
        2 -> state.contacts.filter { it.type == ContactType.STRANGER }
        else -> state.contacts
    }
    val selected = state.contacts.find { it.id == state.selectedContactId }

    SplitPane(
        leftWeight = 0.4f,
        left = {
            Panel(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    PanelHeader("Контакти") {
                        Text(
                            if (state.lanActive) "LAN●" else "LAN✕",
                            style = PdaType.Tiny.copy(color = if (state.lanActive) PdaColors.Green else PdaColors.Red)
                        )
                        Text(
                            "  [⚙]",
                            style = PdaType.Title.copy(color = PdaColors.Amber),
                            modifier = Modifier.clickable { vm.clearSelection() }.padding(horizontal = 2.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Усі", "Друзі", "Сторонні").forEachIndexed { i, label ->
                            Text(
                                label,
                                style = PdaType.Small.copy(color = if (filter == i) PdaColors.Amber else PdaColors.TextGrey),
                                modifier = Modifier.clickable { filter = i }
                            )
                        }
                    }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Нікого не видно.\nПідключи телефони до однієї Wi-Fi мережі й відкрий ПДА на обох.",
                                style = PdaType.Tiny.copy(color = PdaColors.TextDim)
                            )
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(shown, key = { it.id }) { c ->
                                ContactRow(
                                    c, c.id == state.selectedContactId, c.id in state.nearbyContactIds,
                                    state.prefs[c.id]?.blocked == true
                                ) {
                                    profileFor = ""
                                    vm.selectContact(c.id)
                                }
                            }
                        }
                    }
                }
            }
        },
        right = {
            when {
                selected == null -> PrivacyPane(state, vm)
                profileFor == selected.id -> ContactProfilePane(state, vm, selected) { profileFor = "" }
                else -> ChatPane(state, vm, selected) { profileFor = selected.id }
            }
        }
    )
}

@Composable
private fun ContactRow(contact: Contact, selected: Boolean, nearby: Boolean, blocked: Boolean, onClick: () -> Unit) {
    val statusColor = when {
        blocked -> PdaColors.Red
        nearby -> PdaColors.Amber
        contact.onlineStatus == ContactOnlineStatus.OFFLINE -> PdaColors.TextDim
        else -> PdaColors.Green
    }
    val status = when {
        blocked -> "⛔ заблокований"
        nearby -> "◉ ПОРУЧ (менше 100 м)"
        contact.onlineStatus == ContactOnlineStatus.OFFLINE -> "○ не в мережі"
        else -> "● у мережі"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    selected -> PdaColors.SelectBar
                    nearby -> PdaColors.Amber.copy(alpha = 0.10f)
                    else -> Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarView(contact.id, contact.avatarPath, Modifier.size(width = 30.dp, height = 24.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.callsign,
                style = PdaType.Body.copy(color = if (contact.type == ContactType.FRIEND) PdaColors.TextWhite else PdaColors.Orange),
                maxLines = 1
            )
            Text(status, style = PdaType.Tiny.copy(color = statusColor))
        }
    }
}

// ───────────── chat ─────────────

@Composable
private fun ChatPane(state: PdaUiState, vm: PdaViewModel, contact: Contact, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    var input by remember(contact.id) { mutableStateOf("") }
    var photoPath by remember { mutableStateOf<String?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    val recorder = remember { AudioRecorder() }
    val player = remember { AudioPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            recorder.release()
            player.stop()
        }
    }
    val messages = state.chats.filter { it.contactId == contact.id }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isFriend = contact.type == ContactType.FRIEND
    val online = contact.onlineStatus != ContactOnlineStatus.OFFLINE
    val blocked = state.prefs[contact.id]?.blocked == true
    val canChat = (isFriend || contact.isOpenChannel) && !blocked
    val mediaOk = isFriend && online && !blocked

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingFile
        if (ok && f != null) vm.sendMedia(contact.id, "photo", f.absolutePath, f.name)
    }
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Attachments.importUri(ctx, uri)?.let { (file, name, mime) ->
                val kind = when {
                    mime.startsWith("image/") -> "photo"
                    mime.startsWith("audio/") -> "audio"
                    else -> "file"
                }
                vm.sendMedia(contact.id, kind, file.absolutePath, name)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        PanelHeader("Термінал: ${contact.callsign}")
        Row(
            modifier = Modifier.fillMaxWidth().background(PdaColors.PanelAlt).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarView(
                contact.id, contact.avatarPath,
                Modifier.size(width = 38.dp, height = 30.dp).clickable(onClick = onProfile)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).clickable(onClick = onProfile)) {
                Text(contact.callsign, style = PdaType.Body.copy(color = PdaColors.Amber))
                Text(
                    "${if (isFriend) "друг" else "сторонній"} · ${contact.faction.ifBlank { "—" }}  (натисни: профіль)",
                    style = PdaType.Tiny
                )
            }
            PdaButton("☎ ДЗВІНОК", PdaColors.Green, { vm.startCall(contact.id) }, enabled = isFriend && online && !blocked && state.call == null)
        }
        Panel(Modifier.weight(1f).fillMaxWidth()) {
            when {
                blocked -> EmptyHint("Контакт заблоковано")
                !canChat -> EmptyHint("Канал закритий: ${contact.callsign} не приймає повідомлень")
                messages.isEmpty() -> EmptyHint(if (online) "Повідомлень немає" else "Не в мережі")
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages, key = { it.id }) { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (m.fromSelf) Arrangement.End else Arrangement.Start
                        ) {
                            Column(horizontalAlignment = if (m.fromSelf) Alignment.End else Alignment.Start) {
                                Text(
                                    if (m.fromSelf) state.userProfile.callsign else contact.callsign,
                                    style = PdaType.Tiny.copy(color = if (m.fromSelf) PdaColors.Green else PdaColors.Amber)
                                )
                                Bubble(
                                    m = m,
                                    playing = playingId == m.id,
                                    onPhoto = { photoPath = it },
                                    onAudio = {
                                        if (playingId == m.id) {
                                            player.stop()
                                            playingId = null
                                        } else if (m.path != null && player.play(m.path) { playingId = null }) {
                                            playingId = m.id
                                        }
                                    },
                                    onOpen = { m.path?.let { Attachments.openFile(ctx, it) } }
                                )
                                Text(
                                    timeFmt.format(Date(m.time)) + if (m.failed) "  ✗ не доставлено" else "",
                                    style = PdaType.Tiny.copy(color = if (m.failed) PdaColors.Red else PdaColors.TextDim)
                                )
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PdaTextField(input, { input = it }, "> повідомлення", Modifier.weight(1f), singleLine = true)
            if (isFriend) {
                PdaButton(
                    if (recording) "■" else "🎤",
                    if (recording) PdaColors.Red else PdaColors.Amber,
                    {
                        if (!recording) {
                            recording = recorder.start(ctx)
                        } else {
                            recording = false
                            recorder.stop()?.let { (path, _) -> vm.sendMedia(contact.id, "audio", path, "voice.m4a") }
                        }
                    },
                    enabled = mediaOk
                )
                PdaButton("📷", PdaColors.Amber, {
                    val f = Attachments.newPhotoFile(ctx)
                    pendingFile = f
                    camera.launch(Attachments.uriFor(ctx, f))
                }, enabled = mediaOk)
                PdaButton("📎", PdaColors.Amber, { attach.launch("*/*") }, enabled = mediaOk)
            }
            PdaButton(
                "[ВІДПРАВИТИ]", PdaColors.Green,
                {
                    vm.sendChat(contact.id, input)
                    input = ""
                },
                enabled = input.isNotBlank() && canChat
            )
        }
    }
    photoPath?.let { PhotoDialog(it) { photoPath = null } }
}

@Composable
private fun Bubble(m: ChatMessage, playing: Boolean, onPhoto: (String) -> Unit, onAudio: () -> Unit, onOpen: () -> Unit) {
    Box(Modifier.background(PdaColors.PanelAlt).padding(horizontal = 6.dp, vertical = 3.dp)) {
        when (m.kind) {
            "photo" -> if (m.path != null) {
                PhotoView(m.path, Modifier.size(width = 130.dp, height = 96.dp).clickable { onPhoto(m.path) }, 400)
            } else {
                Text("фото", style = PdaType.Terminal)
            }
            "audio" -> PdaButton(if (playing) "■ Стоп" else "♪ Голосове", PdaColors.Amber, onAudio)
            "file" -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("📎 ${m.fileName}", style = PdaType.Terminal)
                PdaButton("Відкрити", PdaColors.Green, onOpen)
            }
            else -> Text(m.text, style = PdaType.Terminal)
        }
    }
}

// ───────────── contact profile (like the "Дані" tab, but theirs) ─────────────

@Composable
private fun ContactProfilePane(state: PdaUiState, vm: PdaViewModel, c: Contact, onBack: () -> Unit) {
    var showPerms by remember { mutableStateOf(false) }
    val prefs = state.prefs[c.id] ?: ContactPrefs()
    val isFriend = c.type == ContactType.FRIEND
    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    val self = state.selfLocation
    val lat = c.lastKnownLat
    val lng = c.lastKnownLng
    val distance = if (self != null && lat != null && lng != null) {
        val r = FloatArray(1)
        Location.distanceBetween(self.latitude, self.longitude, lat, lng, r)
        if (r[0] >= 1000f) "%.1f км".format(r[0] / 1000f) else "${r[0].toInt()} м"
    } else "невідомо"

    Column(Modifier.fillMaxSize()) {
        PanelHeader(c.callsign) {
            Text("[← чат]", style = PdaType.Title.copy(color = PdaColors.Green), modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 4.dp))
            Text("[⚙]", style = PdaType.Title.copy(color = PdaColors.Amber), modifier = Modifier.clickable { showPerms = true }.padding(horizontal = 4.dp))
        }
        Panel(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row {
                    AvatarView(c.id, c.avatarPath, Modifier.size(width = 96.dp, height = 78.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        InfoLine("ранг", StalkerRank.fromXp(c.xp).label)
                        InfoLine("група", c.faction.ifBlank { "—" })
                        InfoLine("ставлення", if (isFriend) "друг" else "сторонній")
                        if (prefs.blocked) Text("⛔ заблокований", style = PdaType.Small.copy(color = PdaColors.Red))
                    }
                }
                if (c.motto.isNotBlank()) Text("«${c.motto}»", style = PdaType.Small.copy(color = PdaColors.Amber))
                Text("Статистика", style = PdaType.Title)
                StatRowLine("Усього XP", "${c.xp}")
                StatRowLine("Виконані завдання", "${c.done}")
                StatRowLine("Пройдено пішки", "%.2f км".format(c.km))
                StatRowLine("Унікальні зустрічі", "${c.enc}")
                if (isFriend) StatRowLine("Друг з", dateFmt.format(Date(c.addedAt)))
                StatRowLine("Відстань", distance)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isFriend) {
                        PdaButton("− З ДРУЗІВ", PdaColors.Red, { vm.removeFriend(c.id) })
                    } else {
                        PdaButton("+ В ДРУЗІ", PdaColors.Amber, { vm.addFriend(c.id) })
                    }
                    PdaButton("⚙ ДОЗВОЛИ", PdaColors.Cyan, { showPerms = true })
                }
            }
        }
    }

    if (showPerms) {
        PdaDialog(title = "Дозволи: ${c.callsign}", onDismiss = { showPerms = false }) {
            Text("Що дозволено цьому контакту", style = PdaType.Small)
            PermRow("Дзвінки", prefs.allowCalls) { vm.setPrefs(c.id, prefs.copy(allowCalls = it)) }
            PermRow("Надсилати фото", prefs.allowPhotos) { vm.setPrefs(c.id, prefs.copy(allowPhotos = it)) }
            PermRow("Надсилати аудіо", prefs.allowAudio) { vm.setPrefs(c.id, prefs.copy(allowAudio = it)) }
            PermRow("Надсилати файли", prefs.allowFiles) { vm.setPrefs(c.id, prefs.copy(allowFiles = it)) }
            PermRow("Заблокувати", prefs.blocked, danger = true) { vm.setBlocked(c.id, it) }
            Text(
                "Заблокований не бачить вашу активність і не може з вами зв'язатися (і ви з ним).",
                style = PdaType.Tiny
            )
            PdaButton("Закрити", PdaColors.TextGrey, { showPerms = false })
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column {
        Text(label, style = PdaType.Tiny.copy(color = PdaColors.TextDim))
        Text(value, style = PdaType.Small.copy(color = PdaColors.TextWhite))
    }
}

@Composable
private fun StatRowLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = PdaType.Small)
        Text(value, style = PdaType.Small.copy(color = PdaColors.TextWhite))
    }
}

@Composable
private fun PermRow(label: String, enabled: Boolean, danger: Boolean = false, onChange: (Boolean) -> Unit) {
    ToggleRow(label, "", enabled, danger) { onChange(!enabled) }
}

// ───────────── privacy (no contact selected) ─────────────

@Composable
private fun PrivacyPane(state: PdaUiState, vm: PdaViewModel) {
    Column(Modifier.fillMaxSize()) {
        PanelHeader("Приватність і мережа")
        Panel(Modifier.fillMaxSize()) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Видимий для всіх", "Твоя іконка й позиція на картах незнайомців", state.userProfile.visibleToAll) { vm.toggleVisibleToAll() }
                ToggleRow("Відкритий канал", "Незнайомці можуть надсилати тобі текст (фото, файли й дзвінки лише від друзів)", state.userProfile.openChannel) { vm.toggleOpenChannel() }
                val online = state.contacts.count { it.onlineStatus != ContactOnlineStatus.OFFLINE }
                Text(
                    if (state.lanActive) "Локальна мережа: активна (UDP ${LanNode.PORT}), у мережі: $online"
                    else "Локальна мережа недоступна: перевір Wi-Fi",
                    style = PdaType.Tiny.copy(color = if (state.lanActive) PdaColors.Green else PdaColors.Red)
                )
                Text(
                    "Оберіть контакт зі списку, щоб відкрити чат. Натисни на його аватар у чаті, щоб побачити профіль, друзів і дозволи.",
                    style = PdaType.Tiny.copy(color = PdaColors.TextDim)
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, hint: String, enabled: Boolean, danger: Boolean = false, onToggle: () -> Unit) {
    val on = if (danger) PdaColors.Red else PdaColors.Green
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PdaColors.PanelAlt)
            .clickable(onClick = onToggle)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = PdaType.Body.copy(color = if (danger && enabled) PdaColors.Red else PdaColors.TextWhite))
            if (hint.isNotEmpty()) Text(hint, style = PdaType.Tiny)
        }
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 18.dp)
                .background(if (enabled) on.copy(alpha = 0.25f) else PdaColors.PanelBg)
                .border(1.dp, if (enabled) on else PdaColors.Border)
                .padding(2.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.size(12.dp).background(if (enabled) on else PdaColors.TextDim))
        }
    }
}
