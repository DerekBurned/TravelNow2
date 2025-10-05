package repository

import models.SafetyReport

interface ISafetyRepository {

    /**
     * Submits a safety report with the given parameters.
     * @param radiusMeters The radius in meters that this report covers
     * @return Result containing the ID of the created report or failure
     */
    suspend fun submitReport(
        latitude: Double,
        longitude: Double,
        areaName: String,
        safetyLevel: String,
        comment: String,
        radiusMeters: Int = 500
    ): Result<String>

    /**
     * Fetches safety reports near the given location within the specified radius in kilometers.
     * @return Result containing a list of nearby reports
     */
    suspend fun getNearbyReports(
        latitude: Double,
        longitude: Double,
        radiusKm: Double = 50.0
    ): Result<List<SafetyReport>>

    /**
     * Fetches the most recent safety reports, limited by the provided number
     * @return Result containing a list of recent reports
     */
    suspend fun getRecentReports(limit: Int = 50): Result<List<SafetyReport>>

    /**
     * Votes on a report (upvote or downvote)
     * @param reportId ID of the report
     * @param isUpvote true for upvote, false for downvote
     */
    suspend fun voteOnReport(reportId: String, isUpvote: Boolean): Result<Unit>

    /**
     * Deletes a report if the authenticated user is the owner
     * @param reportId ID of the report
     */
    suspend fun deleteReport(reportId: String): Result<Unit>
}