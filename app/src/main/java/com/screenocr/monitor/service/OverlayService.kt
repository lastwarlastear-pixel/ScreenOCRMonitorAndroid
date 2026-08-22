package com.screenocr.monitor.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.view.ContextThemeWrapper
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.IBinder
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.screenocr.monitor.R
import com.screenocr.monitor.utils.PreferencesManager
import kotlin.math.max

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PreferencesManager

    private var borderView: View? = null
    private var floatingBarView: View? = null

    private var borderParams: WindowManager.LayoutParams? = null
    private var floatingBarParams: WindowManager.LayoutParams? = null

    private var selectedRegion: Rect = Rect(100, 200, 600, 500)
    private var isBorderVisible = true

    companion object {
        var instance: OverlayService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PreferencesManager(this)

        val config = prefs.loadConfig()
        config.selectedRegion?.let { selectedRegion = it }
        isBorderVisible = config.showBorder

        setupFloatingControls()
        if (isBorderVisible) {
            setupBorderOverlay()
        }
    }

    private fun getOverlayWindowType(): Int {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBorderOverlay() {
        if (borderView != null) return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_ScreenOCRMonitor)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_region_border, null)
        borderView = view

        val width = max(100, selectedRegion.width())
        val height = max(100, selectedRegion.height())

        val params = WindowManager.LayoutParams(
            width,
            height,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = selectedRegion.left
            y = selectedRegion.top
        }
        borderParams = params

        // Drag to move region
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    updateSelectedRegionFromParams()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveRegion()
                    android.widget.Toast.makeText(this@OverlayService, "Region Saved", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        // Drag handle to resize region
        val resizeHandle = view.findViewById<ImageView>(R.id.resizeHandle)
        var initW = 0
        var initH = 0
        resizeHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initW = params.width
                    initH = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = max(100, initW + (event.rawX - initialTouchX).toInt())
                    val newH = max(100, initH + (event.rawY - initialTouchY).toInt())
                    params.width = newW
                    params.height = newH
                    windowManager.updateViewLayout(view, params)
                    updateSelectedRegionFromParams()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    saveRegion()
                    android.widget.Toast.makeText(this@OverlayService, "Region Saved", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingControls() {
        if (floatingBarView != null) return

        val themedContext = ContextThemeWrapper(this, R.style.Theme_ScreenOCRMonitor)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_floating_controls, null)
        floatingBarView = view

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 100
        }
        floatingBarParams = params

        // Drag floating bar anywhere on screen
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }

        // Toggle Monitoring from overlay
        val btnToggle = view.findViewById<ImageButton>(R.id.btnFloatingToggle)
        btnToggle.setOnClickListener {
            val service = ScreenCaptureService.instance
            if (service != null && service.isMonitoring) {
                service.stopMonitoring()
            } else {
                if (service != null) {
                    service.startMonitoring()
                } else {
                    val intent = Intent(this, com.screenocr.monitor.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        }

        // Silence Alarm button
        val btnSilence = view.findViewById<ImageButton>(R.id.btnFloatingSilence)
        btnSilence.setOnClickListener {
            ScreenCaptureService.instance?.stopAlarm()
        }

        // Toggle Border Box
        val btnCrop = view.findViewById<ImageButton>(R.id.btnFloatingCrop)
        btnCrop.setOnClickListener {
            toggleBorderVisibility()
        }

        // Exit App button
        val btnExit = view.findViewById<ImageButton>(R.id.btnFloatingExit)
        btnExit.setOnClickListener {
            ScreenCaptureService.instance?.let {
                it.stopMonitoring()
                it.stopSelf()
            }
            stopSelf()
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleBorderVisibility() {
        isBorderVisible = !isBorderVisible
        setBorderVisible(isBorderVisible)
        val config = prefs.loadConfig()
        config.showBorder = isBorderVisible
        prefs.saveConfig(config)
    }

    fun setBorderVisible(visible: Boolean) {
        isBorderVisible = visible
        if (visible) {
            if (borderView == null) {
                setupBorderOverlay()
            } else if (borderView?.parent == null) {
                try {
                    windowManager.addView(borderView, borderParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            borderView?.visibility = View.VISIBLE
            android.widget.Toast.makeText(this, "Region Box Shown", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            borderView?.visibility = View.GONE
            android.widget.Toast.makeText(this, "Region Box Hidden", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun updateStatusUI(isMonitoring: Boolean, isAlarmRinging: Boolean, statusText: String, ocrPreview: String? = null) {
        floatingBarView?.let { root ->
            val btnToggle = root.findViewById<ImageButton>(R.id.btnFloatingToggle)
            val btnSilence = root.findViewById<ImageButton>(R.id.btnFloatingSilence)
            val tvStatus = root.findViewById<TextView>(R.id.tvFloatingStatus)
            val tvOcr = root.findViewById<TextView>(R.id.tvFloatingOcrPreview)

            btnToggle.setImageResource(if (isMonitoring) R.drawable.ic_stop else R.drawable.ic_play)
            btnSilence.visibility = if (isAlarmRinging) View.VISIBLE else View.GONE
            tvStatus.text = statusText
            
            ocrPreview?.let {
                tvOcr.text = if (it.length > 30) it.substring(0, 27) + "..." else it
            }
        }
    }

    private fun updateSelectedRegionFromParams() {
        borderParams?.let { p ->
            selectedRegion = Rect(p.x, p.y, p.x + p.width, p.y + p.height)
            ScreenCaptureService.instance?.updateRegion(selectedRegion)
        }
    }

    private fun saveRegion() {
        val config = prefs.loadConfig()
        config.selectedRegion = selectedRegion
        prefs.saveConfig(config)
    }

    fun getSelectedRegion(): Rect = selectedRegion

    fun resetToFullScreen() {
        selectedRegion = Rect(0, 0, 0, 0)
        borderView?.visibility = View.GONE
        isBorderVisible = false
        val config = prefs.loadConfig()
        config.selectedRegion = null
        config.showBorder = false
        prefs.saveConfig(config)
        ScreenCaptureService.instance?.updateRegion(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        borderView?.let { windowManager.removeView(it) }
        floatingBarView?.let { windowManager.removeView(it) }
    }
}
