package com.stalker.pda.net

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** What the "connection key" from the server contains. */
data class ServerConfig(val host: String, val port: Int, val fp: String, val token: String)

/**
 * Connection to a PDA server (global network). TLS with certificate pinning: the connection is
 * accepted only if the server certificate has exactly the fingerprint from the connection key.
 */
class ServerLink(
    private val onPacket: (JSONObject) -> Unit,
    private val onOp: (JSONObject) -> Unit,
    private val onState: (Boolean) -> Unit
) {
    @Volatile
    var connected = false
        private set

    @Volatile private var generation = 0
    @Volatile private var out: OutputStream? = null
    @Volatile private var socket: SSLSocket? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    init {
        timer.scheduleWithFixedDelay({
            if (connected) enqueue(JSONObject().put("op", "ping"))
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** null = disconnect and stop. */
    fun configure(c: ServerConfig?, myId: String) {
        generation++
        closeSocket()
        if (c != null) {
            val gen = generation
            Thread { loop(c, myId, gen) }.apply {
                isDaemon = true
                start()
            }
        }
    }

    fun send(to: String, pkt: JSONObject, spool: Boolean) {
        enqueue(JSONObject().put("op", "send").put("to", to).put("mid", newMid()).put("spool", spool).put("pkt", pkt))
    }

    fun claim(name: String) {
        enqueue(JSONObject().put("op", "claim").put("name", name))
    }

    fun broadcast(pkt: JSONObject) {
        enqueue(JSONObject().put("op", "bcast").put("mid", newMid()).put("pkt", pkt))
    }

    private fun newMid() = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun enqueue(o: JSONObject) {
        val os = out ?: return
        writer.execute {
            try {
                os.write((o.toString() + "\n").toByteArray(Charsets.UTF_8))
                os.flush()
            } catch (e: Exception) {
                closeSocket()
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun setConnected(v: Boolean, gen: Int) {
        if (gen != generation) return
        if (connected != v) {
            connected = v
            onState(v)
        }
    }

    private fun loop(c: ServerConfig, myId: String, gen: Int) {
        var backoff = 2000L
        while (gen == generation) {
            var s: SSLSocket? = null
            try {
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(PinTrust(c.fp)), SecureRandom())
                s = ctx.socketFactory.createSocket() as SSLSocket
                s.connect(InetSocketAddress(c.host, c.port), 10_000)
                s.soTimeout = 100_000
                s.startHandshake()
                val input = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8), 65536)
                val os = s.outputStream
                input.readLine() ?: throw IOException("no greeting")
                os.write((JSONObject().put("op", "auth").put("uid", myId).put("token", c.token).toString() + "\n")
                    .toByteArray(Charsets.UTF_8))
                os.flush()
                val ok = JSONObject(input.readLine() ?: throw IOException("closed"))
                if (ok.optString("op") != "ok") throw IOException("auth refused")
                socket = s
                out = os
                setConnected(true, gen)
                backoff = 2000L
                while (gen == generation) {
                    val line = input.readLine() ?: break
                    val o = JSONObject(line)
                    when (o.optString("op")) {
                        "pkt" -> onPacket(o.getJSONObject("pkt"))
                        "pong" -> {}
                        else -> onOp(o)
                    }
                }
            } catch (_: Exception) {
            } finally {
                out = null
                setConnected(false, gen)
                try {
                    s?.close()
                } catch (_: Exception) {
                }
            }
            if (gen != generation) return
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                return
            }
            backoff = minOf(backoff * 2, 30_000L)
        }
    }
}

private class PinTrust(fp: String) : X509TrustManager {
    private val want = fp.lowercase().replace(":", "")

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            .joinToString("") { "%02x".format(it) }
        if (got != want) throw CertificateException("Відбиток сертифіката сервера не збігається")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
