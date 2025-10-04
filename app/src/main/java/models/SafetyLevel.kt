package models


import android.graphics.Color

enum class SafetyLevel(val displayName: String, val color: Int, val colorWithAlpha: Int) {
    SAFE(
        "Safe",
        Color.rgb(76, 175, 80),
        Color.argb(80, 76, 175, 80)
    ),
    BE_CAUTIOUS(
        "Be Cautious",
        Color.rgb(255, 193, 7),
        Color.argb(80, 255, 193, 7)
    ),
    UNSAFE(
        "Unsafe",
        Color.rgb(255, 152, 0),
        Color.argb(80, 255, 152, 0)
    ),
    DANGEROUS(
        "Dangerous",
        Color.rgb(244, 67, 54),
        Color.argb(80, 244, 67, 54)
    ),
    UNKNOWN(
        "Unknown",
        Color.GRAY,
        Color.argb(80, 128, 128, 128)
    );

    companion object {
        fun fromString(value: String?): SafetyLevel {
            return values().find { it.name == value } ?: UNKNOWN
        }
    }
}

