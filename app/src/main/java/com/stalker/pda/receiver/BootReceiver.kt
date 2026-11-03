package com.stalker.pda.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.stalker.pda.service.DistanceWorker

/**
 * After reboot only WorkManager is re-armed. The foreground GPS service is started by
 * MainActivity (Android 14+ forbids starting location services from BOOT_COMPLETED);
 * as the Home app, MainActivity is launched by the system right after boot anyway.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DistanceWorker.schedule(context)
    }
}
