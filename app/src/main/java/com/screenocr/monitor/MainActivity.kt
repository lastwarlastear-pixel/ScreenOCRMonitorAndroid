package com.screenocr.monitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.screenocr.monitor.databinding.ActivityMainBinding
import com.screenocr.monitor.firebase.FirebaseSyncManager
import com.screenocr.monitor.model.AppConfig
import com.screenocr.monitor.model.MatchMode
import com.screenocr.monitor.service.OverlayService
import com.screenocr.monitor.service.ScreenCaptureService
import com.screenocr.monitor.utils.PreferencesManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var firebaseSyncManager: FirebaseSyncManager
    private var config = AppConfig()

    private val logBuilder = SpannableStringBuilder()

    // Activity Result Launcher for MediaProjection
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            appendLog("System", "Screen capture permission denied.", "ERROR")
            Toast.makeText(this, "Screen capture permission is required to monitor screen.", Toast.LENGTH_SHORT).show()
        }
    }

    // Activity Result Launcher for Overlay Permission
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStepStatus()
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
            Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            appendLog("System", "Overlay permission not granted.", "ERROR")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)
        config = prefs.loadConfig()

        firebaseSyncManager = FirebaseSyncManager.getInstance(this)
        firebaseSyncManager.onConfigUpdated = { newConfig ->
            runOnUiThread {
                config = newConfig
                loadConfigToUI()
                ScreenCaptureService.instance?.updateConfig(newConfig)
            }
        }
        firebaseSyncManager.initialize { uid ->
            runOnUiThread {
                binding.tvMainUid.text = "Device UID: $uid"
                loadConfigToUI()
            }
        }

        setupUI()
        setupListeners()
        loadConfigToUI()
        updateStepStatus()
    }

    private fun setupUI() {
        // Setup Match Mode Spinner
        val modeNames = MatchMode.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeNames)
        binding.spinnerMatchMode.adapter = adapter
    }

    private fun setupListeners() {
        // Step 1: Permissions
        binding.btnGrantPermissions.setOnClickListener {
            requestInitialPermissions()
            checkAndStartOverlay()
        }

        // Step 2: Region
        binding.btnSelectArea.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                checkAndStartOverlay()
            } else {
                startOverlayService()
                OverlayService.instance?.setBorderVisible(true)
            }
        }

        binding.btnResetRegion.setOnClickListener {
            OverlayService.instance?.resetToFullScreen()
            binding.tvRegionStatus.text = "Region: Full Screen"
            appendLog("Region", "Region reset to Full Screen", "INFO")
            Toast.makeText(this, "Region reset to Full Screen", Toast.LENGTH_SHORT).show()
        }

        binding.switchShowBorder.setOnCheckedChangeListener { _, isChecked ->
            OverlayService.instance?.setBorderVisible(isChecked)
        }

        // Step 3: Target Text
        binding.etPresetTarget.addTextChangedListener {
            updateStepStatus()
        }

        // Match Mode changed
        binding.spinnerMatchMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedMode = MatchMode.entries[position]
                binding.layoutFuzzy.visibility = if (selectedMode == MatchMode.FUZZY_SIMILARITY) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Fuzzy Slider
        binding.sliderFuzzy.addOnChangeListener { _, value, _ ->
            binding.tvFuzzyThreshold.text = "Fuzzy Threshold: ${value.toInt()}%"
        }

        // Step 4: Toggle Monitoring
        binding.btnToggleMonitoring.setOnClickListener {
            val service = ScreenCaptureService.instance
            if (service != null && service.isMonitoring) {
                service.stopMonitoring()
            } else {
                if (validateAndSave()) {
                    requestScreenCaptureAndStart()
                }
            }
        }

        // Stop Alarm
        binding.btnStopAlarm.setOnClickListener {
            ScreenCaptureService.instance?.stopAlarm()
        }

        // Step 5: Clear Logs
        binding.btnClearLogs.setOnClickListener {
            logBuilder.clear()
            binding.tvLogs.text = ""
        }

        // Exit App
        binding.btnExitApp.setOnClickListener {
            exitApp()
        }

        // Live Log Callback
        ScreenCaptureService.onLogReceived = { time, msg, tag ->
            runOnUiThread {
                appendLog(time, msg, tag)
            }
        }

        // Live Service Status Callback
        ScreenCaptureService.onStatusChanged = { isMonitoring, isAlarmRinging ->
            runOnUiThread {
                updateMonitoringUI(isMonitoring, isAlarmRinging)
            }
        }
    }

    private fun validateAndSave(): Boolean {
        saveConfigFromUI()
        
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant Overlay Permission (Step 1) first!", Toast.LENGTH_LONG).show()
            checkAndStartOverlay()
            return false
        }

        if (config.preset.isBlank()) {
            Toast.makeText(this, "Please enter a target text to monitor (Step 3)!", Toast.LENGTH_LONG).show()
            binding.etPresetTarget.requestFocus()
            return false
        }

        // Numeric validation
        if (config.matchMode in listOf(MatchMode.HIGHER_THAN, MatchMode.LOWER_THAN, MatchMode.EQUAL_TO, MatchMode.HIGHER_OR_EQUAL, MatchMode.LOWER_OR_EQUAL)) {
            val targetNum = config.preset.replace(",", "").toDoubleOrNull()
            if (targetNum == null) {
                Toast.makeText(this, "For '${config.matchMode.displayName}', the target must be a valid number!", Toast.LENGTH_LONG).show()
                binding.etPresetTarget.requestFocus()
                return false
            }
        }
        
        return true
    }

    private fun updateStepStatus() {
        // Step 1 status
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasOverlay && hasNotify) {
            binding.tvStep1Status.text = "● OK"
            binding.tvStep1Status.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        } else {
            binding.tvStep1Status.text = "● Required"
            binding.tvStep1Status.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
        }

        // Step 3 status
        val preset = binding.etPresetTarget.text.toString().trim()
        if (preset.isNotEmpty()) {
            binding.tvStep3Status.text = "● Target Set"
            binding.tvStep3Status.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        } else {
            binding.tvStep3Status.text = "● Waiting for target"
            binding.tvStep3Status.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
        }
    }

    private fun loadConfigToUI() {
        binding.etPresetTarget.setText(config.preset)
        binding.spinnerMatchMode.setSelection(config.matchMode.ordinal)
        binding.cbMatchCase.isChecked = config.caseSensitive
        binding.sliderFuzzy.value = config.fuzzyThreshold.toFloat()
        binding.tvFuzzyThreshold.text = "Fuzzy Threshold: ${config.fuzzyThreshold}%"
        binding.etPollInterval.setText(config.pollInterval.toString())
        binding.etCooldown.setText(config.cooldownSec.toString())
        binding.cbSoundAlert.isChecked = config.soundAlert
        binding.cbContinuousBeep.isChecked = config.continuousBeep
        binding.etBeepInterval.setText(config.beepInterval.toString())
        binding.cbToastAlert.isChecked = config.toastAlert
        binding.cbAlertOnce.isChecked = config.alertOnce
        binding.switchShowBorder.isChecked = config.showBorder

        val r = config.selectedRegion
        if (r != null && r.width() > 0) {
            binding.tvRegionStatus.text = "Region: (${r.left},${r.top}) - ${r.width()}x${r.height()}px"
        } else {
            binding.tvRegionStatus.text = "Region: Full Screen"
        }
    }

    private fun saveConfigFromUI() {
        config.preset = binding.etPresetTarget.text.toString().trim()
        config.matchMode = MatchMode.entries[binding.spinnerMatchMode.selectedItemPosition]
        config.caseSensitive = binding.cbMatchCase.isChecked
        config.fuzzyThreshold = binding.sliderFuzzy.value.toInt()
        config.pollInterval = binding.etPollInterval.text.toString().toDoubleOrNull() ?: 1.5
        config.cooldownSec = binding.etCooldown.text.toString().toIntOrNull() ?: 10
        config.soundAlert = binding.cbSoundAlert.isChecked
        config.continuousBeep = binding.cbContinuousBeep.isChecked
        config.beepInterval = binding.etBeepInterval.text.toString().toDoubleOrNull() ?: 0.8
        config.toastAlert = binding.cbToastAlert.isChecked
        config.alertOnce = binding.cbAlertOnce.isChecked
        config.showBorder = binding.switchShowBorder.isChecked

        prefs.saveConfig(config)
        if (::firebaseSyncManager.isInitialized) {
            firebaseSyncManager.pushConfig(config)
        }
        ScreenCaptureService.instance?.updateConfig(config)
    }

    private fun requestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun checkAndStartOverlay() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        startService(intent)
    }

    private fun requestScreenCaptureAndStart() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun updateMonitoringUI(isMonitoring: Boolean, isAlarmRinging: Boolean) {
        if (isMonitoring) {
            binding.btnToggleMonitoring.text = "⏹ STOP MONITORING"
            binding.btnToggleMonitoring.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_red))
            binding.tvStep4Status.text = "● Active"
            binding.tvStep4Status.setTextColor(ContextCompat.getColor(this, R.color.status_ok))
        } else {
            binding.btnToggleMonitoring.text = "▶ START MONITORING"
            binding.btnToggleMonitoring.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_green))
            binding.tvStep4Status.text = "● Stopped"
            binding.tvStep4Status.setTextColor(ContextCompat.getColor(this, R.color.status_error))
        }

        if (isAlarmRinging) {
            binding.btnStopAlarm.isEnabled = true
            binding.btnStopAlarm.text = "🔕 SILENCE NOW!"
            binding.btnStopAlarm.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_red))
            binding.btnStopAlarm.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            binding.btnStopAlarm.isEnabled = false
            binding.btnStopAlarm.text = "🔕 SILENCE"
            binding.btnStopAlarm.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_disabled))
            binding.btnStopAlarm.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun appendLog(time: String, message: String, tag: String) {
        val color = when (tag) {
            "MATCH" -> ContextCompat.getColor(this, R.color.log_match)
            "OCR" -> ContextCompat.getColor(this, R.color.log_ocr)
            "INFO" -> ContextCompat.getColor(this, R.color.log_info)
            "ERROR" -> ContextCompat.getColor(this, R.color.log_error)
            else -> ContextCompat.getColor(this, R.color.log_text)
        }

        // Cap logs at 50 lines to prevent memory bloat
        val lines = logBuilder.toString().split("\n")
        if (lines.size > 50) {
            logBuilder.clear()
            lines.takeLast(50).forEach { line ->
                if (line.isNotBlank()) logBuilder.append(line).append("\n")
            }
        }

        val start = logBuilder.length
        val logLine = "[$time] $message\n"
        logBuilder.append(logLine)
        logBuilder.setSpan(ForegroundColorSpan(color), start, logBuilder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.tvLogs.text = logBuilder
        binding.scrollLogs.post {
            binding.scrollLogs.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStepStatus()
        ScreenCaptureService.instance?.let { service ->
            updateMonitoringUI(service.isMonitoring, false)
        }
    }

    private fun exitApp() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        finishAffinity()
    }
}
