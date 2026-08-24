package com.screenocr.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.screenocr.companion.databinding.ActivityMainBinding
import com.screenocr.companion.firebase.FirebaseSyncManager
import com.screenocr.companion.model.AppConfig
import com.screenocr.companion.model.MatchMode
import com.screenocr.companion.service.AlertListenerService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var firebaseSyncManager: FirebaseSyncManager
    private var currentRemoteConfig: AppConfig? = null
    private var isMonitoring = false

    private val selectSoundLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            saveLocalPreference("alert_sound_uri", it.toString())
            updateSoundDisplayName(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firebaseSyncManager = FirebaseSyncManager.getInstance()
        firebaseSyncManager.initialize {
            // Ready
        }

        setupUI()
        loadSavedSettings()
        
        // Auto-reconnect if it was monitoring
        if (getSharedPreferences("companion_prefs", Context.MODE_PRIVATE).getBoolean("was_monitoring", false)) {
            val uid = binding.etTargetUid.text.toString().trim()
            if (uid.isNotEmpty()) {
                startMonitoring(uid)
            }
        }
    }

    private fun setupUI() {
        // Match Mode Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, MatchMode.values().map { it.displayName })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerMatchMode.adapter = adapter

        // Fuzzy Threshold SeekBar
        binding.sbFuzzyThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvFuzzyThreshold.text = "Fuzzy Threshold: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Buttons
        binding.btnToggleConnection.setOnClickListener {
            toggleMonitoring()
        }

        binding.btnSelectSound.setOnClickListener {
            selectSoundLauncher.launch(arrayOf("audio/*"))
        }

        binding.btnUpdateRemote.setOnClickListener {
            pushConfigToRemote()
        }
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
        
        // UID
        val savedUid = prefs.getString("target_uid", "")
        binding.etTargetUid.setText(savedUid)

        // Sound URI
        val soundUriStr = prefs.getString("alert_sound_uri", null)
        if (soundUriStr != null) {
            updateSoundDisplayName(Uri.parse(soundUriStr))
        }

        // Restore last known remote config from prefs if available
        val lastPreset = prefs.getString("last_preset", "")
        val lastMode = prefs.getInt("last_mode", MatchMode.CONTAINS.ordinal)
        val lastInterval = prefs.getString("last_interval", "1.5")
        val lastCooldown = prefs.getString("last_cooldown", "10")
        
        binding.etRemotePreset.setText(lastPreset)
        binding.spinnerMatchMode.setSelection(lastMode)
        binding.etInterval.setText(lastInterval)
        binding.etCooldown.setText(lastCooldown)
        binding.cbCaseSensitive.isChecked = prefs.getBoolean("last_case", false)
        binding.sbFuzzyThreshold.progress = prefs.getInt("last_fuzzy", 80)
        binding.cbSoundAlert.isChecked = prefs.getBoolean("last_sound", true)
        binding.cbContinuousBeep.isChecked = prefs.getBoolean("last_beep", false)
    }

    private fun toggleMonitoring() {
        val uid = binding.etTargetUid.text.toString().trim()
        if (uid.isEmpty()) {
            Toast.makeText(this, "Please enter a Device UID", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isMonitoring) {
            startMonitoring(uid)
        } else {
            stopMonitoring()
        }
    }

    private fun startMonitoring(uid: String) {
        saveLocalPreference("target_uid", uid)
        saveLocalPreference("was_monitoring", true)
        
        val serviceIntent = Intent(this, AlertListenerService::class.java).apply {
            putExtra("device_id", uid)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        firebaseSyncManager.connectToDevice(uid, { config ->
            runOnUiThread {
                updateRemoteConfigUI(config)
            }
        }, { /* alerts handled by service */ })

        isMonitoring = true
        binding.btnToggleConnection.text = "Stop Monitoring"
        binding.tvStatus.text = "Status: Monitoring $uid"
        binding.cardRemoteConfig.visibility = View.VISIBLE
    }

    private fun stopMonitoring() {
        stopService(Intent(this, AlertListenerService::class.java))
        saveLocalPreference("was_monitoring", false)
        
        isMonitoring = false
        binding.btnToggleConnection.text = "Start Monitoring"
        binding.tvStatus.text = "Status: Disconnected"
        binding.cardRemoteConfig.visibility = View.GONE
    }

    private fun updateRemoteConfigUI(config: AppConfig) {
        currentRemoteConfig = config
        binding.etRemotePreset.setText(config.preset)
        binding.spinnerMatchMode.setSelection(config.matchMode.ordinal)
        binding.cbCaseSensitive.isChecked = config.caseSensitive
        binding.sbFuzzyThreshold.progress = config.fuzzyThreshold
        binding.etInterval.setText(config.pollInterval.toString())
        binding.etCooldown.setText(config.cooldownSec.toString())
        binding.cbSoundAlert.isChecked = config.soundAlert
        binding.cbContinuousBeep.isChecked = config.continuousBeep

        // Persist these locally as "last known"
        getSharedPreferences("companion_prefs", Context.MODE_PRIVATE).edit().apply {
            putString("last_preset", config.preset)
            putInt("last_mode", config.matchMode.ordinal)
            putBoolean("last_case", config.caseSensitive)
            putInt("last_fuzzy", config.fuzzyThreshold)
            putString("last_interval", config.pollInterval.toString())
            putString("last_cooldown", config.cooldownSec.toString())
            putBoolean("last_sound", config.soundAlert)
            putBoolean("last_beep", config.continuousBeep)
            apply()
        }
    }

    private fun pushConfigToRemote() {
        val config = currentRemoteConfig ?: AppConfig()
        
        config.preset = binding.etRemotePreset.text.toString().trim()
        config.matchMode = MatchMode.values()[binding.spinnerMatchMode.selectedItemPosition]
        config.caseSensitive = binding.cbCaseSensitive.isChecked
        config.fuzzyThreshold = binding.sbFuzzyThreshold.progress
        config.pollInterval = binding.etInterval.text.toString().toDoubleOrNull() ?: 1.5
        config.cooldownSec = binding.etCooldown.text.toString().toIntOrNull() ?: 10
        config.soundAlert = binding.cbSoundAlert.isChecked
        config.continuousBeep = binding.cbContinuousBeep.isChecked

        firebaseSyncManager.updateRemoteConfig(config)
        Toast.makeText(this, "Configuration pushed to main app", Toast.LENGTH_SHORT).show()
    }

    private fun saveLocalPreference(key: String, value: String) {
        getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }

    private fun saveLocalPreference(key: String, value: Boolean) {
        getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun updateSoundDisplayName(uri: Uri) {
        var name = "Default Alarm"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = cursor.getString(index)
                }
            }
        } catch (e: Exception) {
            name = "Custom Audio File"
        }
        binding.tvSelectedSound.text = name
    }
}
