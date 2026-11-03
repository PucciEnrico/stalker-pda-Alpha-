package com.stalker.pda.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export / import of everything (profile, ID, server key, friends, tasks, journal, chats, photos, files).
 * Import is staged first and applied on the next start of the app, before anything else touches the data.
 */
object Backup {
    private val PREFS = listOf("pda_data", "pda_distance")
    private val DIRS = listOf("photos", "audio_logs", "attachments", "avatars", "received")
    private const val PENDING = "import_pending"

    // ───────────── export ─────────────

    fun export(ctx: Context, uri: Uri): Boolean = try {
        val out = ctx.contentResolver.openOutputStream(uri, "w")
        if (out == null) {
            false
        } else {
            ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry("meta.json"))
                zip.write(
                    JSONObject().put("v", 1).put("filesDir", ctx.filesDir.absolutePath)
                        .put("time", System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
                for (name in PREFS) {
                    zip.putNextEntry(ZipEntry("prefs/$name.json"))
                    zip.write(prefsToJson(ctx, name).toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                for (d in DIRS) {
                    File(ctx.filesDir, d).walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(ctx.filesDir).path.replace(File.separatorChar, '/')
                        zip.putNextEntry(ZipEntry("files/$rel"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun prefsToJson(ctx: Context, name: String): JSONObject {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val root = JSONObject()
        for ((k, v) in sp.all) {
            val o = JSONObject()
            when (v) {
                is String -> o.put("t", "s").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Boolean -> o.put("t", "b").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Set<*> -> {
                    val a = JSONArray()
                    v.forEach { a.put(it.toString()) }
                    o.put("t", "ss").put("v", a)
                }
                else -> continue
            }
            root.put(k, o)
        }
        return root
    }

    // ───────────── import: stage now, apply on next start ─────────────

    /** Unpacks the backup next to the app data. Returns false if it is not a PDA backup. */
    fun stage(ctx: Context, uri: Uri): Boolean {
        val pending = File(ctx.filesDir, PENDING)
        pending.deleteRecursively()
        pending.mkdirs()
        val root = pending.canonicalPath + File.separator
        return try {
            val input = ctx.contentResolver.openInputStream(uri) ?: return false
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(pending, entry.name)
                    if (!target.canonicalPath.startsWith(root)) throw SecurityException("bad path")
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
            val ok = File(pending, "meta.json").exists() && File(pending, "prefs/pda_data.json").exists()
            if (!ok) pending.deleteRecursively()
            ok
        } catch (e: Exception) {
            pending.deleteRecursively()
            false
        }
    }

    /** Called first thing in Application.onCreate. */
    fun applyPending(ctx: Context) {
        val pending = File(ctx.filesDir, PENDING)
        val metaFile = File(pending, "meta.json")
        if (!metaFile.exists()) return
        try {
            val oldDir = JSONObject(metaFile.readText()).optString("filesDir", ctx.filesDir.absolutePath)
            val newDir = ctx.filesDir.absolutePath
            fun fix(s: String) = s.replace(oldDir, newDir)

            for (name in PREFS) {
                val f = File(pending, "prefs/$name.json")
                if (!f.exists()) continue
                val json = JSONObject(f.readText())
                val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                for (key in json.keys()) {
                    val o = json.getJSONObject(key)
                    when (o.getString("t")) {
                        "s" -> e.putString(key, fix(o.getString("v")))
                        "i" -> e.putInt(key, o.getInt("v"))
                        "l" -> e.putLong(key, o.getLong("v"))
                        "b" -> e.putBoolean(key, o.getBoolean("v"))
                        "f" -> e.putFloat(key, o.getDouble("v").toFloat())
                        "ss" -> {
                            val a = o.getJSONArray("v")
                            e.putStringSet(key, (0 until a.length()).map { fix(a.getString(it)) }.toSet())
                        }
                    }
                }
                e.commit()
            }
            for (d in DIRS) File(ctx.filesDir, d).deleteRecursively()
            File(pending, "files").takeIf { it.exists() }?.copyRecursively(ctx.filesDir, overwrite = true)
        } catch (_: Exception) {
        } finally {
            pending.deleteRecursively()
        }
    }

    /** Restart the app so the staged import is applied before the core starts. */
    fun restartApp(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) {
            val pi = PendingIntent.getActivity(ctx, 77, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .set(AlarmManager.RTC, System.currentTimeMillis() + 700, pi)
        }
        Runtime.getRuntime().exit(0)
    }
}
