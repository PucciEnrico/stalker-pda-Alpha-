package com.stalker.pda.data

import com.stalker.pda.data.model.GeoTag
import com.stalker.pda.data.model.SubTask
import com.stalker.pda.data.model.Task
import com.stalker.pda.data.model.TaskStatus
import org.json.JSONArray
import org.json.JSONObject

/** Task <-> JSON (storage, and quests sent to friends without media paths). */
object TaskJson {
    private fun geo(g: GeoTag) = JSONObject().put("lat", g.lat).put("lng", g.lng).put("label", g.label)

    private fun readGeo(o: JSONObject?): GeoTag? =
        if (o == null) null else GeoTag(o.getDouble("lat"), o.getDouble("lng"), o.optString("label", ""))

    fun toJson(t: Task, withMedia: Boolean = true): JSONObject {
        val o = JSONObject()
        o.put("id", t.id)
        o.put("title", t.title)
        o.put("desc", t.description)
        o.put("status", t.status.name)
        o.put("created", t.createdAt)
        o.put("xp", t.xpReward)
        t.fromId?.let { o.put("fromId", it) }
        t.fromName?.let { o.put("fromName", it) }
        t.deadline?.let { o.put("deadline", it) }
        t.geoTag?.let { o.put("geo", geo(it)) }
        if (withMedia) {
            t.imageUri?.let { o.put("icon", it) }
            t.audioPath?.let { o.put("audio", it) }
        }
        val subs = JSONArray()
        t.subTasks.forEach { s ->
            val so = JSONObject()
            so.put("id", s.id)
            so.put("title", s.title)
            so.put("desc", s.description)
            so.put("done", s.isDone)
            s.geoTag?.let { so.put("geo", geo(it)) }
            if (withMedia) {
                s.audioPath?.let { so.put("audio", it) }
                s.photoPath?.let { so.put("photo", it) }
            }
            subs.put(so)
        }
        o.put("subs", subs)
        return o
    }

    fun fromJson(o: JSONObject): Task {
        val arr = o.optJSONArray("subs") ?: JSONArray()
        val subs = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SubTask(
                id = s.getString("id"),
                title = s.getString("title"),
                isDone = s.optBoolean("done", false),
                geoTag = readGeo(s.optJSONObject("geo")),
                audioPath = if (s.has("audio")) s.getString("audio") else null,
                photoPath = if (s.has("photo")) s.getString("photo") else null,
                description = s.optString("desc", "")
            )
        }
        val taskGeo = readGeo(o.optJSONObject("geo"))
            ?: if (o.has("lat")) GeoTag(o.getDouble("lat"), o.getDouble("lng"), o.optString("label", "")) else null
        return Task(
            id = o.getString("id"),
            title = o.getString("title"),
            description = o.optString("desc", ""),
            imageUri = if (o.has("icon")) o.getString("icon") else null,
            subTasks = subs,
            geoTag = taskGeo,
            status = TaskStatus.valueOf(o.optString("status", "ACTIVE")),
            createdAt = o.optLong("created", System.currentTimeMillis()),
            xpReward = o.optInt("xp", 100),
            fromId = if (o.has("fromId")) o.getString("fromId") else null,
            fromName = if (o.has("fromName")) o.getString("fromName") else null,
            deadline = if (o.has("deadline")) o.getLong("deadline") else null,
            audioPath = if (o.has("audio")) o.getString("audio") else null
        )
    }
}
