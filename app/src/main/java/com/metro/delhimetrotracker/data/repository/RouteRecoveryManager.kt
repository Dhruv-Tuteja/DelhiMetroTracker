package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint
import com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod
import com.metro.delhimetrotracker.data.local.database.entities.RouteDivergence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import android.util.Log

/**
 * Handles GPS gap recovery and route divergence scenarios
 * Core responsibilities:
 * 1. Infer stations traversed during GPS gaps
 * 2. Detect when user deviates from planned route
 * 3. Recalculate path to destination after divergence
 * 4. Save inferred checkpoints to database
 */
class RouteRecoveryManager(
    private val database: AppDatabase,
    private val routePlanner: RoutePlannerInterface
) {
    companion object {
        private const val TAG = "RouteRecoveryManager"
    }

    /**
     * Reconstruct path between two stations after GPS gap
     * Determines if user stayed on original path or diverged
     */
    suspend fun inferStationsBetween(
        lastKnownStationId: String,
        currentDetectedStationId: String,
        originalPath: List<MetroStation>
    ): InferredPathResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Inferring path from $lastKnownStationId to $currentDetectedStationId")

        // Check if both stations are on original path
        val lastKnownIndex = routePlanner.getStationIndexInPath(lastKnownStationId, originalPath)
        val currentIndex = routePlanner.getStationIndexInPath(currentDetectedStationId, originalPath)

        if (lastKnownIndex != null && currentIndex != null && currentIndex > lastKnownIndex) {
            // User stayed on original path - just fill the gap
            val inferredStations = originalPath.subList(lastKnownIndex + 1, currentIndex)
            Log.d(TAG, "User stayed on path. Inferred ${inferredStations.size} stations")

            return@withContext InferredPathResult(
                inferredStations = inferredStations,
                isOnOriginalPath = true,
                divergencePoint = null
            )
        }

        // User diverged - find new path between the two stations
        Log.d(TAG, "Route divergence detected. Finding alternate path...")
        val inferredStations = routePlanner.findPath(lastKnownStationId, currentDetectedStationId)

        if (inferredStations.isEmpty()) {
            Log.w(TAG, "Could not find path between stations")
            return@withContext InferredPathResult(
                inferredStations = emptyList(),
                isOnOriginalPath = false,
                divergencePoint = database.metroStationDao().getStationById(lastKnownStationId)
            )
        }

        // Remove first and last station (already known)
        val pathWithoutEndpoints = if (inferredStations.size > 2) {
            inferredStations.subList(1, inferredStations.size - 1)
        } else {
            emptyList()
        }

        InferredPathResult(
            inferredStations = pathWithoutEndpoints,
            isOnOriginalPath = false,
            divergencePoint = database.metroStationDao().getStationById(lastKnownStationId)
        )
    }

    /**
     * Handle route divergence scenario
     * When user is detected at a station not on the original path
     */
    suspend fun handleRouteDivergence(
        tripId: Long,
        lastKnownStation: MetroStation,
        currentStation: MetroStation,
        originalDestinationId: String
    ): DivergenceResolution = withContext(Dispatchers.IO) {
        Log.d(TAG, "Handling divergence: ${lastKnownStation.stationName} -> ${currentStation.stationName}")

        // 1. Get existing checkpoints to preserve visited stations
        val existingCheckpoints = database.stationCheckpointDao().getCheckpointsForTrip(tripId)
        val keptStations = existingCheckpoints.map { it.stationId }

        // 2. Infer gap between last known and current
        val trip = database.tripDao().getTripById(tripId)
        val originalPath = if (trip != null) {
            // Reconstruct original path from trip data
            getOriginalPathFromTrip(trip)
        } else {
            emptyList()
        }

        val inferResult = inferStationsBetween(
            lastKnownStation.stationId,
            currentStation.stationId,
            originalPath
        )

        // 3. Calculate new path from current location to destination
        val newPathToDestination = routePlanner.findPath(
            currentStation.stationId,
            originalDestinationId
        )

        // 4. Save inferred checkpoints
        if (inferResult.inferredStations.isNotEmpty()) {
            val lastOrder = existingCheckpoints.maxOfOrNull { it.stationOrder } ?: 0
            saveInferredCheckpoints(
                tripId = tripId,
                inferredStations = inferResult.inferredStations,
                reason = "GPS_GAP",
                startOrder = lastOrder + 1
            )
        }

        // 5. Create divergence record
        val divergenceInfo = RouteDivergence(
            timestamp = System.currentTimeMillis(),
            lastKnownStationId = lastKnownStation.stationId,
            lastKnownStationName = lastKnownStation.stationName,
            detectedStationId = currentStation.stationId,
            detectedStationName = currentStation.stationName,
            gapStations = inferResult.inferredStations.map { it.stationId },
            reason = if (inferResult.isOnOriginalPath) "GPS_GAP" else "LINE_CHANGE"
        )

        // 6. Update trip with divergence info
        updateTripWithDivergence(tripId, divergenceInfo)

        DivergenceResolution(
            newPath = newPathToDestination,
            keptStations = keptStations,
            inferredStations = inferResult.inferredStations.map { it.stationId },
            upcomingStations = newPathToDestination.map { it.stationId },
            divergenceInfo = divergenceInfo
        )
    }

    /**
     * Save inferred checkpoints to database
     * These are marked with isInferred = true
     */
    private suspend fun saveInferredCheckpoints(
        tripId: Long,
        inferredStations: List<MetroStation>,
        reason: String,
        startOrder: Int = 0
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Saving ${inferredStations.size} inferred checkpoints")

        inferredStations.forEachIndexed { index, station ->
            val checkpoint = StationCheckpoint(
                tripId = tripId,
                stationId = station.stationId,
                stationName = station.stationName,
                stationOrder = startOrder + index,
                arrivalTime = Date(),
                detectionMethod = DetectionMethod.HYBRID,
                confidence = 0.7f,
                isInferred = true,
                inferredReason = reason
            )
            database.stationCheckpointDao().insertCheckpoint(checkpoint)
        }
    }

    /**
     * Update trip with divergence information
     */
    private suspend fun updateTripWithDivergence(
        tripId: Long,
        divergence: RouteDivergence
    ) = withContext(Dispatchers.IO) {
        val trip = database.tripDao().getTripById(tripId) ?: return@withContext

        val updatedDivergences = trip.routeDivergences?.toMutableList() ?: mutableListOf()
        updatedDivergences.add(divergence)

        val updatedTrip = trip.copy(
            routeDivergences = updatedDivergences,
            syncState = "PENDING",
            lastModified = System.currentTimeMillis()
        )

        database.tripDao().updateTrip(updatedTrip)
        Log.d(TAG, "Trip $tripId updated with divergence record")
    }

    /**
     * Helper to reconstruct original path from trip data
     */
    private suspend fun getOriginalPathFromTrip(trip: com.metro.delhimetrotracker.data.local.database.entities.Trip): List<MetroStation> {
        return withContext(Dispatchers.IO) {
            val sourceStation = database.metroStationDao().getStationById(trip.sourceStationId)
            val destStation = database.metroStationDao().getStationById(trip.destinationStationId)

            if (sourceStation == null || destStation == null) {
                return@withContext emptyList()
            }

            routePlanner.findPath(trip.sourceStationId, trip.destinationStationId)
        }
    }
}

/**
 * Result of inferring path during GPS gap
 */
data class InferredPathResult(
    val inferredStations: List<MetroStation>,
    val isOnOriginalPath: Boolean,
    val divergencePoint: MetroStation?
)

/**
 * Complete resolution of a route divergence scenario
 */
data class DivergenceResolution(
    val newPath: List<MetroStation>,
    val keptStations: List<String>, // Already visited, keep as-is
    val inferredStations: List<String>, // Gap fill
    val upcomingStations: List<String>, // New path to destination
    val divergenceInfo: RouteDivergence
)