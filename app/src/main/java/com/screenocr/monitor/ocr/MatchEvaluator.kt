package com.screenocr.monitor.ocr

import com.screenocr.monitor.model.MatchMode
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max

object MatchEvaluator {
    private val NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?")

    fun extractNumbers(text: String): List<Double> {
        val matcher = NUMBER_PATTERN.matcher(text)
        val result = mutableListOf<Double>()
        while (matcher.find()) {
            val raw = matcher.group().replace(",", "")
            raw.toDoubleOrNull()?.let { result.add(it) }
        }
        return result
    }

    fun calculateFuzzySimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 100.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        val distance = dp[len1][len2]
        val maxLen = max(len1, len2)
        return ((maxLen - distance).toDouble() / maxLen.toDouble()) * 100.0
    }

    fun evaluateMatch(
        text: String,
        preset: String,
        mode: MatchMode,
        caseSensitive: Boolean = false,
        fuzzyThreshold: Int = 80
    ): Boolean {
        if (preset.isBlank()) return false

        when (mode) {
            MatchMode.CONTAINS -> {
                val t = if (caseSensitive) text else text.lowercase()
                val p = if (caseSensitive) preset else preset.lowercase()
                return t.contains(p)
            }
            MatchMode.EXACT -> {
                val t = if (caseSensitive) text.trim() else text.trim().lowercase()
                val p = if (caseSensitive) preset.trim() else preset.trim().lowercase()
                return t == p
            }
            MatchMode.REGEX -> {
                return try {
                    val flags = if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE
                    val regex = Pattern.compile(preset, flags)
                    regex.matcher(text).find()
                } catch (e: Exception) {
                    false
                }
            }
            MatchMode.FUZZY_SIMILARITY -> {
                val t = if (caseSensitive) text else text.lowercase()
                val p = if (caseSensitive) preset else preset.lowercase()
                return calculateFuzzySimilarity(t, p) >= fuzzyThreshold
            }
            MatchMode.HIGHER_THAN,
            MatchMode.LOWER_THAN,
            MatchMode.EQUAL_TO,
            MatchMode.HIGHER_OR_EQUAL,
            MatchMode.LOWER_OR_EQUAL -> {
                val presetNums = extractNumbers(preset)
                if (presetNums.isEmpty()) return false
                val targetVal = presetNums[0]

                val ocrNums = extractNumbers(text)
                if (ocrNums.isEmpty()) return false

                for (num in ocrNums) {
                    when (mode) {
                        MatchMode.HIGHER_THAN -> if (num > targetVal) return true
                        MatchMode.LOWER_THAN -> if (num < targetVal) return true
                        MatchMode.EQUAL_TO -> if (abs(num - targetVal) < 1e-6) return true
                        MatchMode.HIGHER_OR_EQUAL -> if (num >= targetVal) return true
                        MatchMode.LOWER_OR_EQUAL -> if (num <= targetVal) return true
                        else -> {}
                    }
                }
                return false
            }
        }
    }
}
