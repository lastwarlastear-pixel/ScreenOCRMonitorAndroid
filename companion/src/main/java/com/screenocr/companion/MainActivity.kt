package com.screenocr.companion

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.screenocr.companion.databinding.ActivityMainBinding
import com.screenocr.companion.firebase.FirebaseSyncManager
import com.screenocr.companion.model.AppConfig

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var firebaseSyncManager: FirebaseSyncManager
    private var currentConfig: AppConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseSyncManager = FirebaseSyncManager.getInstance()
        firebaseSyncManager.initialize {
            // Ready
        }

        binding.btnConnect.setOnClickListener {
            val uid = binding.etTargetUid.text.toString().trim()
            if (uid.isNotEmpty()) {
                connectToDevice(uid)
            } else {
                Toast.makeText(this, "Please enter a Device UID", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveRemote.setOnClickListener {
            saveRemoteConfig()
        }
    }

    private fun connectToDevice(uid: String) {
        firebaseSyncManager.connectToDevice(uid, { config ->
            runOnUiThread {
                currentConfig = config
                binding.layoutSettings.visibility = View.VISIBLE
                
                binding.etRemotePreset.setText(config.preset)
                binding.etRemoteInterval.setText(config.pollInterval.toString())
                binding.etRemoteCooldown.setText(config.cooldownSec.toString())
                binding.cbRemoteSound.isChecked = config.soundAlert
                binding.cbRemoteContinuous.isChecked = config.continuousBeep
                binding.etRemoteBeepInterval.setText(config.beepInterval.toString())
                
                Toast.makeText(this, "Synced with $uid", Toast.LENGTH_SHORT).show()
            }
        }, { alertMsg ->
            runOnUiThread {
                Toast.makeText(this, "🚨 ALERT: $alertMsg", Toast.LENGTH_LONG).show()
                // Also trigger a system notification for background visibility
                showLocalNotification(alertMsg)
            }
        })
    }

    private fun showLocalNotification(message: String) {
        val channelId = "companion_alerts"
        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "OCR Alerts", android.app.NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Match Detected!")
            .setContentText(message)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun saveRemoteConfig() {
        val config = currentConfig ?: return
        
        config.preset = binding.etRemotePreset.text.toString().trim()
        config.pollInterval = binding.etRemoteInterval.text.toString().toDoubleOrNull() ?: 1.5
        config.cooldownSec = binding.etRemoteCooldown.text.toString().toIntOrNull() ?: 10
        config.soundAlert = binding.cbRemoteSound.isChecked
        config.continuousBeep = binding.cbRemoteContinuous.isChecked
        config.beepInterval = binding.etRemoteBeepInterval.text.toString().toDoubleOrNull() ?: 0.8
        
        firebaseSyncManager.updateRemoteConfig(config)
        Toast.makeText(this, "Remote configuration sent to device", Toast.LENGTH_SHORT).show()
    }
}
