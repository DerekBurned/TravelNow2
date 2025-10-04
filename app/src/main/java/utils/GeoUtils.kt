package utils

import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS_KM = 6371.0
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /**
     * Calculate distance between two points in kilometers using Haversine formula
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    /**
     * Simple geohash encoding for location-based queries
     * @param latitude The latitude coordinate
     * @param longitude The longitude coordinate
     * @param precision The precision level (default 7)
     * @return The geohash string
     */
    fun encode(latitude: Double, longitude: Double, precision: Int = 7): String {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0

        val geohash = StringBuilder()
        var isEven = true
        var bit = 0
        var ch = 0

        while (geohash.length < precision) {
            if (isEven) {
                val mid = (lonMin + lonMax) / 2
                if (longitude > mid) {
                    ch = ch or (1 shl (4 - bit))
                    lonMin = mid
                } else {
                    lonMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude > mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }

            isEven = !isEven

            if (bit < 4) {
                bit++
            } else {
                geohash.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }

        return geohash.toString()
    }

    /**
     * Get geohash bounds for querying nearby locations
     * @param latitude Center latitude
     * @param longitude Center longitude
     * @param radiusKm Search radius in kilometers
     * @return Pair of lower and upper geohash bounds
     */
    fun getGeohashBounds(latitude: Double, longitude: Double, radiusKm: Double): Pair<String, String> {
        // Approximate: use smaller precision for larger radius
        val precision = when {
            radiusKm > 100 -> 4
            radiusKm > 20 -> 5
            radiusKm > 5 -> 6
            else -> 7
        }

        val centerHash = encode(latitude, longitude, precision)
        val lower = centerHash.substring(0, precision)

        // Calculate upper bound
        val lastCharIndex = BASE32.indexOf(lower.last())
        val upper = if (lastCharIndex < BASE32.length - 1) {
            lower.substring(0, precision - 1) + BASE32[lastCharIndex + 1]
        } else {
            lower.substring(0, precision - 1) + "z"
        }

        return Pair(lower, upper)
    }
}

// Location: app/src/main/java/com/example/travelnow/utils/GeoUtils.kt