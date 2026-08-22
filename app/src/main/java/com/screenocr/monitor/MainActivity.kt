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
import com.screenocr.monitor.databinding.ActivityMainBinding
import com.screenocr.monitor.model.AppConfig
import com.screenocr.monitor.model.MatchMode
import com.screenocr.monitor.service.OverlayService
import com.screenocr.monitor.service.ScreenCaptureService
import com.screenocr.monitor.utils.PreferencesManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private var config = AppConfig()

    private val logBuilder = SpannableStringBuilder()

    // Activity Result Launcher for MediaProjection
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startCaptureService(result.resultCode, result.data!!)
        } else {
            appendLog("System", "Screen capture permission denied by user.", "ERROR")
            Toast.makeText(this, "Screen capture permission is required to monitor screen.", Toast.LENGTH_SHORT).show()
        }
    }

    // Activity Result Launcher for Overlay Permission
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
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

        setupUI()
        setupListeners()
        requestInitialPermissions()
        loadConfigToUI()
    }

    private fun setupUI() {
        // Setup Match Mode Spinner
        val modeNames = MatchMode.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeNames)
        binding.spinnerMatchMode.adapter = adapter
    }

    private fun setupListeners() {
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

        // Overlay & Region Buttons
        binding.btnSelectArea.setOnClickListener {
            checkAndStartOverlay()
        }

        binding.btnResetRegion.setOnClickListener {
            OverlayService.instance?.resetToFullScreen()
            binding.tvRegionStatus.text = "Region: Full Screen"
            appendLog("Region", "Region reset to Full Screen", "INFO")
        }

        binding.switchShowBorder.setOnCheckedChangeListener { _, isChecked ->
            OverlayService.instance?.setBorderVisible(isChecked)
        }

        // Start / Stop Monitoring
        binding.btnToggleMonitoring.setOnClickListener {
            val service = ScreenCaptureService.instance
            if (service != null && service.isMonitoring) {
                service.stopMonitoring()
            } else {
                saveConfigFromUI()
                if (config.preset.isBlank()) {
                    Toast.makeText(this, "Enter a target preset to monitor for!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                requestScreenCaptureAndStart()
            }
        }

        // Stop Alarm
        binding.btnStopAlarm.setOnClickListener {
            ScreenCaptureService.instance?.stopAlarm()
        }

        // Clear Logs
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
        Toast.makeText(this, "Floating Overlay & Border activated!", Toast.LENGTH_SHORT).show()
    }

    private fun requestScreenCaptureAndStart() {
        checkAndStartOverlay()
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
        } else {
            binding.btnToggleMonitoring.text = "▶ START MONITORING"
            binding.btnToggleMonitoring.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_green))
        }

        if (isAlarmRinging) {
            binding.btnStopAlarm.isEnabled = true
            binding.btnStopAlarm.text = "🔕 STOP ALARM (RINGING!)"
            binding.btnStopAlarm.setBackgroundColor(ContextCompat.getColor(this, R.color.btn_red))
            binding.btnStopAlarm.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            binding.btnStopAlarm.isEnabled = false
            binding.btnStopAlarm.text = "🔕 STOP ALARM"
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
        ScreenCaptureService.instance?.let { service ->
            updateMonitoringUI(service.isMonitoring, false)
        }
    }

    private fun exitApp() {
        // Stop all services
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, OverlayService::class.java))

        // Finish activity
        finishAffinity()
    }
}
