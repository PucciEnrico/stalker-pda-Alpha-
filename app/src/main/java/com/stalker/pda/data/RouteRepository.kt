package com.stalker.pda.data

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RouteResult(
    val points: List<Pair<Double, Double>>,   // (lat, lng)
    val distanceM: Double,
    val durationS: Double,
    val straight: Boolean                     // true = routing service unavailable, plain straight line
)

/** Pedestrian route (just the line, no voice and no turns) from OpenStreetMap data. */
object RouteRepository {
    private const val BASE = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"

    suspend fun fetch(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): RouteResult =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE$fromLng,$fromLat;$toLng,$toLat?overview=full&geometries=geojson")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("User-Agent", "StalkerPDA/0.7 (Android)")
                    if (conn.responseCode == 200) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        val routes = JSONObject(text).getJSONArray("routes")
                        if (routes.length() > 0) {
                            val r = routes.getJSONObject(0)
                            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
                            val pts = ArrayList<Pair<Double, Double>>(coords.length())
                            for (i in 0 until coords.length()) {
                                val c = coords.getJSONArray(i)
                                pts.add(c.getDouble(1) to c.getDouble(0))
                            }
                            if (pts.size >= 2) {
                                return@withContext RouteResult(pts, r.getDouble("distance"), r.optDouble("duration", 0.0), false)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
            }
            val out = FloatArray(1)
            Location.distanceBetween(fromLat, fromLng, toLat, toLng, out)
            RouteResult(listOf(fromLat to fromLng, toLat to toLng), out[0].toDouble(), out[0] / 1.3, true)
        }
}
