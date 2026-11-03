package com.stalker.pda.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.stalker.pda.R

private var soundPool: SoundPool? = null
private val loadedSounds = mutableMapOf<Int, Int>()

fun initSoundPool(context: Context) {
    if (soundPool != null) return
    val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
    listOf(R.raw.pda_click, R.raw.pda_objective, R.raw.beep_pda).forEach { res ->
        loadedSounds[res] = pool.load(context, res, 1)
    }
    soundPool = pool
}

fun playSound(context: Context, resId: Int, volume: Float = 0.8f) {
    if (soundPool == null) initSoundPool(context.applicationContext)
    val id = loadedSounds[resId] ?: return
    soundPool?.play(id, volume, volume, 1, 0, 1f)
}
