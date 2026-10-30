package com.stalker.pda.net

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Local-network messaging over UDP: presence ("hello"), chat, quests and call signalling.
 * Everything is small JSON; peers are found by broadcast on the Wi-Fi subnet.
 */
class LanNode(private val onPacket: (ip: String, json: JSONObject) -> Unit) {
    companion object {
        const val PORT = 45454
    }

    private var socket: DatagramSocket? = null
    @Volatile private var running = false
    private val sender = Executors.newSingleThreadExecutor()

    fun start(): Boolean {
        if (running) return true
        return try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(PORT))
            socket = s
            running = true
            Thread {
                val buf = ByteArray(32 * 1024)
                while (running) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address.hostAddress ?: continue
                        val text = String(p.data, 0, p.length, Charsets.UTF_8)
                        onPacket(ip, JSONObject(text))
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun send(ip: String, json: JSONObject) {
        val s = socket ?: return
        sender.execute {
            try {
                val data = json.toString().toByteArray(Charsets.UTF_8)
                s.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), PORT))
            } catch (_: Exception) {
            }
        }
    }

    fun broadcast(json: JSONObject) {
        val s = socket ?: return
        sender.execute {
            try {
                val data = json.toString().toByteArray(Charsets.UTF_8)
                for (addr in broadcastAddresses()) {
                    try {
                        s.send(DatagramPacket(data, data.size, addr, PORT))
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        sender.shutdown()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            if (ifs != null) {
                for (ni in Collections.list(ifs)) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) ia.broadcast?.let { out.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) out.add(InetAddress.getByName("255.255.255.255"))
        return out
    }
}
