package models

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.text.SimpleDateFormat
import java.util.*

data class SafetyReport(
    @DocumentId
    val id: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val areaName: String = "",
    val safetyLevel: String = "",
    val comment: String = "",
    val userId: String = "",
    val userName: String = "Anonymous User",
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val geohash: String = "",
    @ServerTimestamp
    val timestamp: Date? = null
) {
    fun getSafetyLevelEnum(): SafetyLevel {
        return try {
            SafetyLevel.valueOf(safetyLevel)
        } catch (e: IllegalArgumentException) {
            SafetyLevel.UNKNOWN
        }
    }

    fun getFormattedDate(): String {
        return if (timestamp != null) {
            val now = Date()
            val diff = now.time - timestamp.time

            when {
                diff < 60_000 -> "Just now"
                diff < 3_600_000 -> "${diff / 60_000}m ago"
                diff < 86_400_000 -> "${diff / 3_600_000}h ago"
                diff < 604_800_000 -> "${diff / 86_400_000}d ago"
                else -> {
                    val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    formatter.format(timestamp)
                }
            }
        } else {
            "Unknown"
        }
    }
}