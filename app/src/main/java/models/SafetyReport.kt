package models


import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class SafetyReport(
    @DocumentId
    val id: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val areaName: String = "",
    val safetyLevel: String = SafetyLevel.UNKNOWN.name,
    val comment: String = "",
    val userId: String = "",
    val userName: String = "Anonymous",
    @ServerTimestamp
    val timestamp: Date? = null,
    val upvotes: Int = 0,
    val downvotes: Int = 0,
    val geohash: String = ""
) {
    fun getSafetyLevelEnum(): SafetyLevel {
        return SafetyLevel.fromString(safetyLevel)
    }

    fun getFormattedDate(): String {
        timestamp?.let {
            val diff = System.currentTimeMillis() - it.time
            val days = diff / (1000 * 60 * 60 * 24)
            return when {
                days == 0L -> "Today"
                days == 1L -> "Yesterday"
                days < 7 -> "$days days ago"
                days < 30 -> "${days / 7} weeks ago"
                else -> "${days / 30} months ago"
            }
        }
        return "Unknown"
    }
}

