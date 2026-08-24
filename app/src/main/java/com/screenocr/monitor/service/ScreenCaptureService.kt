package com.screenocr.monitor.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.screenocr.monitor.MainActivity
import com.screenocr.monitor.R
import com.screenocr.monitor.model.AppConfig
import com.screenocr.monitor.model.MatchMode
import com.screenocr.monitor.ocr.MLKitOcrEngine
import com.screenocr.monitor.ocr.MatchEvaluator
import com.screenocr.monitor.utils.PreferencesManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

class ScreenCaptureService : LifecycleService() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionResultCode: Int = Activity.RESULT_CANCELED
    private var projectionData: Intent? = null

    private lateinit var prefs: PreferencesManager
    private lateinit var alarmPlayer: AlarmPlayer
    private val ocrEngine = MLKitOcrEngine()

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 400

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (isMonitoring) {
                val oldW = screenWidth
                val oldH = screenHeight
                val oldD = screenDensity
                
                updateScreenDimensions()
                
                // Only re-init if dimensions or density actually changed to avoid infinite loop
                if (screenWidth != oldW || screenHeight != oldH || screenDensity != oldD) {
                    log("Display dimensions changed, re-initializing virtual display...", "INFO")
                    reinitVirtualDisplay()
                }
            }
        }
    }

    @Volatile var isMonitoring = false
        private set

    private var monitoringJob: Job? = null
    private var appConfig = AppConfig()
    private var lastMatchedText = ""
    private var lastAlertTime = 0L

    companion object {
        const val CHANNEL_ID = "screen_ocr_monitor_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_SILENCE = "ACTION_SILENCE"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"

        var instance: ScreenCaptureService? = null
            private set

        var onLogReceived: ((time: String, message: String, tag: String) -> Unit)? = null
        var onStatusChanged: ((isMonitoring: Boolean, isAlarmRinging: Boolean) -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = PreferencesManager(this)
        appConfig = prefs.loadConfig()
        alarmPlayer = AlarmPlayer(this)

        alarmPlayer.onAlarmStateChanged = { ringing ->
            lifecycleScope.launch(Dispatchers.Main) {
                updateNotification()
                onStatusChanged?.invoke(isMonitoring, ringing)
                OverlayService.instance?.updateStatusUI(isMonitoring, ringing, if (ringing) "ALARM RINGING" else (if (isMonitoring) "Monitoring" else "Stopped"))
            }
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        updateScreenDimensions()

        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        createNotificationChannel()
    }

    private fun updateScreenDimensions() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        log("Updated dimensions: $screenWidth x $screenHeight ($screenDensity dpi)", "INFO")
    }

    private fun reinitVirtualDisplay() {
        synchronized(this) {
            try {
                virtualDisplay?.release()
                imageReader?.close()
                virtualDisplay = null
                imageReader = null
                
                val resultCode = projectionResultCode
                val data = projectionData
                if (resultCode == Activity.RESULT_OK && data != null) {
                    initMediaProjection(resultCode, data)
                }
            } catch (e: Exception) {
                log("Re-init error: ${e.message}", "ERROR")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                projectionResultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                projectionData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (projectionResultCode == Activity.RESULT_OK && projectionData != null) {
                    startForegroundWithNotification()
                    initMediaProjection(projectionResultCode, projectionData!!)
                    startMonitoring()
                }
            }
            ACTION_STOP -> {
                stopMonitoring()
                stopSelf()
            }
            ACTION_SILENCE -> {
                stopAlarm()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen OCR Monitoring Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live OCR monitoring status and alarm alerts"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification("Monitoring active", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(statusText: String, isAlarmRinging: Boolean): Notification {
        val mainIntent = Intent(this, MainActivity::class.java)
        val pMain = PendingIntent.getActivity(this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val silenceIntent = Intent(this, ScreenCaptureService::class.java).apply { action = ACTION_SILENCE }
        val pSilence = PendingIntent.getService(this, 2, silenceIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_crop)
            .setContentTitle("Screen OCR Monitor")
            .setContentText(statusText)
            .setContentIntent(pMain)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, "Stop", pStop)

        if (isAlarmRinging) {
            builder.addAction(R.drawable.ic_silence, "🔕 Silence Alarm", pSilence)
            builder.setContentText("💥 TARGET MATCHED! Alarm Ringing!")
            builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }

        return builder.build()
    }

    private fun updateNotification() {
        lifecycleScope.launch(Dispatchers.Main) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = buildNotification(if (isMonitoring) "Monitoring active" else "Monitoring stopped", alarmPlayer.isAlarmRinging)
            manager.notify(NOTIFICATION_ID, notif)
        }
    }

    @SuppressLint("WrongConstant")
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        try {
            if (mediaProjection == null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            }

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenOCRMonitorDisplay",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )
            log("Screen capture display initialized ($screenWidth x $screenHeight)", "INFO")
        } catch (e: Exception) {
            log("MediaProjection initialization error: ${e.message}", "ERROR")
        }
    }

    fun startMonitoring() {
        if (isMonitoring) return
        if (!::prefs.isInitialized) {
            prefs = PreferencesManager(this)
        }
        appConfig = prefs.loadConfig()

        if (appConfig.preset.isBlank()) {
            log("Warning: Enter target preset text/number before starting.", "ERROR")
            return
        }

        isMonitoring = true
        log("Started monitoring. Target: '${appConfig.preset}' [${appConfig.matchMode.displayName}]", "INFO")
        lifecycleScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(this@ScreenCaptureService, "Monitoring Started", android.widget.Toast.LENGTH_SHORT).show()
            updateNotification()
            onStatusChanged?.invoke(true, alarmPlayer.isAlarmRinging)
            OverlayService.instance?.updateStatusUI(true, alarmPlayer.isAlarmRinging, "Monitoring...")
        }

        monitoringJob?.cancel()
        monitoringJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isMonitoring && isActive) {
                try {
                    val frameBitmap = captureScreenRegion()
                    if (frameBitmap != null) {
                        try {
                            val ocrText = ocrEngine.recognizeText(frameBitmap)
                            
                            // Update floating bar with latest OCR (or clear if empty)
                            val cleanDisplay = ocrText.replace('\n', ' ').trim()
                            val previewText = if (cleanDisplay.isBlank()) "Reading..." else cleanDisplay
                            withContext(Dispatchers.Main) {
                                OverlayService.instance?.updateStatusUI(isMonitoring, alarmPlayer.isAlarmRinging, "Monitoring", previewText)
                            }

                            if (ocrText.isNotBlank()) {
                                val disp = if (cleanDisplay.length > 60) cleanDisplay.substring(0, 57) + "..." else cleanDisplay
                                log("OCR: \"$disp\"", "OCR")

                                val matched = MatchEvaluator.evaluateMatch(
                                    text = ocrText,
                                    preset = appConfig.preset,
                                    mode = appConfig.matchMode,
                                    caseSensitive = appConfig.caseSensitive,
                                    fuzzyThreshold = appConfig.fuzzyThreshold
                                )

                                if (matched) {
                                    // For numeric modes, log the numbers found to help user debug
                                    if (appConfig.matchMode in listOf(MatchMode.HIGHER_THAN, MatchMode.LOWER_THAN, MatchMode.EQUAL_TO, MatchMode.HIGHER_OR_EQUAL, MatchMode.LOWER_OR_EQUAL)) {
                                        val nums = MatchEvaluator.extractNumbers(ocrText)
                                        log("Numeric Match Triggered! Found: $nums vs Target: ${appConfig.preset}", "INFO")
                                    }
                                    
                                    val now = System.currentTimeMillis()
                                    val cooldownMs = appConfig.cooldownSec * 1000L
                                    val shouldAlert = (!appConfig.alertOnce || ocrText != lastMatchedText) && (now - lastAlertTime > cooldownMs)

                                    if (shouldAlert) {
                                        lastMatchedText = ocrText
                                        lastAlertTime = now
                                        log("💥 MATCH DETECTED [${appConfig.matchMode.displayName}]: '${appConfig.preset}' found in \"$disp\"", "MATCH")
                                        triggerMatchAlert()
                                    }
                                }
                            }
                        } finally {
                            frameBitmap.recycle()
                        }
                    }
                } catch (e: Exception) {
                    log("OCR Loop error: ${e.message}", "ERROR")
                }

                val pollDelay = (maxOf(0.2, appConfig.pollInterval) * 1000).toLong()
                delay(pollDelay)
            }
        }
    }

    private fun captureScreenRegion(): Bitmap? {
        synchronized(this) {
            val reader = imageReader ?: return null
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) return null

                val planes = image.planes
                val buffer: ByteBuffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                
                // Re-calculate width based on buffer stride to be safe
                val actualBufferWidth = rowStride / pixelStride

                val fullBitmap = Bitmap.createBitmap(
                    actualBufferWidth,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)

                // If border is shown, strictly capture the selected region
            if (appConfig.showBorder) {
                val region = appConfig.selectedRegion
                if (region != null && region.width() > 5 && region.height() > 5) {
                    val safeLeft = maxOf(0, minOf(region.left, screenWidth - 1))
                    val safeTop = maxOf(0, minOf(region.top, screenHeight - 1))
                    val safeWidth = minOf(region.width(), screenWidth - safeLeft)
                    val safeHeight = minOf(region.height(), screenHeight - safeTop)

                    if (safeWidth > 5 && safeHeight > 5) {
                        val cropped = Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeWidth, safeHeight)
                        fullBitmap.recycle()
                        return cropped
                    }
                }
                // If border is enabled but region is invalid, don't fallback to full screen to avoid "leaks"
                return null
            }

                // Return entire screen (cropping out stride padding)
                val screenOnly = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
                fullBitmap.recycle()
                return screenOnly
            } catch (e: Exception) {
                return null
            } finally {
                image?.close()
            }
        }
    }

    private fun triggerMatchAlert() {
        // Main app is now silent as requested. No local sound or vibration.
        // alarmPlayer.triggerAlarm(appConfig.continuousBeep, appConfig.beepInterval)
        
        com.screenocr.monitor.firebase.FirebaseSyncManager.getInstance(this).pushAlert("Target matched: ${appConfig.preset}")
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        stopAlarm()
        log("Stopped monitoring.", "INFO")
        lifecycleScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(this@ScreenCaptureService, "Monitoring Stopped", android.widget.Toast.LENGTH_SHORT).show()
            updateNotification()
            onStatusChanged?.invoke(false, false)
            OverlayService.instance?.updateStatusUI(false, false, "Stopped")
        }
    }

    fun stopAlarm() {
        if (::alarmPlayer.isInitialized) {
            alarmPlayer.stopAlarm()
        }
        log("🔕 Alarm silenced.", "INFO")
        updateNotification()
    }

    fun updateRegion(rect: Rect?) {
        appConfig.selectedRegion = rect
        // Also update showBorder based on whether rect is null (optional, but keep it in sync with Overlay logic)
        appConfig.showBorder = (rect != null)
        log(if (rect != null) "Region updated: (${rect.left},${rect.top}) - ${rect.width()}x${rect.height()}px" else "Region set to Full Screen", "INFO")
    }

    fun updateConfig(config: AppConfig) {
        this.appConfig = config
    }

    private fun log(message: String, tag: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            onLogReceived?.invoke(time, message, tag)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.unregisterDisplayListener(displayListener)
        
        if (::alarmPlayer.isInitialized) {
            alarmPlayer.release()
        }
        ocrEngine.close()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        instance = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
