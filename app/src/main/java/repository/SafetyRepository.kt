package repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import models.SafetyReport
import utils.GeoUtils

class SafetyRepository {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val reportsCollection = db.collection("safety_reports")

    private suspend fun ensureAuthenticated(): Boolean {
        return try {
            if (auth.currentUser == null) {
                Log.d("SafetyRepository", "No user found, signing in anonymously...")
                auth.signInAnonymously().await()
                Log.d("SafetyRepository", "Signed in anonymously with UID: ${auth.currentUser?.uid}")
            } else {
                Log.d("SafetyRepository", "Already authenticated with UID: ${auth.currentUser?.uid}")
            }
            auth.currentUser != null
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Authentication failed", e)
            false
        }
    }

    suspend fun submitReport(
        latitude: Double,
        longitude: Double,
        areaName: String,
        safetyLevel: String,
        comment: String,
        radiusMeters: Int = 500
    ): Result<String> {
        return try {
            Log.d("SafetyRepository", "Attempting to submit report...")

            if (!ensureAuthenticated()) {
                Log.e("SafetyRepository", "Authentication failed")
                return Result.failure(Exception("Authentication failed. Please try again."))
            }

            val userId = auth.currentUser?.uid
            if (userId == null) {
                Log.e("SafetyRepository", "User ID is null after authentication")
                return Result.failure(Exception("Not authenticated"))
            }

            Log.d("SafetyRepository", "Authenticated user ID: $userId")

            val geohash = GeoUtils.encode(latitude, longitude, 7)
            Log.d("SafetyRepository", "Generated geohash: $geohash")

            val report = hashMapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "areaName" to areaName,
                "safetyLevel" to safetyLevel,
                "comment" to comment,
                "userId" to userId,
                "userName" to "Anonymous User",
                "upvotes" to 0,
                "downvotes" to 0,
                "radiusMeters" to radiusMeters,
                "geohash" to geohash,
                "timestamp" to FieldValue.serverTimestamp()
            )

            Log.d("SafetyRepository", "Submitting report to Firestore...")
            val documentRef = reportsCollection.add(report).await()
            Log.d("SafetyRepository", "Report submitted successfully with ID: ${documentRef.id}")
            Result.success(documentRef.id)
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Error submitting report: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getNearbyReports(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 50.0
    ): Result<List<SafetyReport>> {
        return try {
            Log.d("SafetyRepository", "Fetching nearby reports for lat=$latitude, lon=$longitude, radius=$radiusKm km")

            val bounds = GeoUtils.getGeohashBounds(latitude, longitude, radiusKm)
            Log.d("SafetyRepository", "Geohash bounds: ${bounds.first} to ${bounds.second}")

            val reports = mutableListOf<SafetyReport>()

            val snapshot = reportsCollection
                .whereGreaterThanOrEqualTo("geohash", bounds.first)
                .whereLessThanOrEqualTo("geohash", bounds.second)
                .orderBy("geohash")
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(100)
                .get()
                .await()

            Log.d("SafetyRepository", "Firestore returned ${snapshot.documents.size} documents")

            for (document in snapshot.documents) {
                val report = document.toObject(SafetyReport::class.java)
                report?.let {
                    val distance = GeoUtils.calculateDistance(
                        latitude, longitude,
                        it.latitude, it.longitude
                    )
                    if (distance <= radiusKm) {
                        reports.add(it)
                    }
                }
            }

            Log.d("SafetyRepository", "Found ${reports.size} nearby reports within radius")
            Result.success(reports)
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Error fetching reports: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getRecentReports(limit: Int = 50): Result<List<SafetyReport>> {
        return try {
            Log.d("SafetyRepository", "Fetching recent reports (limit: $limit)")

            val snapshot = reportsCollection
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(limit.toLong())
                .get()
                .await()

            val reports = snapshot.toObjects(SafetyReport::class.java)
            Log.d("SafetyRepository", "Found ${reports.size} recent reports")
            Result.success(reports)
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Error fetching recent reports: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun voteOnReport(reportId: String, isUpvote: Boolean): Result<Unit> {
        return try {
            Log.d("SafetyRepository", "Voting on report $reportId (upvote: $isUpvote)")

            val reportRef = reportsCollection.document(reportId)
            val field = if (isUpvote) "upvotes" else "downvotes"

            db.runTransaction { transaction ->
                val snapshot = transaction.get(reportRef)
                if (!snapshot.exists()) {
                    throw Exception("Report not found")
                }
                val currentValue = snapshot.getLong(field) ?: 0
                transaction.update(reportRef, field, currentValue + 1)
            }.await()

            Log.d("SafetyRepository", "Vote recorded successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Error voting on report: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteReport(reportId: String): Result<Unit> {
        return try {
            Log.d("SafetyRepository", "Attempting to delete report $reportId")

            if (!ensureAuthenticated()) {
                return Result.failure(Exception("Not authenticated"))
            }

            val userId = auth.currentUser?.uid
                ?: return Result.failure(Exception("Not authenticated"))

            val report = reportsCollection.document(reportId).get().await()

            if (!report.exists()) {
                return Result.failure(Exception("Report not found"))
            }

            val reportUserId = report.getString("userId")

            if (reportUserId == userId) {
                reportsCollection.document(reportId).delete().await()
                Log.d("SafetyRepository", "Report deleted successfully")
                Result.success(Unit)
            } else {
                Log.e("SafetyRepository", "Unauthorized delete attempt")
                Result.failure(Exception("You can only delete your own reports"))
            }
        } catch (e: Exception) {
            Log.e("SafetyRepository", "Error deleting report: ${e.message}", e)
            Result.failure(e)
        }
    }
}