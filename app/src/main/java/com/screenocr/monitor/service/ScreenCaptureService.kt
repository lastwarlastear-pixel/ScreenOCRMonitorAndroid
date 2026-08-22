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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.screenocr.monitor.MainActivity
import com.screenocr.monitor.R
import com.screenocr.monitor.model.AppConfig
import com.screenocr.monitor.ocr.MLKitOcrEngine
import com.screenocr.monitor.ocr.MatchEvaluator
import com.screenocr.monitor.utils.PreferencesManager
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class ScreenCaptureService : LifecycleService() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var prefs: PreferencesManager
    private lateinit var alarmPlayer: AlarmPlayer
    private val ocrEngine = MLKitOcrEngine()

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 400

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
            updateNotification()
            onStatusChanged?.invoke(isMonitoring, ringing)
            OverlayService.instance?.updateStatusUI(isMonitoring, ringing, if (ringing) "ALARM RINGING" else (if (isMonitoring) "Monitoring" else "Stopped"))
        }

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForegroundWithNotification()
                    initMediaProjection(resultCode, data)
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = buildNotification(if (isMonitoring) "Monitoring active" else "Monitoring stopped", alarmPlayer.isAlarmRinging)
        manager.notify(NOTIFICATION_ID, notif)
    }

    @SuppressLint("WrongConstant")
    private fun initMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

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
        updateNotification()
        onStatusChanged?.invoke(true, alarmPlayer.isAlarmRinging)
        OverlayService.instance?.updateStatusUI(true, alarmPlayer.isAlarmRinging, "Monitoring...")

        monitoringJob?.cancel()
        monitoringJob = lifecycleScope.launch(Dispatchers.Default) {
            while (isMonitoring && isActive) {
                try {
                    val frameBitmap = captureScreenRegion()
                    if (frameBitmap != null) {
                        val ocrText = ocrEngine.recognizeText(frameBitmap)
                        if (ocrText.isNotBlank()) {
                            val cleanDisplay = ocrText.replace('\n', ' ')
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
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
            if (image == null) return null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            val region = appConfig.selectedRegion
            if (region != null && region.width() > 10 && region.height() > 10) {
                val safeLeft = max(0, minOf(region.left, screenWidth - 1))
                val safeTop = max(0, minOf(region.top, screenHeight - 1))
                val safeWidth = minOf(region.width(), screenWidth - safeLeft)
                val safeHeight = minOf(region.height(), screenHeight - safeTop)

                if (safeWidth > 10 && safeHeight > 10) {
                    val cropped = Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeWidth, safeHeight)
                    fullBitmap.recycle()
                    return cropped
                }
            }
            return fullBitmap
        } catch (e: Exception) {
            return null
        } finally {
            image?.close()
        }
    }

    private fun triggerMatchAlert() {
        if (appConfig.soundAlert) {
            alarmPlayer.triggerAlarm(appConfig.continuousBeep, appConfig.beepInterval)
        }
    }

    fun stopMonitoring() {
        if (!isMonitoring) return
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        stopAlarm()
        log("Stopped monitoring.", "INFO")
        updateNotification()
        onStatusChanged?.invoke(false, false)
        OverlayService.instance?.updateStatusUI(false, false, "Stopped")
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
        log(if (rect != null) "Region updated: (${rect.left},${rect.top}) - ${rect.width()}x${rect.height()}px" else "Region set to Full Screen", "INFO")
    }

    fun updateConfig(config: AppConfig) {
        this.appConfig = config
    }

    private fun log(message: String, tag: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        onLogReceived?.invoke(time, message, tag)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
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
