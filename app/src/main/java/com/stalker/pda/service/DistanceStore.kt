package com.stalker.pda.service

import android.content.Context

/** Persistent pedestrian distance counter shared by LocationService and DistanceWorker. */
object DistanceStore {
    data class Point(val lat: Double, val lng: Double, val time: Long)

    private fun sp(c: Context) = c.getSharedPreferences("pda_distance", Context.MODE_PRIVATE)

    @Synchronized
    fun addMeters(c: Context, meters: Double) {
        val s = sp(c)
        s.edit().putLong("total_mm", s.getLong("total_mm", 0L) + (meters * 1000).toLong()).apply()
    }

    fun totalKm(c: Context): Double = sp(c).getLong("total_mm", 0L) / 1_000_000.0

    fun saveLast(c: Context, lat: Double, lng: Double, time: Long) {
        sp(c).edit()
            .putLong("lat_bits", lat.toRawBits())
            .putLong("lng_bits", lng.toRawBits())
            .putLong("time", time)
            .apply()
    }

    fun loadLast(c: Context): Point? {
        val s = sp(c)
        if (!s.contains("time")) return null
        return Point(
            Double.fromBits(s.getLong("lat_bits", 0L)),
            Double.fromBits(s.getLong("lng_bits", 0L)),
            s.getLong("time", 0L)
        )
    }
}
