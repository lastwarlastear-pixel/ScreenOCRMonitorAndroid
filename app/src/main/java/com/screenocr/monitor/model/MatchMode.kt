package com.screenocr.monitor.model

enum class MatchMode(val displayName: String) {
    CONTAINS("Contains"),
    EXACT("Exact"),
    HIGHER_THAN("Higher than (>)"),
    LOWER_THAN("Lower than (<)"),
    EQUAL_TO("Equal to (=)"),
    HIGHER_OR_EQUAL("Higher or Equal (>=)"),
    LOWER_OR_EQUAL("Lower or Equal (<=)"),
    REGEX("Regex"),
    FUZZY_SIMILARITY("Fuzzy Similarity");

    companion object {
        fun fromDisplayName(name: String): MatchMode {
            return entries.find { it.displayName.equals(name, ignoreCase = true) } ?: CONTAINS
        }
    }
}
