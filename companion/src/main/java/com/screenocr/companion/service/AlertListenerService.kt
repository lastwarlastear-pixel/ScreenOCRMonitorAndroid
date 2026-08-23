package com.screenocr.companion.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.screenocr.companion.MainActivity
import com.screenocr.companion.firebase.FirebaseSyncManager

class AlertListenerService : Service() {

    private val channelId = "companion_service"
    private val alertChannelId = "companion_alerts"
    private val notificationId = 100
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceId = intent?.getStringExtra("device_id")
        
        // Foreground service requirement
        val notification = buildForegroundNotification(deviceId ?: "Unknown Device")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }

        if (deviceId != null) {
            FirebaseSyncManager.getInstance().connectToDevice(deviceId, {
                // Config updates can be ignored here, Activity handles them
            }, { alertMsg ->
                Log.d("AlertListenerService", "Alert received: $alertMsg")
                triggerAlarm(alertMsg)
            })
        }

        return START_STICKY
    }

    private fun triggerAlarm(message: String) {
        // Wake up the CPU
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OCRCompanion:AlarmLock")
        wakeLock?.acquire(10000) // Acquire for 10 seconds

        // Update notification to show alert
        showMatchNotification(message)

        // Play sound
        playAlarmSound()
    }

    private fun playAlarmSound() {
        val prefs = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
        val soundUriStr = prefs.getString("alert_sound_uri", null)
        val soundUri = if (soundUriStr != null) Uri.parse(soundUriStr) else 
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlertListenerService, soundUri)
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setLooping(false)
                prepare()
                start()
                setOnCompletionListener { 
                    it.release() 
                    mediaPlayer = null
                }
            }
        } catch (e: Exception) {
            Log.e("AlertListenerService", "Failed to play sound", e)
            // Fallback to system default if custom fails
            playDefaultSound()
        }
    }

    private fun playDefaultSound() {
        try {
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            MediaPlayer.create(this, defaultUri)?.start()
        } catch (e: Exception) {
            Log.e("AlertListenerService", "Fallback sound failed", e)
        }
    }

    private fun showMatchNotification(message: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, alertChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 OCR MATCH DETECTED")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(200, notification)
    }

    private fun buildForegroundNotification(deviceId: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("OCR Monitoring Active")
            .setContentText("Connected to: $deviceId")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val serviceChannel = NotificationChannel(channelId, "Monitoring Service", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(serviceChannel)

            val alertChannel = NotificationChannel(alertChannelId, "Match Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Urgent alerts for OCR matches"
                enableLights(true)
                enableVibration(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FirebaseSyncManager.getInstance().cleanup()
        mediaPlayer?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
    }
}
