package com.stalker.pda.viewmodel

import android.app.Application
import android.location.Location
import android.util.Base64
import com.stalker.pda.Notifier
import com.stalker.pda.PdaApplication
import com.stalker.pda.R
import com.stalker.pda.data.Attachments
import com.stalker.pda.data.PdaStore
import com.stalker.pda.data.SampleData
import com.stalker.pda.data.TaskJson
import com.stalker.pda.data.model.*
import com.stalker.pda.net.LanNode
import com.stalker.pda.net.VoiceCall
import com.stalker.pda.service.DistanceStore
import com.stalker.pda.service.LocationService
import com.stalker.pda.ui.components.playSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class PdaUiState(
    val activeScreen: PdaScreen = PdaScreen.TASKS,
    val taskFilter: TaskStatus = TaskStatus.ACTIVE,

    val selectedTaskId: String? = null,
    val selectedContactId: String? = null,
    val selectedJournalId: String? = null,
    val selectedFolderId: String? = null,
    val selectedArticleId: String? = null,
    val selectedRankId: String? = null,

    // chosen target (marked on the map) and an explicit "centre the map here" request
    val mapFocusLat: Double? = null,
    val mapFocusLng: Double? = null,
    val mapFocusLabel: String? = null,
    val mapCenterLat: Double? = null,
    val mapCenterLng: Double? = null,
    val mapCenterToken: Int = 0,
    val mapCenterConsumed: Int = 0,

    val selfLocation: Location? = null,
    val nearbyContactIds: Set<String> = emptySet(),
    val newNearbyContact: Contact? = null,

    val userProfile: UserProfile = UserProfile(),
    val tasks: List<Task> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val prefs: Map<String, ContactPrefs> = emptyMap(),
    val journal: List<DiaryEntry> = emptyList(),
    val folders: List<JournalFolder> = emptyList(),
    val articles: List<EncyclopediaArticle> = emptyList(),
    val messages: List<LogMessage> = emptyList(),
    val chats: List<ChatMessage> = emptyList(),

    val call: CallState? = null,
    val incomingQuest: IncomingQuest? = null,
    val lanActive: Boolean = false,

    val messagesSeenAt: Long = 0L,
    val bootComplete: Boolean = false
) {
    val unreadMessages: Boolean get() = messages.any { it.time > messagesSeenAt }
}

/** A stage (or the task itself) typed into the "new task" dialog. */
data class StageDraft(
    val id: String,
    val title: String = "",
    val description: String = "",
    val geoText: String = "",
    val audioPath: String? = null,
    val photoPath: String? = null
)

data class FriendPosition(val contactId: String, val lat: Double, val lng: Double, val timestamp: Long)

private data class Peer(
    val id: String,
    val name: String,
    val faction: String,
    val motto: String,
    val xp: Int,
    val done: Int,
    val km: Double,
    val enc: Int,
    val av: Long,
    val open: Boolean,
    val visible: Boolean,
    val lat: Double?,
    val lng: Double?,
    val ip: String,
    val seenAt: Long
)

private class Outgoing(val ip: String, val chunks: List<ByteArray>, val kind: String, val name: String, val ts: Long)

private class Incoming(
    val from: String,
    val kind: String,
    val name: String,
    val chunks: Array<ByteArray?>,
    var got: Int,
    var lastAt: Long,
    var nacks: Int
)

/**
 * The PDA core. It lives in the Application (see PdaApplication), not in the activity,
 * so the LAN node keeps receiving while the screen is closed.
 */
class PdaViewModel(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = PdaStore(app)
    private val deviceId = store.deviceId()
    private val metIds: MutableSet<String> = store.metIds().toMutableSet()
    private val known = LinkedHashMap<String, Contact>()      // friends + blocked strangers (persisted)
    private val peers = HashMap<String, Peer>()               // seen on the network right now
    private val blockedBy: MutableSet<String> = store.blockedBy().toMutableSet()
    private val outgoing = HashMap<String, Outgoing>()
    private val incoming = HashMap<String, Incoming>()
    private val deniedFids = HashSet<String>()
    private val lastAvReq = HashMap<String, Long>()
    private val lastBlockNotice = HashMap<String, Long>()
    private val voice = VoiceCall(app)
    private var callTimeout: Job? = null
    private val node = LanNode { ip, json -> scope.launch { onPacket(ip, json) } }

    private val _state = MutableStateFlow(
        PdaUiState(userProfile = store.loadProfile(), prefs = store.loadPrefs())
    )
    val state: StateFlow<PdaUiState> = _state.asStateFlow()

    init {
        store.loadFriends().forEach { known[it.id] = it }

        scope.launch { LocationService.locationFlow.filterNotNull().collect { updateSelfLocation(it) } }
        scope.launch {
            while (true) {
                setKmWalked(DistanceStore.totalKm(app))
                delay(5000)
            }
        }
        scope.launch {
            delay(1500) // boot screen
            val tasks = store.loadTasks() ?: SampleData.tasks.also { store.saveTasks(it) }
            val articles = store.loadArticles() ?: SampleData.articles.also { store.saveArticles(it) }
            val log = store.loadLog().ifEmpty {
                listOf(LogMessage(UUID.randomUUID().toString(), LogType.SYSTEM, "ПДА ініціалізовано", System.currentTimeMillis()))
            }
            val started = withContext(Dispatchers.IO) { node.start() }
            _state.update {
                it.copy(
                    bootComplete = true,
                    tasks = tasks,
                    journal = store.loadDiary() ?: SampleData.diaryEntries,
                    folders = store.loadFolders(),
                    articles = articles,
                    messages = log,
                    chats = store.loadChats(),
                    messagesSeenAt = System.currentTimeMillis(),
                    lanActive = started
                )
            }
            rebuildContacts()
            while (true) {
                sendHello()
                rebuildContacts()
                checkDeadlines()
                checkIncomingFiles()
                delay(3000)
            }
        }
    }

    // ───────────── helpers ─────────────

    private fun isFriend(id: String) = known[id]?.type == ContactType.FRIEND
    private fun prefsOf(id: String) = _state.value.prefs[id] ?: ContactPrefs()
    private fun avatarFile(id: String) = File(Attachments.avatarDir(app), "$id.jpg")

    private fun notifyBackground(channel: String, nid: Int, title: String, text: String) {
        if (!PdaApplication.foreground) Notifier.show(app, channel, nid, title, text)
    }

    private fun appendLog(type: LogType, text: String, refId: String? = null, refKind: String? = null) {
        val entry = LogMessage(UUID.randomUUID().toString(), type, text, System.currentTimeMillis(), refId, refKind)
        _state.update { it.copy(messages = (listOf(entry) + it.messages).take(200)) }
        store.saveLog(_state.value.messages)
    }

    // ───────────── navigation ─────────────

    fun navigateTo(screen: PdaScreen) {
        _state.update { it.copy(activeScreen = screen) }
        if (screen == PdaScreen.MESSAGES) markMessagesSeen()
    }

    fun markMessagesSeen() = _state.update { it.copy(messagesSeenAt = System.currentTimeMillis()) }

    fun clearSelection() {
        _state.update {
            when (it.activeScreen) {
                PdaScreen.TASKS -> it.copy(selectedTaskId = null, mapFocusLat = null, mapFocusLng = null, mapFocusLabel = null)
                PdaScreen.CONTACTS -> it.copy(selectedContactId = null)
                PdaScreen.JOURNAL -> it.copy(selectedJournalId = null, selectedFolderId = null)
                PdaScreen.ENCYCLOPEDIA -> it.copy(selectedArticleId = null)
                PdaScreen.RANKING -> it.copy(selectedRankId = null)
                else -> it
            }
        }
    }

    // ───────────── map target / centre ─────────────

    /** Mark a target on the map without moving the map. */
    fun setTarget(lat: Double, lng: Double, label: String) =
        _state.update { it.copy(mapFocusLat = lat, mapFocusLng = lng, mapFocusLabel = label) }

    /** Open the Map tab centred on a point (also marks it as the target). */
    fun openMapAt(lat: Double, lng: Double, label: String) {
        _state.update {
            it.copy(
                activeScreen = PdaScreen.MAP,
                mapFocusLat = lat, mapFocusLng = lng, mapFocusLabel = label,
                mapCenterLat = lat, mapCenterLng = lng, mapCenterToken = it.mapCenterToken + 1
            )
        }
    }

    fun consumeCenter(token: Int) = _state.update { it.copy(mapCenterConsumed = token) }

    /** Centre the map on a point while already on the Map tab (search, "to me"). */
    fun centerMap(lat: Double, lng: Double) =
        _state.update { it.copy(mapCenterLat = lat, mapCenterLng = lng, mapCenterToken = it.mapCenterToken + 1) }

    // ───────────── tasks ─────────────

    fun setTaskFilter(status: TaskStatus) = _state.update { it.copy(taskFilter = status) }

    fun selectTask(id: String) {
        val task = _state.value.tasks.find { it.id == id } ?: return
        val geo = task.targetGeo()
        _state.update {
            it.copy(
                selectedTaskId = id,
                mapFocusLat = geo?.lat, mapFocusLng = geo?.lng,
                mapFocusLabel = if (geo != null) task.title else null
            )
        }
    }

    fun selectStage(taskId: String, stageId: String?) {
        val task = _state.value.tasks.find { it.id == taskId } ?: return
        val stage = task.subTasks.find { it.id == stageId }
        val geo = stage?.geoTag ?: task.targetGeo()
        _state.update {
            it.copy(
                mapFocusLat = geo?.lat, mapFocusLng = geo?.lng,
                mapFocusLabel = if (geo != null) (stage?.title ?: task.title) else null
            )
        }
    }

    fun focusTaskOnMap(task: Task) {
        val geo = task.targetGeo() ?: return
        openMapAt(geo.lat, geo.lng, task.title)
    }

    fun addTask(
        title: String, description: String, geoText: String,
        iconPath: String?, audioPath: String?, deadlineMs: Long?,
        stages: List<StageDraft>
    ) {
        if (title.isBlank()) return
        val subs = stages.filter { it.title.isNotBlank() }.map { d ->
            SubTask(
                id = UUID.randomUUID().toString(),
                title = d.title.trim(),
                description = d.description.trim(),
                geoTag = parseGeo(d.geoText, d.title.trim()),
                audioPath = d.audioPath,
                photoPath = d.photoPath
            )
        }
        val task = Task(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            description = description.trim(),
            imageUri = iconPath,
            audioPath = audioPath,
            deadline = deadlineMs,
            subTasks = subs,
            geoTag = parseGeo(geoText, title.trim())
        )
        _state.update { it.copy(tasks = listOf(task) + it.tasks, taskFilter = TaskStatus.ACTIVE) }
        selectTask(task.id)
        store.saveTasks(_state.value.tasks)
        appendLog(LogType.NEW_TASK, task.title, task.id, "task")
    }

    /** Stages must be done in order; the last one finishes the whole task. */
    fun completeStage(taskId: String, stageId: String) {
        val task = _state.value.tasks.find { it.id == taskId } ?: return
        if (task.status != TaskStatus.ACTIVE) return
        val idx = task.subTasks.indexOfFirst { it.id == stageId }
        if (idx < 0 || task.subTasks[idx].isDone) return
        if (task.subTasks.take(idx).any { !it.isDone }) return
        val updated = task.copy(subTasks = task.subTasks.mapIndexed { i, s -> if (i == idx) s.copy(isDone = true) else s })
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) updated else it }) }
        store.saveTasks(_state.value.tasks)
        playSound(app, R.raw.pda_objective)
        appendLog(LogType.TASK_DONE, "Етап ${idx + 1}: ${task.subTasks[idx].title} (${task.title})", task.id, "task")
        if (updated.subTasks.all { it.isDone }) completeTask(taskId)
    }

    fun completeTask(id: String) {
        val task = _state.value.tasks.find { it.id == id } ?: return
        if (task.status != TaskStatus.ACTIVE) return
        playSound(app, R.raw.pda_objective)
        _state.update { s ->
            s.copy(
                tasks = s.tasks.map { if (it.id == id) it.copy(status = TaskStatus.DONE) else it },
                userProfile = s.userProfile.copy(completedTasks = s.userProfile.completedTasks + 1)
            )
        }
        persistTasksAndProfile()
        appendLog(LogType.TASK_DONE, "Завдання виконано: ${task.title}", task.id, "task")
        notifyAssigner(task, "quest_done")
    }

    fun failTask(id: String, reason: String = "") {
        val task = _state.value.tasks.find { it.id == id } ?: return
        if (task.status != TaskStatus.ACTIVE) return
        _state.update { s ->
            s.copy(
                tasks = s.tasks.map { if (it.id == id) it.copy(status = TaskStatus.FAILED) else it },
                userProfile = s.userProfile.copy(failedTasks = s.userProfile.failedTasks + 1)
            )
        }
        persistTasksAndProfile()
        appendLog(LogType.TASK_FAILED, "Завдання провалено: ${task.title}" + if (reason.isNotEmpty()) " ($reason)" else "", task.id, "task")
        notifyAssigner(task, "quest_fail")
        if (reason.isNotEmpty()) notifyBackground(Notifier.CH_MSG, 3001, "Завдання провалено", "${task.title} ($reason)")
    }

    private fun checkDeadlines() {
        val now = System.currentTimeMillis()
        _state.value.tasks
            .filter { it.status == TaskStatus.ACTIVE && it.deadline != null && it.deadline < now }
            .forEach { failTask(it.id, "строк вичерпано") }
    }

    private fun persistTasksAndProfile() {
        val s = _state.value
        store.saveTasks(s.tasks)
        store.saveProfile(s.userProfile)
    }

    // ───────────── quests between friends ─────────────

    fun assignTask(taskId: String, friendId: String) {
        val task = _state.value.tasks.find { it.id == taskId } ?: return
        val peer = peers[friendId]
        val friend = known[friendId]
        if (peer == null || friend == null || !isFriend(friendId)) {
            appendLog(LogType.QUEST, "Не вдалося надіслати: ${friend?.callsign ?: "друг"} не в мережі", friendId, "contact")
            return
        }
        node.send(
            peer.ip,
            JSONObject().put("t", "quest").put("id", deviceId).put("name", _state.value.userProfile.callsign)
                .put("task", TaskJson.toJson(task, withMedia = false))
        )
        appendLog(LogType.QUEST, "Завдання «${task.title}» надіслано: ${friend.callsign}", friendId, "contact")
    }

    fun acceptQuest() {
        val q = _state.value.incomingQuest ?: return
        val task = q.task.copy(
            id = UUID.randomUUID().toString(),
            status = TaskStatus.ACTIVE,
            createdAt = System.currentTimeMillis(),
            fromId = q.fromId,
            fromName = q.fromName,
            subTasks = q.task.subTasks.map { it.copy(isDone = false) }
        )
        _state.update { it.copy(tasks = listOf(task) + it.tasks, incomingQuest = null, taskFilter = TaskStatus.ACTIVE) }
        store.saveTasks(_state.value.tasks)
        appendLog(LogType.QUEST, "Прийнято завдання від ${q.fromName}: ${task.title}", q.fromId, "contact")
        playSound(app, R.raw.pda_objective)
    }

    fun declineQuest() {
        val q = _state.value.incomingQuest ?: return
        _state.update { it.copy(incomingQuest = null) }
        appendLog(LogType.QUEST, "Відхилено завдання від ${q.fromName}: ${q.task.title}", q.fromId, "contact")
    }

    private fun notifyAssigner(task: Task, kind: String) {
        val fromId = task.fromId ?: return
        val ip = peers[fromId]?.ip ?: return
        node.send(
            ip,
            JSONObject().put("t", kind).put("id", deviceId)
                .put("name", _state.value.userProfile.callsign).put("title", task.title)
        )
    }

    // ───────────── contacts / friends / permissions / block ─────────────

    fun selectContact(id: String) = _state.update { it.copy(selectedContactId = id) }

    fun openChat(id: String) = _state.update { it.copy(selectedContactId = id, activeScreen = PdaScreen.CONTACTS) }

    fun addFriend(id: String) {
        val c = _state.value.contacts.find { it.id == id } ?: return
        known[id] = c.copy(type = ContactType.FRIEND, addedAt = System.currentTimeMillis())
        store.saveFriends(known.values.toList())
        appendLog(LogType.SYSTEM, "${c.callsign} додано до друзів", id, "contact")
        rebuildContacts()
    }

    fun removeFriend(id: String) {
        val c = known[id] ?: return
        if (prefsOf(id).blocked) known[id] = c.copy(type = ContactType.STRANGER) else known.remove(id)
        store.saveFriends(known.values.toList())
        appendLog(LogType.SYSTEM, "${c.callsign} видалено з друзів", id, "contact")
        rebuildContacts()
    }

    fun setPrefs(id: String, prefs: ContactPrefs) {
        _state.update { it.copy(prefs = it.prefs + (id to prefs)) }
        store.savePrefs(_state.value.prefs)
    }

    fun setBlocked(id: String, on: Boolean) {
        val c = _state.value.contacts.find { it.id == id } ?: return
        setPrefs(id, prefsOf(id).copy(blocked = on))
        if (on && known[id] == null) known[id] = c.copy(type = ContactType.STRANGER, addedAt = System.currentTimeMillis())
        if (!on && known[id]?.type == ContactType.STRANGER) known.remove(id)
        store.saveFriends(known.values.toList())
        peers[id]?.let { node.send(it.ip, JSONObject().put("t", if (on) "block" else "unblock").put("id", deviceId)) }
        appendLog(LogType.SYSTEM, "${c.callsign} ${if (on) "заблоковано" else "розблоковано"}", id, "contact")
        rebuildContacts()
    }

    // ───────────── chat: text ─────────────

    private fun addChat(msg: ChatMessage) {
        _state.update { it.copy(chats = it.chats + msg) }
        store.saveChats(_state.value.chats)
    }

    fun sendChat(contactId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val s = _state.value
        val contact = s.contacts.find { it.id == contactId } ?: return
        if (prefsOf(contactId).blocked) return
        val ip = peers[contactId]?.ip
        addChat(ChatMessage(UUID.randomUUID().toString(), contactId, true, t, System.currentTimeMillis(), failed = ip == null))
        if (ip != null) {
            node.send(ip, JSONObject().put("t", "msg").put("id", deviceId).put("name", s.userProfile.callsign).put("text", t))
        }
        appendLog(LogType.MESSAGE, "Ви >> ${contact.callsign}: $t", contactId, "contact")
    }

    private fun handleMsg(fromId: String, o: JSONObject) {
        val text = o.optString("text")
        if (text.isBlank()) return
        // friends always; strangers only when my channel is open (and then text only)
        if (!isFriend(fromId) && !_state.value.userProfile.openChannel) return
        val name = _state.value.contacts.find { it.id == fromId }?.callsign ?: o.optString("name", "?")
        addChat(ChatMessage(UUID.randomUUID().toString(), fromId, false, text, System.currentTimeMillis()))
        appendLog(LogType.MESSAGE, "$name >> Ви: $text", fromId, "contact")
        playSound(app, R.raw.beep_pda)
        notifyBackground(Notifier.CH_MSG, fromId.hashCode(), name, text)
    }

    // ───────────── chat: photos / audio / files (friends only) ─────────────

    fun sendMedia(contactId: String, kind: String, path: String, name: String) {
        val contact = _state.value.contacts.find { it.id == contactId } ?: return
        val ip = peers[contactId]?.ip
        val fail = ip == null || !isFriend(contactId) || prefsOf(contactId).blocked
        if (fail) {
            addChat(ChatMessage(UUID.randomUUID().toString(), contactId, true, name, System.currentTimeMillis(), true, kind, path, name))
            return
        }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { try { File(path).readBytes() } catch (e: Exception) { null } }
            if (bytes == null || bytes.size > MAX_FILE_BYTES) {
                addChat(ChatMessage(UUID.randomUUID().toString(), contactId, true, name, System.currentTimeMillis(), true, kind, path, name))
                appendLog(LogType.MESSAGE, "Файл завеликий або недоступний: $name", contactId, "contact")
                return@launch
            }
            val chunks = (0 until (bytes.size + CHUNK - 1) / CHUNK).map { i ->
                bytes.copyOfRange(i * CHUNK, minOf(bytes.size, (i + 1) * CHUNK))
            }
            val fid = UUID.randomUUID().toString().take(10)
            outgoing[fid] = Outgoing(ip!!, chunks, kind, name, System.currentTimeMillis())
            addChat(ChatMessage(UUID.randomUUID().toString(), contactId, true, name, System.currentTimeMillis(), false, kind, path, name))
            appendLog(LogType.MESSAGE, "Ви >> ${contact.callsign}: ${kindLabel(kind)} $name", contactId, "contact")
            for (i in chunks.indices) {
                node.send(ip, chunkPacket(fid, kind, name, i, chunks.size, chunks[i]))
                delay(3)
            }
        }
    }

    private fun chunkPacket(fid: String, kind: String, name: String, seq: Int, n: Int, data: ByteArray) =
        JSONObject().put("t", "file").put("id", deviceId).put("fid", fid).put("kind", kind)
            .put("name", name).put("seq", seq).put("n", n)
            .put("d", Base64.encodeToString(data, Base64.NO_WRAP))

    private fun kindLabel(kind: String) = when (kind) {
        "photo" -> "фото"
        "audio" -> "аудіо"
        else -> "файл"
    }

    private fun handleFile(fromId: String, ip: String, o: JSONObject) {
        val kind = o.optString("kind")
        val fid = o.optString("fid")
        val pref = prefsOf(fromId)
        val allowed = isFriend(fromId) && !pref.blocked && when (kind) {
            "photo" -> pref.allowPhotos
            "audio" -> pref.allowAudio
            else -> pref.allowFiles
        }
        if (!allowed) {
            if (deniedFids.add(fid)) {
                node.send(ip, JSONObject().put("t", "deny").put("id", deviceId).put("what", kind))
            }
            return
        }
        val n = o.optInt("n")
        val seq = o.optInt("seq", -1)
        if (n <= 0 || n > MAX_CHUNKS || seq !in 0 until n) return
        val now = System.currentTimeMillis()
        val inc = incoming.getOrPut(fid) {
            Incoming(fromId, kind, o.optString("name", "file"), arrayOfNulls(n), 0, now, 0)
        }
        if (seq < inc.chunks.size && inc.chunks[seq] == null) {
            inc.chunks[seq] = Base64.decode(o.getString("d"), Base64.NO_WRAP)
            inc.got++
        }
        inc.lastAt = now
        if (inc.got == inc.chunks.size) finishIncoming(fid)
    }

    private fun finishIncoming(fid: String) {
        val inc = incoming.remove(fid) ?: return
        val out = ByteArrayOutputStream()
        inc.chunks.forEach { out.write(it ?: return) }
        val dir = File(app.filesDir, "received").apply { mkdirs() }
        val safe = inc.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "${System.currentTimeMillis()}_$safe")
        file.writeBytes(out.toByteArray())
        val name = _state.value.contacts.find { it.id == inc.from }?.callsign ?: "?"
        addChat(ChatMessage(UUID.randomUUID().toString(), inc.from, false, inc.name, System.currentTimeMillis(), false, inc.kind, file.absolutePath, inc.name))
        appendLog(LogType.MESSAGE, "$name >> Ви: ${kindLabel(inc.kind)} ${inc.name}", inc.from, "contact")
        playSound(app, R.raw.beep_pda)
        notifyBackground(Notifier.CH_MSG, inc.from.hashCode(), name, "${kindLabel(inc.kind)}: ${inc.name}")
    }

    /** Ask for chunks that never arrived (UDP can lose some). */
    private fun checkIncomingFiles() {
        val now = System.currentTimeMillis()
        val it = incoming.entries.iterator()
        while (it.hasNext()) {
            val (fid, inc) = it.next()
            val idle = now - inc.lastAt
            if (idle > 40_000) {
                it.remove()
                continue
            }
            if (idle > 1500 && inc.got < inc.chunks.size && inc.nacks < 8) {
                val ip = peers[inc.from]?.ip ?: continue
                val miss = JSONArray()
                var count = 0
                for (i in inc.chunks.indices) {
                    if (inc.chunks[i] == null && count < 150) {
                        miss.put(i)
                        count++
                    }
                }
                inc.nacks++
                node.send(ip, JSONObject().put("t", "file_nack").put("id", deviceId).put("fid", fid).put("miss", miss))
            }
        }
        outgoing.entries.removeAll { now - it.value.ts > 120_000 }
    }

    private fun handleNack(ip: String, o: JSONObject) {
        val fid = o.optString("fid")
        val out = outgoing[fid] ?: return
        val miss = o.optJSONArray("miss") ?: return
        scope.launch {
            for (k in 0 until miss.length()) {
                val i = miss.optInt(k, -1)
                if (i in out.chunks.indices) {
                    node.send(ip, chunkPacket(fid, out.kind, out.name, i, out.chunks.size, out.chunks[i]))
                    delay(3)
                }
            }
        }
    }

    // ───────────── profile + avatar ─────────────

    fun setProfile(callsign: String, faction: String, motto: String) {
        if (callsign.isBlank()) return
        _state.update {
            it.copy(userProfile = it.userProfile.copy(callsign = callsign.trim(), faction = faction.trim(), motto = motto.trim()))
        }
        store.saveProfile(_state.value.userProfile)
    }

    /** [srcPath] is any picture; it is cropped to a small square avatar. */
    fun setAvatar(srcPath: String) {
        val f = Attachments.makeAvatar(app, srcPath, "self.jpg") ?: return
        _state.update { it.copy(userProfile = it.userProfile.copy(avatarPath = f.absolutePath, avatarVersion = System.currentTimeMillis())) }
        store.saveProfile(_state.value.userProfile)
    }

    fun removeAvatar() {
        File(Attachments.avatarDir(app), "self.jpg").delete()
        _state.update { it.copy(userProfile = it.userProfile.copy(avatarPath = null, avatarVersion = 0L)) }
        store.saveProfile(_state.value.userProfile)
    }

    fun toggleVisibleToAll() {
        _state.update { it.copy(userProfile = it.userProfile.copy(visibleToAll = !it.userProfile.visibleToAll)) }
        store.saveProfile(_state.value.userProfile)
    }

    fun toggleOpenChannel() {
        _state.update { it.copy(userProfile = it.userProfile.copy(openChannel = !it.userProfile.openChannel)) }
        store.saveProfile(_state.value.userProfile)
    }

    fun selectRank(id: String) = _state.update { it.copy(selectedRankId = id) }

    private fun handleAvatarRequest(ip: String) {
        val p = _state.value.userProfile
        val path = p.avatarPath ?: return
        val bytes = try { File(path).readBytes() } catch (e: Exception) { return }
        node.send(
            ip,
            JSONObject().put("t", "av").put("id", deviceId).put("v", p.avatarVersion)
                .put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        )
    }

    private fun handleAvatar(fromId: String, o: JSONObject) {
        val b64 = o.optString("b64")
        if (b64.isEmpty()) return
        try {
            avatarFile(fromId).writeBytes(Base64.decode(b64, Base64.NO_WRAP))
            store.setAvatarVersion(fromId, o.optLong("v", 1L))
            rebuildContacts()
        } catch (_: Exception) {
        }
    }

    // ───────────── voice calls ─────────────

    fun startCall(contactId: String) {
        val s = _state.value
        if (s.call != null || prefsOf(contactId).blocked || !isFriend(contactId)) return
        val peer = peers[contactId] ?: return
        _state.update { it.copy(call = CallState(contactId, peer.name, peer.ip, CallPhase.OUTGOING)) }
        node.send(peer.ip, JSONObject().put("t", "call").put("id", deviceId).put("name", s.userProfile.callsign))
        callTimeout?.cancel()
        callTimeout = scope.launch {
            delay(30_000)
            if (_state.value.call?.phase == CallPhase.OUTGOING) endCall("без відповіді")
        }
    }

    fun acceptCall() {
        val c = _state.value.call ?: return
        if (c.phase != CallPhase.INCOMING) return
        Notifier.cancel(app, CALL_NOTIFICATION)
        node.send(c.ip, JSONObject().put("t", "call_ans").put("id", deviceId).put("ok", true))
        if (voice.start(c.ip)) {
            _state.update { it.copy(call = c.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis())) }
        } else {
            endCall("мікрофон недоступний")
        }
    }

    fun declineCall() {
        val c = _state.value.call ?: return
        Notifier.cancel(app, CALL_NOTIFICATION)
        node.send(c.ip, JSONObject().put("t", "call_ans").put("id", deviceId).put("ok", false))
        callTimeout?.cancel()
        _state.update { it.copy(call = null) }
        appendLog(LogType.CALL, "Відхилено дзвінок від ${c.peerName}", c.peerId, "contact")
    }

    fun endCall(reason: String = "") {
        val c = _state.value.call ?: return
        node.send(c.ip, JSONObject().put("t", "call_end").put("id", deviceId))
        finishCall(c, reason)
    }

    fun setSpeaker(on: Boolean) = voice.setSpeaker(on)

    private fun finishCall(c: CallState, reason: String) {
        callTimeout?.cancel()
        voice.stop()
        Notifier.cancel(app, CALL_NOTIFICATION)
        _state.update { it.copy(call = null) }
        val secs = if (c.startedAt > 0) (System.currentTimeMillis() - c.startedAt) / 1000 else 0
        val text = if (c.startedAt > 0) "Дзвінок: ${c.peerName}, ${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        else "Дзвінок: ${c.peerName}" + if (reason.isNotEmpty()) " ($reason)" else ""
        appendLog(LogType.CALL, text, c.peerId, "contact")
    }

    private fun handleCall(ip: String, fromId: String, o: JSONObject) {
        val s = _state.value
        val pref = prefsOf(fromId)
        val allowed = isFriend(fromId) && pref.allowCalls && !pref.blocked
        if (!allowed || s.call != null) {
            node.send(ip, JSONObject().put("t", "call_ans").put("id", deviceId).put("ok", false))
            if (!allowed) node.send(ip, JSONObject().put("t", "deny").put("id", deviceId).put("what", "call"))
            return
        }
        val name = o.optString("name", "?")
        _state.update { it.copy(call = CallState(fromId, name, ip, CallPhase.INCOMING)) }
        notifyBackground(Notifier.CH_CALL, CALL_NOTIFICATION, "Вхідний дзвінок", name)
        callTimeout?.cancel()
        callTimeout = scope.launch {
            delay(35_000)
            val c = _state.value.call
            if (c?.phase == CallPhase.INCOMING) {
                _state.update { it.copy(call = null) }
                Notifier.cancel(app, CALL_NOTIFICATION)
                appendLog(LogType.CALL, "Пропущений дзвінок: ${c.peerName}", c.peerId, "contact")
            }
        }
    }

    private fun handleCallAnswer(fromId: String, o: JSONObject) {
        val c = _state.value.call ?: return
        if (c.peerId != fromId || c.phase != CallPhase.OUTGOING) return
        if (o.optBoolean("ok", false)) {
            callTimeout?.cancel()
            if (voice.start(c.ip)) {
                _state.update { it.copy(call = c.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis())) }
            } else {
                endCall("мікрофон недоступний")
            }
        } else {
            finishCall(c, "відхилено")
        }
    }

    // ───────────── LAN presence + packet routing ─────────────

    private fun sendHello() {
        val s = _state.value
        val p = s.userProfile
        val loc = s.selfLocation
        val base = JSONObject()
            .put("t", "hello").put("id", deviceId).put("name", p.callsign).put("faction", p.faction)
            .put("motto", p.motto).put("xp", p.totalXp).put("done", p.completedTasks)
            .put("km", p.kmWalked).put("enc", p.uniqueEncounters).put("av", p.avatarVersion)
            .put("open", p.openChannel).put("vis", p.visibleToAll)
        val withPos = JSONObject(base.toString())
        if (loc != null) withPos.put("lat", loc.latitude).put("lng", loc.longitude)

        if (p.visibleToAll) {
            node.broadcast(withPos)
        } else {
            node.broadcast(base)
            if (loc != null) {
                known.values.filter { it.type == ContactType.FRIEND }.forEach { f ->
                    peers[f.id]?.let { node.send(it.ip, withPos) }
                }
            }
        }
    }

    private fun onPacket(ip: String, o: JSONObject) {
        val id = o.optString("id")
        val t = o.optString("t")
        if (id.isEmpty() || id == deviceId) return

        if (t == "unblock") {
            if (blockedBy.remove(id)) store.saveBlockedBy(blockedBy)
            return
        }
        if (t == "block") {
            blockedBy.add(id)
            store.saveBlockedBy(blockedBy)
            peers.remove(id)
            rebuildContacts()
            return
        }
        if (id in blockedBy) return
        if (prefsOf(id).blocked) {
            // I blocked them: stay silent, but keep telling them so they hide me too
            if (t == "hello") {
                val now = System.currentTimeMillis()
                if (now - (lastBlockNotice[id] ?: 0L) > 30_000) {
                    lastBlockNotice[id] = now
                    node.send(ip, JSONObject().put("t", "block").put("id", deviceId))
                }
            }
            return
        }

        when (t) {
            "hello" -> handleHello(ip, id, o)
            "msg" -> handleMsg(id, o)
            "file" -> handleFile(id, ip, o)
            "file_nack" -> handleNack(ip, o)
            "deny" -> {
                val what = when (o.optString("what")) { "photo" -> "фото"; "audio" -> "аудіо"; "call" -> "дзвінки"; else -> "файли" }
                val name = _state.value.contacts.find { it.id == id }?.callsign ?: "Контакт"
                appendLog(LogType.MESSAGE, "$name не приймає: $what", id, "contact")
            }
            "av_req" -> handleAvatarRequest(ip)
            "av" -> handleAvatar(id, o)
            "quest" -> handleQuest(id, o)
            "quest_done" -> if (isFriend(id)) {
                appendLog(LogType.QUEST, "${o.optString("name")} виконав завдання: ${o.optString("title")}", id, "contact")
                playSound(app, R.raw.pda_objective)
            }
            "quest_fail" -> if (isFriend(id)) {
                appendLog(LogType.QUEST, "${o.optString("name")} провалив завдання: ${o.optString("title")}", id, "contact")
            }
            "call" -> handleCall(ip, id, o)
            "call_ans" -> handleCallAnswer(id, o)
            "call_end" -> {
                val c = _state.value.call
                if (c != null && c.peerId == id) finishCall(c, "")
            }
        }
    }

    private fun handleHello(ip: String, id: String, o: JSONObject) {
        val vis = o.optBoolean("vis", true)
        if (!vis && !isFriend(id)) {
            peers.remove(id)
            return
        }
        val hasPos = o.has("lat") && o.has("lng")
        val av = o.optLong("av", 0L)
        peers[id] = Peer(
            id = id,
            name = o.optString("name", "?"),
            faction = o.optString("faction", ""),
            motto = o.optString("motto", ""),
            xp = o.optInt("xp", 0),
            done = o.optInt("done", 0),
            km = o.optDouble("km", 0.0),
            enc = o.optInt("enc", 0),
            av = av,
            open = o.optBoolean("open", false),
            visible = vis,
            lat = if (hasPos) o.getDouble("lat") else null,
            lng = if (hasPos) o.getDouble("lng") else null,
            ip = ip,
            seenAt = System.currentTimeMillis()
        )
        if (av != 0L && (store.avatarVersion(id) != av || !avatarFile(id).exists())) {
            val now = System.currentTimeMillis()
            if (now - (lastAvReq[id] ?: 0L) > 15_000) {
                lastAvReq[id] = now
                node.send(ip, JSONObject().put("t", "av_req").put("id", deviceId))
            }
        }
        rebuildContacts()
    }

    private fun handleQuest(fromId: String, o: JSONObject) {
        if (!isFriend(fromId)) return
        val taskObj = o.optJSONObject("task") ?: return
        val task = try { TaskJson.fromJson(taskObj) } catch (e: Exception) { return }
        val name = o.optString("name", "друг")
        _state.update { it.copy(incomingQuest = IncomingQuest(fromId, name, task)) }
        appendLog(LogType.QUEST, "$name пропонує завдання: ${task.title}", fromId, "contact")
        playSound(app, R.raw.beep_pda)
        notifyBackground(Notifier.CH_MSG, fromId.hashCode() + 1, "Нове завдання від $name", task.title)
    }

    private fun rebuildContacts() {
        val now = System.currentTimeMillis()
        peers.values.removeAll { now - it.seenAt > PEER_FORGET_MS }
        val nearbyIds = _state.value.nearbyContactIds
        val prefs = _state.value.prefs
        val list = ArrayList<Contact>()

        known.values.forEach { f ->
            val blocked = prefs[f.id]?.blocked == true
            val p = if (blocked || f.id in blockedBy) null else peers[f.id]
            val online = p != null && now - p.seenAt < PEER_TTL_MS
            val status = when {
                online && f.id in nearbyIds -> ContactOnlineStatus.LOCAL_NETWORK
                online -> ContactOnlineStatus.ONLINE
                else -> ContactOnlineStatus.OFFLINE
            }
            val av = avatarFile(f.id)
            list.add(
                f.copy(
                    callsign = p?.name ?: f.callsign,
                    faction = p?.faction?.ifBlank { f.faction } ?: f.faction,
                    onlineStatus = status,
                    lastKnownLat = if (online) p?.lat ?: f.lastKnownLat else f.lastKnownLat,
                    lastKnownLng = if (online) p?.lng ?: f.lastKnownLng else f.lastKnownLng,
                    lastSeenAt = p?.seenAt ?: f.lastSeenAt,
                    isOpenChannel = f.type == ContactType.FRIEND,
                    xp = p?.xp ?: f.xp,
                    motto = p?.motto ?: f.motto,
                    done = p?.done ?: f.done,
                    km = p?.km ?: f.km,
                    enc = p?.enc ?: f.enc,
                    avatarPath = if (av.exists()) av.absolutePath else null
                )
            )
        }
        peers.values
            .filter { it.id !in known && it.id !in blockedBy && now - it.seenAt < PEER_TTL_MS }
            .forEach { p ->
                val av = avatarFile(p.id)
                list.add(
                    Contact(
                        id = p.id, callsign = p.name, type = ContactType.STRANGER,
                        onlineStatus = if (p.id in nearbyIds) ContactOnlineStatus.LOCAL_NETWORK else ContactOnlineStatus.ONLINE,
                        lastKnownLat = p.lat, lastKnownLng = p.lng, lastSeenAt = p.seenAt, addedAt = p.seenAt,
                        isOpenChannel = p.open, faction = p.faction, xp = p.xp, motto = p.motto,
                        done = p.done, km = p.km, enc = p.enc,
                        avatarPath = if (av.exists()) av.absolutePath else null
                    )
                )
            }
        _state.update { it.copy(contacts = list) }
        evaluateProximity()
    }

    // ───────────── location + NEARBY FRIEND DETECTION ─────────────

    fun updateSelfLocation(location: Location) {
        _state.update { it.copy(selfLocation = location) }
        evaluateProximity()
    }

    fun setKmWalked(km: Double) {
        _state.update { it.copy(userProfile = it.userProfile.copy(kmWalked = km)) }
    }

    /** Enter radius 100 m, leave radius 130 m (hysteresis). Positions older than 10 min are ignored. */
    private fun evaluateProximity() {
        val s = _state.value
        val self = s.selfLocation ?: return
        val now = System.currentTimeMillis()
        val result = FloatArray(1)
        val nearby = HashSet<String>()

        for (c in s.contacts) {
            val lat = c.lastKnownLat ?: continue
            val lng = c.lastKnownLng ?: continue
            if (now - c.lastSeenAt > STALE_MS) continue
            if (c.onlineStatus == ContactOnlineStatus.OFFLINE) continue
            Location.distanceBetween(self.latitude, self.longitude, lat, lng, result)
            val limit = if (c.id in s.nearbyContactIds) EXIT_RADIUS_M else ENTER_RADIUS_M
            if (result[0] < limit) nearby.add(c.id)
        }
        if (nearby == s.nearbyContactIds) return

        val entered = nearby - s.nearbyContactIds
        val newlyMet = entered - metIds
        if (newlyMet.isNotEmpty()) {
            metIds.addAll(newlyMet)
            store.saveMet(metIds)
        }
        val enteredFriends = s.contacts.filter { it.id in entered && it.type == ContactType.FRIEND }

        _state.update { cur ->
            cur.copy(
                nearbyContactIds = nearby,
                newNearbyContact = enteredFriends.firstOrNull() ?: cur.newNearbyContact,
                contacts = cur.contacts.map { c ->
                    when {
                        c.id in nearby -> c.copy(onlineStatus = ContactOnlineStatus.LOCAL_NETWORK)
                        c.onlineStatus == ContactOnlineStatus.LOCAL_NETWORK -> c.copy(onlineStatus = ContactOnlineStatus.ONLINE)
                        else -> c
                    }
                },
                userProfile = cur.userProfile.copy(uniqueEncounters = metIds.size)
            )
        }
        enteredFriends.forEach {
            appendLog(LogType.NEARBY, "${it.callsign} поруч (менше 100 м)", it.id, "contact")
            notifyBackground(Notifier.CH_MSG, it.id.hashCode() + 2, "Друг поруч", it.callsign)
        }
    }

    fun acknowledgeNearbyAlert() = _state.update { it.copy(newNearbyContact = null) }

    // ───────────── journal (folders + entries) ─────────────

    fun selectJournal(id: String) {
        val e = _state.value.journal.find { it.id == id }
        _state.update { it.copy(selectedJournalId = id, selectedFolderId = e?.folderId) }
    }

    fun selectFolder(id: String?) = _state.update { it.copy(selectedFolderId = id, selectedJournalId = null) }

    fun addFolder(name: String, icon: String) {
        if (name.isBlank()) return
        val f = JournalFolder(UUID.randomUUID().toString(), name.trim(), icon, _state.value.selectedFolderId)
        _state.update { it.copy(folders = it.folders + f, selectedFolderId = f.id, selectedJournalId = null) }
        store.saveFolders(_state.value.folders)
    }

    /** New entries go into the selected folder (or the type categories when none is selected). */
    fun addDiaryEntry(content: String, type: DiaryEntryType, durationSeconds: Int?, title: String = "") {
        val entry = DiaryEntry(
            UUID.randomUUID().toString(), type, content, System.currentTimeMillis(),
            durationSeconds, title, _state.value.selectedFolderId
        )
        _state.update { it.copy(journal = listOf(entry) + it.journal, selectedJournalId = entry.id) }
        store.saveDiary(_state.value.journal)
    }

    /** One tap: write the current position as a journal entry. Returns false if there is no GPS fix. */
    fun addGeoEntry(): Boolean {
        val loc = _state.value.selfLocation ?: return false
        addDiaryEntry("%.6f, %.6f".format(java.util.Locale.US, loc.latitude, loc.longitude), DiaryEntryType.GEO, null, "Геомітка")
        return true
    }

    // ───────────── encyclopedia ─────────────

    fun selectArticle(id: String) = _state.update { it.copy(selectedArticleId = id) }

    fun addArticle(title: String, category: String, body: String) {
        if (title.isBlank()) return
        val a = EncyclopediaArticle(UUID.randomUUID().toString(), title.trim(), category, body)
        _state.update { it.copy(articles = it.articles + a, selectedArticleId = a.id) }
        store.saveArticles(_state.value.articles)
    }

    fun toggleArticlePublic(id: String) {
        _state.update { s -> s.copy(articles = s.articles.map { if (it.id == id) it.copy(isPublic = !it.isPublic) else it }) }
        store.saveArticles(_state.value.articles)
    }

    companion object {
        const val ENTER_RADIUS_M = 100f
        const val EXIT_RADIUS_M = 130f
        const val STALE_MS = 10 * 60 * 1000L
        const val PEER_TTL_MS = 12_000L
        const val PEER_FORGET_MS = 60_000L
        const val CHUNK = 6000
        const val MAX_CHUNKS = 600
        const val MAX_FILE_BYTES = 3 * 1024 * 1024
        const val CALL_NOTIFICATION = 4001
    }
}
