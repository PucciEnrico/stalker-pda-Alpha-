package com.stalker.pda.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.stalker.pda.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground GPS service: feeds the UI (friend radar) and counts pedestrian distance. */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocationService"
        private const val CHANNEL_ID = "pda_location"
        private const val NOTIFICATION_ID = 1001

        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val FASTEST_INTERVAL_MS = 5_000L
        private const val MIN_DISPLACEMENT_M = 5f

        private const val MAX_ACCURACY_M = 40f
        private const val MIN_STEP_M = 3.0
        private const val MAX_GAP_S = 60.0
        private const val MAX_WALK_SPEED_MS = 4.0 // ~14 km/h: faster = not walking

        @Volatile
        var isRunning = false
            private set

        private val _locationFlow = MutableStateFlow<Location?>(null)
        val locationFlow: StateFlow<Location?> = _locationFlow.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java))
        }
    }

    private lateinit var client: FusedLocationProviderClient
    private var lastFix: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            handleFix(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        requestUpdates()
        return START_STICKY
    }

    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISPLACEMENT_M)
            .build()
        try {
            client.removeLocationUpdates(callback)
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        }
    }

    private fun handleFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return

        val prev = lastFix
        if (prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            val d = prev.distanceTo(loc).toDouble()
            if (dt in 1.0..MAX_GAP_S && d >= MIN_STEP_M && d / dt <= MAX_WALK_SPEED_MS) {
                DistanceStore.addMeters(applicationContext, d)
            }
        }
        lastFix = loc
        DistanceStore.saveLast(applicationContext, loc.latitude, loc.longitude, loc.time)
        _locationFlow.value = loc
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "PDA GPS", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PDA ACTIVE")
            .setContentText("GPS radar online")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        isRunning = false
        super.onDestroy()
    }
}
