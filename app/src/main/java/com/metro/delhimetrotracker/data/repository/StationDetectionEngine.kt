package com.metro.delhimetrotracker.data.repository

import android.location.Location
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import android.util.Log

/**
 * Intelligent station detection with multi-station lookahead and GPS recovery
 *
 * Key Features:
 * - Checks current + next 3 + destination (prevents getting stuck on missed detections)
 * - Geofencing search (±0.02° box) for GPS recovery
 * - Confidence scoring based on distance and context
 */
class StationDetectionEngine(
    private val database: AppDatabase,
    private val locationProvider: com.metro.delhimetrotracker.data.repository.LocationProvider
) {
    companion object {
        private const val TAG = "StationDetectionEngine"

        // Detection thresholds
        private const val HIGH_CONFIDENCE_DISTANCE_M = 100.0 // Within 100m = very confident
        private const val MEDIUM_CONFIDENCE_DISTANCE_M = 250.0 // Within 250m = medium confidence
        private const val MAX_DETECTION_DISTANCE_M = 400.0 // Beyond 400m = no detection

        // Geofencing parameters
        private const val GPS_RECOVERY_BOX_DEGREES = 0.02 // ±2.2 km radius
    }

    /**
     * Check for station detection with multi-station lookahead
     * Checks: last visited, next 3, and destination
     */
    suspend fun detectStationInRange(
        userLocation: Location,
        currentJourneyPath: List<MetroStation>,
        lastVisitedIndex: Int
    ): StationDetectionResult = withContext(Dispatchers.IO) {

        if (currentJourneyPath.isEmpty()) {
            return@withContext StationDetectionResult(
                detectedStation = null,
                detectionConfidence = 0f,
                stationsChecked = emptyList(),
                isDestination = false
            )
        }

        // Get the detection range
        val stationsToCheck = getDetectionRange(currentJourneyPath, lastVisitedIndex)
        Log.d(TAG, "Checking ${stationsToCheck.size} stations for detection")

        var bestMatch: MetroStation? = null
        var bestDistance = Double.MAX_VALUE
        var bestConfidence = 0f

        // Check each station in range
        for (station in stationsToCheck) {
            val distance = calculateDistance(
                userLocation.latitude,
                userLocation.longitude,
                station.latitude,
                station.longitude
            )

            Log.d(TAG, "Station: ${station.stationName}, Distance: ${distance}m")

            if (distance < bestDistance && distance < MAX_DETECTION_DISTANCE_M) {
                bestDistance = distance
                bestMatch = station
                bestConfidence = calculateConfidence(distance)
            }
        }

        val isDestination = bestMatch?.stationId == currentJourneyPath.last().stationId

        StationDetectionResult(
            detectedStation = bestMatch,
            detectionConfidence = bestConfidence,
            stationsChecked = stationsToCheck.map { it.stationName },
            isDestination = isDestination
        )
    }

    /**
     * GPS recovery logic using geofencing
     * Creates a ±0.02° bounding box and finds closest station
     */
    suspend fun findClosestStationAfterGPSGap(
        currentLocation: Location,
        lastKnownStationId: String,
        journeyPath: List<MetroStation>
    ): GPSRecoveryResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "GPS Recovery: Finding closest station to (${currentLocation.latitude}, ${currentLocation.longitude})")

        // 1. Get all stations in bounding box
        val nearbyStations = getStationsInBoundingBox(
            currentLocation.latitude,
            currentLocation.longitude
        )

        Log.d(TAG, "Found ${nearbyStations.size} stations in bounding box")

        if (nearbyStations.isEmpty()) {
            // Fallback: return last known station
            val lastKnown = database.metroStationDao().getStationById(lastKnownStationId)
            return@withContext GPSRecoveryResult(
                closestStation = lastKnown ?: journeyPath.first(),
                inferredPath = emptyList(),
                divergenceDetected = false,
                newRoutePath = null
            )
        }

        // 2. Find closest station
        val closestStation = nearbyStations.minByOrNull { station ->
            calculateDistance(
                currentLocation.latitude,
                currentLocation.longitude,
                station.latitude,
                station.longitude
            )
        }!!

        Log.d(TAG, "Closest station: ${closestStation.stationName}")

        // 3. Check if it's on the original path
        val closestIndexInPath = journeyPath.indexOfFirst { it.stationId == closestStation.stationId }
        val lastKnownIndexInPath = journeyPath.indexOfFirst { it.stationId == lastKnownStationId }

        val divergenceDetected = closestIndexInPath == -1

        // 4. Infer path between last known and current
        val inferredPath = if (closestIndexInPath > lastKnownIndexInPath && closestIndexInPath != -1) {
            // On original path - just slice
            journeyPath.subList(lastKnownIndexInPath + 1, closestIndexInPath)
        } else {
            // Diverged - would need RoutePlanner here, return empty for now
            emptyList()
        }

        // 5. Calculate new route if diverged
        val newRoutePath = if (divergenceDetected && closestStation.stationId != journeyPath.last().stationId) {
            // Would use RoutePlanner.findPath() here
            // For now, return remaining path
            journeyPath.subList(closestIndexInPath.coerceAtLeast(0), journeyPath.size)
        } else {
            null
        }

        GPSRecoveryResult(
            closestStation = closestStation,
            inferredPath = inferredPath,
            divergenceDetected = divergenceDetected,
            newRoutePath = newRoutePath
        )
    }

    /**
     * Get stations within ±0.02° bounding box (±2.2 km radius)
     * Uses efficient database query
     */
    private suspend fun getStationsInBoundingBox(
        lat: Double,
        lng: Double
    ): List<MetroStation> = withContext(Dispatchers.IO) {
        database.metroStationDao().getNearbyStations(lat, lng)
    }

    /**
     * Get detection range: last visited + next 3 + destination
     */
    private fun getDetectionRange(
        journeyPath: List<MetroStation>,
        lastVisitedIndex: Int
    ): List<MetroStation> {
        val range = mutableListOf<MetroStation>()

        // Safety check
        if (lastVisitedIndex < 0 || lastVisitedIndex >= journeyPath.size) {
            return journeyPath.take(4) // Fallback: check first 4 stations
        }

        // Last visited station
        range.add(journeyPath[lastVisitedIndex])

        // Next 3 stations
        for (i in 1..3) {
            val nextIndex = lastVisitedIndex + i
            if (nextIndex < journeyPath.size) {
                range.add(journeyPath[nextIndex])
            }
        }

        // Destination (always check, avoids missing final station)
        val destination = journeyPath.last()
        if (!range.contains(destination)) {
            range.add(destination)
        }

        return range
    }

    /**
     * Calculate confidence based on distance
     * - <100m = 0.95 confidence
     * - 100-250m = 0.75 confidence
     * - 250-400m = 0.50 confidence
     */
    private fun calculateConfidence(distanceMeters: Double): Float {
        return when {
            distanceMeters < HIGH_CONFIDENCE_DISTANCE_M -> 0.95f
            distanceMeters < MEDIUM_CONFIDENCE_DISTANCE_M -> 0.75f
            distanceMeters < MAX_DETECTION_DISTANCE_M -> 0.50f
            else -> 0.0f
        }
    }

    /**
     * Calculate distance between two lat/lng points (Haversine formula)
     * Returns distance in meters
     */
    private fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val earthRadiusKm = 6371.0

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)

        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return earthRadiusKm * c * 1000 // Convert to meters
    }
}

/**
 * Result of station detection
 */
data class StationDetectionResult(
    val detectedStation: MetroStation?,
    val detectionConfidence: Float,
    val stationsChecked: List<String>,
    val isDestination: Boolean
)

/**
 * Result of GPS recovery after gap
 */
data class GPSRecoveryResult(
    val closestStation: MetroStation,
    val inferredPath: List<MetroStation>,
    val divergenceDetected: Boolean,
    val newRoutePath: List<MetroStation>?
)