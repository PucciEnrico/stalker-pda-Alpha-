package com.stalker.pda.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Battery-friendly distance tracking: one low-power fix every ~15 min while the
 * foreground service is NOT running. When the service runs it already counts distance.
 */
class DistanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "pda_distance_tracking"
        private const val MAX_AVG_SPEED_MS = 2.0 // straight-line average between samples; faster = vehicle
        private const val MIN_STEP_M = 20f

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DistanceWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }

    override suspend fun doWork(): Result {
        if (LocationService.isRunning) return Result.success()

        val granted = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.success()

        return try {
            val client = LocationServices.getFusedLocationProviderClient(applicationContext)
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: return Result.success()

            val prev = DistanceStore.loadLast(applicationContext)
            if (prev != null) {
                val out = FloatArray(1)
                Location.distanceBetween(prev.lat, prev.lng, loc.latitude, loc.longitude, out)
                val dt = (loc.time - prev.time) / 1000.0
                if (dt > 0 && out[0] >= MIN_STEP_M && out[0] / dt <= MAX_AVG_SPEED_MS) {
                    DistanceStore.addMeters(applicationContext, out[0].toDouble())
                }
            }
            DistanceStore.saveLast(applicationContext, loc.latitude, loc.longitude, loc.time)
            Result.success()
        } catch (e: SecurityException) {
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
