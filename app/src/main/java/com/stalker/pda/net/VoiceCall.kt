package com.stalker.pda.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** Two-way voice over UDP on the local network: 16 kHz mono PCM, 20 ms frames. */
class VoiceCall(private val context: Context) {
    companion object {
        const val AUDIO_PORT = 45455
        private const val RATE = 16000
        private const val FRAME = 640 // 20 ms of 16-bit mono
    }

    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var track: AudioTrack? = null
    private var sock: DatagramSocket? = null
    private var oldMode = AudioManager.MODE_NORMAL

    private val audio get() = context.getSystemService(AudioManager::class.java)

    @SuppressLint("MissingPermission")
    fun start(peerIp: String): Boolean {
        if (running) return true
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return try {
            oldMode = audio.mode
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = false

            val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRec, FRAME * 4)
            )
            val minTrk = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minTrk, FRAME * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(AUDIO_PORT))

            rec = r
            track = t
            sock = s
            running = true
            r.startRecording()
            t.play()

            val addr = InetAddress.getByName(peerIp)
            Thread {
                val buf = ByteArray(FRAME)
                while (running) {
                    try {
                        val n = r.read(buf, 0, FRAME)
                        if (n > 0) s.send(DatagramPacket(buf, n, addr, AUDIO_PORT))
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }.apply { isDaemon = true; start() }

            Thread {
                val buf = ByteArray(4096)
                while (running) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        t.write(p.data, 0, p.length)
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }.apply { isDaemon = true; start() }
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun setSpeaker(on: Boolean) {
        try {
            audio.isSpeakerphoneOn = on
        } catch (_: Exception) {
        }
    }

    fun stop() {
        val wasRunning = running
        running = false
        try { sock?.close() } catch (_: Exception) { }
        try { rec?.stop() } catch (_: Exception) { }
        try { rec?.release() } catch (_: Exception) { }
        try { track?.stop() } catch (_: Exception) { }
        try { track?.release() } catch (_: Exception) { }
        sock = null
        rec = null
        track = null
        if (wasRunning) {
            try {
                audio.isSpeakerphoneOn = false
                audio.mode = oldMode
            } catch (_: Exception) {
            }
        }
    }
}
