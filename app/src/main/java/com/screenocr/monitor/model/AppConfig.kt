package com.screenocr.monitor.model

import android.graphics.Rect

data class AppConfig(
    var preset: String = "",
    var matchMode: MatchMode = MatchMode.CONTAINS,
    var caseSensitive: Boolean = false,
    var fuzzyThreshold: Int = 80,
    var pollInterval: Double = 1.5,
    var cooldownSec: Int = 10,
    var soundAlert: Boolean = true,
    var continuousBeep: Boolean = false,
    var beepInterval: Double = 0.8,
    var toastAlert: Boolean = true,
    var alertOnce: Boolean = true,
    var showBorder: Boolean = true,
    var selectedRegion: Rect? = null
)
