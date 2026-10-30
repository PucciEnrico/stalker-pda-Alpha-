package com.stalker.pda.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.io.File

class AudioRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    @Suppress("DEPRECATION")
    private fun newRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()

    fun start(context: Context): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        val dir = File(context.filesDir, "audio_logs").apply { mkdirs() }
        val f = File(dir, "log_${System.currentTimeMillis()}.m4a")
        var r: MediaRecorder? = null
        return try {
            r = newRecorder(context)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(22_050)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            try { r?.release() } catch (_: Exception) { }
            false
        }
    }

    /** Returns (file path, duration in seconds) or null if nothing was recorded. */
    fun stop(): Pair<String, Int>? {
        val r = recorder ?: return null
        val f = file
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        recorder = null
        file = null
        return try {
            r.stop()
            r.release()
            f?.let { it.absolutePath to maxOf(seconds, 1) }
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) { }
            f?.delete()
            null
        }
    }

    fun amplitude(): Int = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }

    fun release() {
        recorder?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        recorder = null
    }
}

class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(path: String, onDone: () -> Unit): Boolean {
        stop()
        if (!File(path).exists()) return false
        return try {
            val p = MediaPlayer()
            player = p
            p.setDataSource(path)
            p.setOnCompletionListener {
                stop()
                onDone()
            }
            p.prepare()
            p.start()
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        player?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        player = null
    }
}
