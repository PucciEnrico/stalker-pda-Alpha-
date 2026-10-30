package com.stalker.pda

import android.app.Application
import com.stalker.pda.service.DistanceWorker
import com.stalker.pda.ui.components.initSoundPool
import com.stalker.pda.viewmodel.PdaViewModel

/**
 * The PDA "core" (LAN node, calls, chat, quests) lives here, not in the activity,
 * so messages and calls keep arriving while the screen is closed.
 */
class PdaApplication : Application() {
    companion object {
        @Volatile
        var foreground = false
    }

    lateinit var core: PdaViewModel
        private set

    override fun onCreate() {
        super.onCreate()
        initSoundPool(this)
        Notifier.init(this)
        DistanceWorker.schedule(this)
        core = PdaViewModel(this)
    }
}
