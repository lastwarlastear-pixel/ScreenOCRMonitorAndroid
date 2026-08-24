package com.screenocr.monitor.service

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import kotlinx.coroutines.*

class AlarmPlayer(private val context: Context) {
    private var toneGenerator: ToneGenerator? = null
    private var alarmJob: Job? = null
    @Volatile var isAlarmRinging = false
        private set

    var onAlarmStateChanged: ((Boolean) -> Unit)? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, 100)
        } catch (e: Exception) {
            try {
                toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            } catch (e: Exception) {
                // Tone generator init failed
            }
        }
    }

    private fun playBeepTone() {
        try {
            // Main app is now silent (no sound or vibration)
            // toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 350)
            
            /* 
            // VIBRATION REMOVED as requested
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200)
            }
            */
        } catch (e: Exception) {
            // ignore
        }
    }

    fun triggerAlarm(continuous: Boolean, beepIntervalSeconds: Double) {
        if (continuous) {
            if (isAlarmRinging) return
            isAlarmRinging = true
            onAlarmStateChanged?.invoke(true)

            alarmJob?.cancel()
            alarmJob = CoroutineScope(Dispatchers.Default).launch {
                while (isAlarmRinging && isActive) {
                    playBeepTone()
                    
                    val sleepMs = (maxOf(0.05, beepIntervalSeconds) * 1000).toLong()
                    val start = System.currentTimeMillis()
                    while (isAlarmRinging && isActive && (System.currentTimeMillis() - start < sleepMs)) {
                        delay(50)
                    }
                }
            }
        } else {
            CoroutineScope(Dispatchers.Default).launch {
                playBeepTone()
            }
        }
    }

    fun stopAlarm() {
        if (isAlarmRinging) {
            isAlarmRinging = false
            alarmJob?.cancel()
            alarmJob = null
            onAlarmStateChanged?.invoke(false)
        }
    }

    fun release() {
        stopAlarm()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            // ignore
        }
    }
}
