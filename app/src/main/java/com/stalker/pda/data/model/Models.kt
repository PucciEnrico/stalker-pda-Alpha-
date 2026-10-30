package com.stalker.pda.data.model

// ───────────── TASKS ─────────────
enum class TaskStatus { ACTIVE, DONE, FAILED }

data class SubTask(
    val id: String,
    val title: String,
    val isDone: Boolean = false,
    val geoTag: GeoTag? = null,
    val audioPath: String? = null,
    val photoPath: String? = null,
    val description: String = ""
)

data class GeoTag(val lat: Double, val lng: Double, val label: String = "")

data class Task(
    val id: String,
    val title: String,
    val description: String,
    val imageUri: String? = null,
    val subTasks: List<SubTask> = emptyList(),
    val geoTag: GeoTag? = null,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val xpReward: Int = 100,
    val fromId: String? = null,
    val fromName: String? = null,
    val deadline: Long? = null,      // after this moment an active task fails by itself
    val audioPath: String? = null    // task-level audio file (imageUri = icon photo)
)

/** Where to go: the task point, or the first stage that has a geotag. */
fun Task.targetGeo(): GeoTag? = geoTag ?: subTasks.firstNotNullOfOrNull { it.geoTag }

/** "51.27, 30.22" -> GeoTag, or null if it is not a pair of numbers. */
fun parseGeo(text: String, label: String = ""): GeoTag? {
    val parts = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lng = parts[1].toDoubleOrNull() ?: return null
    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
    return GeoTag(lat, lng, label)
}

// ───────────── CONTACTS / CHAT ─────────────
enum class ContactType { FRIEND, STRANGER }
enum class ContactOnlineStatus { ONLINE, OFFLINE, LOCAL_NETWORK }

data class Contact(
    val id: String,
    val callsign: String,
    val type: ContactType = ContactType.FRIEND,
    val onlineStatus: ContactOnlineStatus = ContactOnlineStatus.OFFLINE,
    val lastKnownLat: Double? = null,
    val lastKnownLng: Double? = null,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val addedAt: Long = System.currentTimeMillis(),
    val isOpenChannel: Boolean = false,
    val faction: String = "Одинак",
    val xp: Int = 0,
    val avatarPath: String? = null,
    val motto: String = "",
    val done: Int = 0,
    val km: Double = 0.0,
    val enc: Int = 0
)

data class ChatMessage(
    val id: String,
    val contactId: String,
    val fromSelf: Boolean,
    val text: String,
    val time: Long,
    val failed: Boolean = false,
    val kind: String = "text",        // text | photo | audio | file
    val path: String? = null,
    val fileName: String = ""
)

// ───────────── MESSAGE LOG ("Історія повідомлень") ─────────────
enum class LogType { SYSTEM, NEW_TASK, TASK_DONE, TASK_FAILED, MESSAGE, NEARBY, QUEST, CALL }

data class LogMessage(
    val id: String,
    val type: LogType,
    val text: String,
    val time: Long,
    val refId: String? = null,        // contact id / task id for the avatar
    val refKind: String? = null       // "contact" | "task" | "self"
)

// ───────────── DIARY ─────────────
enum class DiaryEntryType { TEXT, AUDIO_LOG, PHOTO, FILE, GEO }

data class DiaryEntry(
    val id: String,
    val type: DiaryEntryType = DiaryEntryType.TEXT,
    val content: String,               // note text, or absolute path of the audio file
    val createdAt: Long = System.currentTimeMillis(),
    val durationSeconds: Int? = null,
    val title: String = "",
    val folderId: String? = null
)

/** User-made folder in the journal (can contain anything, and other folders). */
data class JournalFolder(val id: String, val name: String, val icon: String, val parentId: String? = null)

/** What I allow from a contact. */
data class ContactPrefs(
    val allowCalls: Boolean = true,
    val allowPhotos: Boolean = true,
    val allowAudio: Boolean = true,
    val allowFiles: Boolean = true,
    val blocked: Boolean = false
)

// ───────────── ENCYCLOPEDIA ─────────────
data class EncyclopediaArticle(
    val id: String,
    val title: String,
    val category: String,
    val bodyMarkdown: String,
    val isPublic: Boolean = false
)

// ───────────── PROFILE / RANKS ─────────────
enum class StalkerRank(val label: String, val minXp: Int) {
    ROOKIE("новачок", 0),
    EXPERIENCED("досвідчений", 1000),
    VETERAN("ветеран", 5000),
    MASTER("майстер", 15000);

    companion object {
        fun fromXp(xp: Int): StalkerRank = entries.lastOrNull { xp >= it.minXp } ?: ROOKIE
    }
}

data class UserProfile(
    val callsign: String = "Мічений",
    val faction: String = "Одинак",
    val motto: String = "",
    val avatarPath: String? = null,
    val avatarVersion: Long = 0L,
    val completedTasks: Int = 0,
    val failedTasks: Int = 0,
    val kmWalked: Double = 0.0,
    val uniqueEncounters: Int = 0,
    val visibleToAll: Boolean = true,
    val openChannel: Boolean = false
) {
    // Total_XP = (Completed_Tasks * 100) + (Km_Walked * 10) + (Unique_Encounters * 50)
    val totalXp: Int
        get() = (completedTasks * 100) + (kmWalked * 10).toInt() + (uniqueEncounters * 50)

    val rank: StalkerRank get() = StalkerRank.fromXp(totalXp)

    val taskSuccessRate: Float
        get() {
            val total = completedTasks + failedTasks
            return if (total == 0) 0f else completedTasks.toFloat() / total
        }

    val reputation: String
        get() = when {
            completedTasks + failedTasks == 0 -> "нейтральна"
            taskSuccessRate >= 0.8f -> "відмінна"
            taskSuccessRate >= 0.5f -> "добра"
            else -> "погана"
        }
}

// ───────────── NAVIGATION (tabs as in the PDA reference) ─────────────
enum class PdaScreen(val label: String) {
    TASKS("Задачі"),
    MAP("Карта"),
    JOURNAL("Журнал"),
    MESSAGES("Історія повідомлень"),
    CONTACTS("Контакти"),
    RANKING("Рейтинг"),
    DATA("Дані"),
    ENCYCLOPEDIA("Довідка")
}

// ───────────── CALLS / INCOMING QUESTS (local network) ─────────────
enum class CallPhase { INCOMING, OUTGOING, ACTIVE }

data class CallState(
    val peerId: String,
    val peerName: String,
    val ip: String,
    val phase: CallPhase,
    val startedAt: Long = 0L
)

data class IncomingQuest(val fromId: String, val fromName: String, val task: Task)
