package com.stalker.pda.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class PoiKind { MEDIC, TRADER }

data class Poi(val lat: Double, val lng: Double, val name: String, val kind: PoiKind)

/** Real-world places from OpenStreetMap (Overpass): hospitals/pharmacies = medics, shops = traders. */
object PoiRepository {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    /** Looks around the centre of the box (at most about 6 km across, so servers answer quickly). */
    suspend fun fetch(south: Double, west: Double, north: Double, east: Double): List<Poi> =
        withContext(Dispatchers.IO) {
            val cLat = (south + north) / 2
            val cLng = (west + east) / 2
            val s = maxOf(south, cLat - 0.03)
            val n = minOf(north, cLat + 0.03)
            val w = maxOf(west, cLng - 0.045)
            val e = minOf(east, cLng + 0.045)
            val box = "$s,$w,$n,$e"
            val query = "[out:json][timeout:25];(" +
                "nwr[\"amenity\"~\"^(hospital|clinic|pharmacy|doctors)\$\"]($box);" +
                "nwr[\"shop\"]($box);" +
                "nwr[\"amenity\"=\"marketplace\"]($box);" +
                ");out center 150;"

            var lastError = "немає з'єднання"
            for (endpoint in ENDPOINTS) {
                try {
                    val conn = URL(endpoint).openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 30_000
                        conn.setRequestProperty("User-Agent", "StalkerPDA/0.5 (Android)")
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                        if (conn.responseCode != 200) {
                            lastError = "сервер ${conn.responseCode}"
                            continue
                        }
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        return@withContext parse(text)
                    } finally {
                        conn.disconnect()
                    }
                } catch (ex: Exception) {
                    lastError = ex.message?.take(40) ?: "помилка мережі"
                }
            }
            throw RuntimeException(lastError)
        }

    private fun parse(text: String): List<Poi> {
        val elements = JSONObject(text).getJSONArray("elements")
        val out = ArrayList<Poi>()
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val center = el.optJSONObject("center")
            val lat = if (el.has("lat")) el.getDouble("lat") else center?.optDouble("lat") ?: continue
            val lng = if (el.has("lon")) el.getDouble("lon") else center?.optDouble("lon") ?: continue
            val medic = tags.optString("amenity") in listOf("hospital", "clinic", "pharmacy", "doctors")
            val name = tags.optString("name", "").ifBlank { if (medic) "Медпункт" else "Торговець" }
            out.add(Poi(lat, lng, name, if (medic) PoiKind.MEDIC else PoiKind.TRADER))
        }
        return out
    }
}
