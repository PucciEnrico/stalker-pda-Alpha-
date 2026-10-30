package com.stalker.pda.data

import android.content.Context
import com.stalker.pda.data.model.*
import org.json.JSONArray
import org.json.JSONObject

/** SharedPreferences + JSON persistence, so everything survives restarts. */
class PdaStore(context: Context) {
    private val sp = context.getSharedPreferences("pda_data", Context.MODE_PRIVATE)

    // ── profile ──
    fun loadProfile(): UserProfile = UserProfile(
        callsign = sp.getString("callsign", "Мічений") ?: "Мічений",
        faction = sp.getString("faction", "Одинак") ?: "Одинак",
        motto = sp.getString("motto", "") ?: "",
        avatarPath = sp.getString("avatar", null),
        avatarVersion = sp.getLong("avatar_ver", 0L),
        completedTasks = sp.getInt("completed", 0),
        failedTasks = sp.getInt("failed", 0),
        uniqueEncounters = metIds().size,
        visibleToAll = sp.getBoolean("visible", true),
        openChannel = sp.getBoolean("open_channel", false)
    )

    fun saveProfile(p: UserProfile) {
        val e = sp.edit()
            .putString("callsign", p.callsign)
            .putString("faction", p.faction)
            .putString("motto", p.motto)
            .putLong("avatar_ver", p.avatarVersion)
            .putInt("completed", p.completedTasks)
            .putInt("failed", p.failedTasks)
            .putBoolean("visible", p.visibleToAll)
            .putBoolean("open_channel", p.openChannel)
        if (p.avatarPath != null) e.putString("avatar", p.avatarPath) else e.remove("avatar")
        e.apply()
    }

    /** Stable id of this device on the network. */
    fun deviceId(): String {
        val saved = sp.getString("device_id", null)
        if (saved != null) return saved
        val id = java.util.UUID.randomUUID().toString().take(12)
        sp.edit().putString("device_id", id).apply()
        return id
    }

    fun metIds(): Set<String> = sp.getStringSet("met", emptySet()) ?: emptySet()
    fun saveMet(ids: Set<String>) {
        sp.edit().putStringSet("met", HashSet(ids)).apply()
    }

    // ── avatars of other people (version of the cached picture) ──
    fun avatarVersion(id: String): Long = sp.getLong("av_$id", 0L)
    fun setAvatarVersion(id: String, v: Long) {
        sp.edit().putLong("av_$id", v).apply()
    }

    // ── blocked-by (they blocked me) ──
    fun blockedBy(): Set<String> = sp.getStringSet("blocked_by", emptySet()) ?: emptySet()
    fun saveBlockedBy(ids: Set<String>) {
        sp.edit().putStringSet("blocked_by", HashSet(ids)).apply()
    }

    // ── generic helpers ──
    private fun <T> save(key: String, list: List<T>, conv: (T) -> JSONObject) {
        val a = JSONArray()
        list.forEach { a.put(conv(it)) }
        sp.edit().putString(key, a.toString()).apply()
    }

    private fun <T> load(key: String, conv: (JSONObject) -> T): List<T>? {
        val raw = sp.getString(key, null) ?: return null
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { conv(a.getJSONObject(it)) }
        } catch (e: Exception) {
            null
        }
    }

    // ── tasks ──
    fun loadTasks(): List<Task>? = load("tasks") { TaskJson.fromJson(it) }
    fun saveTasks(list: List<Task>) = save("tasks", list) { TaskJson.toJson(it) }

    // ── friends ──
    fun loadFriends(): List<Contact> = load("friends") { o ->
        Contact(
            id = o.getString("id"),
            callsign = o.getString("name"),
            type = ContactType.FRIEND,
            addedAt = o.optLong("added", System.currentTimeMillis()),
            faction = o.optString("faction", "")
        )
    } ?: emptyList()

    fun saveFriends(list: List<Contact>) = save("friends", list) { c ->
        JSONObject().put("id", c.id).put("name", c.callsign).put("added", c.addedAt).put("faction", c.faction)
    }

    // ── what I allow from each contact ──
    fun loadPrefs(): Map<String, ContactPrefs> {
        val out = HashMap<String, ContactPrefs>()
        load("prefs") { o ->
            o.getString("id") to ContactPrefs(
                allowCalls = o.optBoolean("calls", true),
                allowPhotos = o.optBoolean("photos", true),
                allowAudio = o.optBoolean("audio", true),
                allowFiles = o.optBoolean("files", true),
                blocked = o.optBoolean("blocked", false)
            )
        }?.forEach { out[it.first] = it.second }
        return out
    }

    fun savePrefs(map: Map<String, ContactPrefs>) = save("prefs", map.entries.toList()) { (id, p) ->
        JSONObject().put("id", id).put("calls", p.allowCalls).put("photos", p.allowPhotos)
            .put("audio", p.allowAudio).put("files", p.allowFiles).put("blocked", p.blocked)
    }

    // ── articles ──
    fun loadArticles(): List<EncyclopediaArticle>? = load("articles") { o ->
        EncyclopediaArticle(
            id = o.getString("id"),
            title = o.getString("title"),
            category = o.getString("category"),
            bodyMarkdown = o.optString("body", ""),
            isPublic = o.optBoolean("public", false)
        )
    }

    fun saveArticles(list: List<EncyclopediaArticle>) = save("articles", list) { a ->
        JSONObject()
            .put("id", a.id).put("title", a.title).put("category", a.category)
            .put("body", a.bodyMarkdown).put("public", a.isPublic)
    }

    // ── diary + folders ──
    fun loadDiary(): List<DiaryEntry>? = load("diary") { o ->
        DiaryEntry(
            id = o.getString("id"),
            type = DiaryEntryType.valueOf(o.getString("type")),
            content = o.getString("content"),
            createdAt = o.getLong("createdAt"),
            durationSeconds = if (o.has("dur")) o.getInt("dur") else null,
            title = o.optString("title", ""),
            folderId = if (o.has("folder")) o.getString("folder") else null
        )
    }

    fun saveDiary(list: List<DiaryEntry>) = save("diary", list) { e ->
        val o = JSONObject()
        o.put("id", e.id)
        o.put("type", e.type.name)
        o.put("content", e.content)
        o.put("createdAt", e.createdAt)
        e.durationSeconds?.let { o.put("dur", it) }
        o.put("title", e.title)
        e.folderId?.let { o.put("folder", it) }
        o
    }

    fun loadFolders(): List<JournalFolder> = load("folders") { o ->
        JournalFolder(
            o.getString("id"), o.getString("name"), o.optString("icon", "📁"),
            if (o.has("parent")) o.getString("parent") else null
        )
    } ?: emptyList()

    fun saveFolders(list: List<JournalFolder>) = save("folders", list) { f ->
        val o = JSONObject().put("id", f.id).put("name", f.name).put("icon", f.icon)
        f.parentId?.let { o.put("parent", it) }
        o
    }

    // ── message log ──
    fun loadLog(): List<LogMessage> = load("log") { o ->
        LogMessage(
            o.getString("id"), LogType.valueOf(o.getString("type")), o.getString("text"), o.getLong("time"),
            if (o.has("ref")) o.getString("ref") else null,
            if (o.has("refk")) o.getString("refk") else null
        )
    } ?: emptyList()

    fun saveLog(list: List<LogMessage>) = save("log", list) { m ->
        val o = JSONObject().put("id", m.id).put("type", m.type.name).put("text", m.text).put("time", m.time)
        m.refId?.let { o.put("ref", it) }
        m.refKind?.let { o.put("refk", it) }
        o
    }

    // ── chats ──
    fun loadChats(): List<ChatMessage> = load("chats") { o ->
        ChatMessage(
            id = o.getString("id"),
            contactId = o.getString("cid"),
            fromSelf = o.getBoolean("self"),
            text = o.getString("text"),
            time = o.getLong("time"),
            failed = o.optBoolean("failed", false),
            kind = o.optString("kind", "text"),
            path = if (o.has("path")) o.getString("path") else null,
            fileName = o.optString("fname", "")
        )
    } ?: emptyList()

    fun saveChats(list: List<ChatMessage>) = save("chats", list) { m ->
        val o = JSONObject().put("id", m.id).put("cid", m.contactId).put("self", m.fromSelf)
            .put("text", m.text).put("time", m.time).put("failed", m.failed)
            .put("kind", m.kind).put("fname", m.fileName)
        m.path?.let { o.put("path", it) }
        o
    }
}
