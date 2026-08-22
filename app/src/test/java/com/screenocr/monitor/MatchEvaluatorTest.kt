package com.screenocr.monitor

import com.screenocr.monitor.model.MatchMode
import com.screenocr.monitor.ocr.MatchEvaluator
import org.junit.Assert.*
import org.junit.Test

class MatchEvaluatorTest {

    @Test
    fun testExtractNumbers() {
        val res1 = MatchEvaluator.extractNumbers("$1,234.50 USD")
        assertEquals(listOf(1234.50), res1)

        val res2 = MatchEvaluator.extractNumbers("Temp: -12.5 C, Max: 40.0")
        assertEquals(listOf(-12.5, 40.0), res2)

        val res3 = MatchEvaluator.extractNumbers("No numbers here")
        assertTrue(res3.isEmpty())
    }

    @Test
    fun testMathematicalHigherThan() {
        assertTrue(MatchEvaluator.evaluateMatch("Balance: $150.00", "100", MatchMode.HIGHER_THAN))
        assertFalse(MatchEvaluator.evaluateMatch("Balance: $95.00", "100", MatchMode.HIGHER_THAN))
        assertFalse(MatchEvaluator.evaluateMatch("Balance: $100.00", "100", MatchMode.HIGHER_THAN))
    }

    @Test
    fun testMathematicalLowerThan() {
        assertTrue(MatchEvaluator.evaluateMatch("Battery: 15%", "20", MatchMode.LOWER_THAN))
        assertFalse(MatchEvaluator.evaluateMatch("Battery: 25%", "20", MatchMode.LOWER_THAN))
        assertFalse(MatchEvaluator.evaluateMatch("Battery: 20%", "20", MatchMode.LOWER_THAN))
    }

    @Test
    fun testMathematicalEqualTo() {
        assertTrue(MatchEvaluator.evaluateMatch("Count: 100", "100", MatchMode.EQUAL_TO))
        assertTrue(MatchEvaluator.evaluateMatch("Price: 100.000", "100", MatchMode.EQUAL_TO))
        assertFalse(MatchEvaluator.evaluateMatch("Count: 101", "100", MatchMode.EQUAL_TO))
    }

    @Test
    fun testMathematicalHigherOrEqual() {
        assertTrue(MatchEvaluator.evaluateMatch("Score: 100", "100", MatchMode.HIGHER_OR_EQUAL))
        assertTrue(MatchEvaluator.evaluateMatch("Score: 105", "100", MatchMode.HIGHER_OR_EQUAL))
        assertFalse(MatchEvaluator.evaluateMatch("Score: 99", "100", MatchMode.HIGHER_OR_EQUAL))
    }

    @Test
    fun testMathematicalLowerOrEqual() {
        assertTrue(MatchEvaluator.evaluateMatch("Score: 100", "100", MatchMode.LOWER_OR_EQUAL))
        assertTrue(MatchEvaluator.evaluateMatch("Score: 95", "100", MatchMode.LOWER_OR_EQUAL))
        assertFalse(MatchEvaluator.evaluateMatch("Score: 105", "100", MatchMode.LOWER_OR_EQUAL))
    }

    @Test
    fun testTextContainsAndExact() {
        assertTrue(MatchEvaluator.evaluateMatch("Order #12345 Completed", "Completed", MatchMode.CONTAINS, caseSensitive = false))
        assertFalse(MatchEvaluator.evaluateMatch("Order #12345 Completed", "completed", MatchMode.CONTAINS, caseSensitive = true))
        assertTrue(MatchEvaluator.evaluateMatch(" SUCCESS ", "SUCCESS", MatchMode.EXACT, caseSensitive = true))
    }

    @Test
    fun testRegexMatching() {
        assertTrue(MatchEvaluator.evaluateMatch("Error code: 404 Not Found", "code:\\s*\\d+", MatchMode.REGEX))
        assertFalse(MatchEvaluator.evaluateMatch("Error code: unknown", "code:\\s*\\d+", MatchMode.REGEX))
    }

    @Test
    fun testFuzzyMatching() {
        assertTrue(MatchEvaluator.evaluateMatch("Disconnected", "Disconnect", MatchMode.FUZZY_SIMILARITY, fuzzyThreshold = 80))
        assertFalse(MatchEvaluator.evaluateMatch("Connected", "Disconnect", MatchMode.FUZZY_SIMILARITY, fuzzyThreshold = 80))
    }
}
