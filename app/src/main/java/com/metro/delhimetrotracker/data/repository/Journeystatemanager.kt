package com.metro.delhimetrotracker.data.repository

import android.location.Location
import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Central coordinator for journey state management
 * Handles GPS gaps, route divergence, and state transitions
 */
class JourneyStateManager(
    private val database: AppDatabase,
    private val detectionEngine: StationDetectionEngine,
    private val recoveryManager: RouteRecoveryManager,
    private val locationProvider: LocationProvider
) {
    companion object {
        private const val TAG = "JourneyStateManager"
        private const val GPS_TIMEOUT_MS = 30_000L // 30 seconds without GPS = considered offline
    }

    // Journey state
    private val _journeyState = MutableStateFlow<JourneyState>(JourneyState.Idle)
    val journeyState: StateFlow<JourneyState> = _journeyState.asStateFlow()

    // GPS state tracking
    private var lastGpsUpdate: Long = 0L
    private var lastKnownLocation: Location? = null
    private var lastDetectedStationIndex: Int = -1

    /**
     * Process a location update
     * Main entry point for GPS data
     */
    suspend fun processLocationUpdate(
        location: Location,
        currentTrip: Trip,
        journeyPath: List<MetroStation>
    ): LocationProcessingResult = withContext(Dispatchers.IO) {
        val wasGpsOffline = isGpsOffline()
        lastGpsUpdate = System.currentTimeMillis()
        lastKnownLocation = location

        if (wasGpsOffline) {
            Log.d(TAG, "GPS reconnected after gap - triggering recovery")
            return@withContext handleGpsRecovery(location, currentTrip, journeyPath)
        }

        // Normal detection flow
        return@withContext handleNormalDetection(location, currentTrip, journeyPath)
    }

    /**
     * Handle normal station detection (GPS online)
     */
    private suspend fun handleNormalDetection(
        location: Location,
        currentTrip: Trip,
        journeyPath: List<MetroStation>
    ): LocationProcessingResult {

        val detectionResult = detectionEngine.detectStationInRange(
            userLocation = location,
            currentJourneyPath = journeyPath,
            lastVisitedIndex = lastDetectedStationIndex
        )

        if (detectionResult.detectedStation == null) {
            return LocationProcessingResult.NoDetection(
                stationsChecked = detectionResult.stationsChecked
            )
        }

        // Check if this is a new station (not the last detected one)
        val isNewStation = if (lastDetectedStationIndex >= 0 && lastDetectedStationIndex < journeyPath.size) {
            detectionResult.detectedStation.stationId != journeyPath[lastDetectedStationIndex].stationId
        } else {
            true
        }

        if (!isNewStation) {
            return LocationProcessingResult.SameStation(
                station = detectionResult.detectedStation
            )
        }

        // New station detected!
        Log.d(TAG, "New station detected: ${detectionResult.detectedStation.stationName}")

        // Save checkpoint
        val checkpoint = StationCheckpoint(
            tripId = currentTrip.id,
            stationId = detectionResult.detectedStation.stationId,
            stationName = detectionResult.detectedStation.stationName,
            stationOrder = lastDetectedStationIndex + 1,
            arrivalTime = Date(),
            detectionMethod = DetectionMethod.GPS,
            confidence = detectionResult.detectionConfidence,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            isInferred = false
        )
        database.stationCheckpointDao().insertCheckpoint(checkpoint)

        // Update index
        val newIndex = journeyPath.indexOfFirst { it.stationId == detectionResult.detectedStation.stationId }
        if (newIndex != -1) {
            lastDetectedStationIndex = newIndex
        }

        // Update trip's visited stations list
        val updatedVisitedStations = currentTrip.visitedStations.toMutableList()
        updatedVisitedStations.add(detectionResult.detectedStation.stationId)
        database.tripDao().updateVisitedStations(
            tripId = currentTrip.id,
            visitedListJson = com.google.gson.Gson().toJson(updatedVisitedStations)
        )

        return LocationProcessingResult.NewStationDetected(
            station = detectionResult.detectedStation,
            confidence = detectionResult.detectionConfidence,
            isDestination = detectionResult.isDestination,
            checkpoint = checkpoint
        )
    }

    /**
     * Handle GPS recovery after gap
     */
    private suspend fun handleGpsRecovery(
        location: Location,
        currentTrip: Trip,
        journeyPath: List<MetroStation>
    ): LocationProcessingResult {
        Log.d(TAG, "Processing GPS recovery")

        // Get last known station
        val lastCheckpoint = database.stationCheckpointDao().getLastCheckpoint(currentTrip.id)
        val lastKnownStationId = lastCheckpoint?.stationId ?: currentTrip.sourceStationId

        // Use recovery engine to find closest station
        val recoveryResult = detectionEngine.findClosestStationAfterGPSGap(
            currentLocation = location,
            lastKnownStationId = lastKnownStationId,
            journeyPath = journeyPath
        )

        Log.d(TAG, "Recovered to: ${recoveryResult.closestStation.stationName}, Divergence: ${recoveryResult.divergenceDetected}")

        // Handle divergence if detected
        if (recoveryResult.divergenceDetected) {
            val lastKnownStation = database.metroStationDao().getStationById(lastKnownStationId)
                ?: journeyPath.first()

            val divergenceResolution = recoveryManager.handleRouteDivergence(
                tripId = currentTrip.id,
                lastKnownStation = lastKnownStation,
                currentStation = recoveryResult.closestStation,
                originalDestinationId = currentTrip.destinationStationId
            )

            // Update journey path with new route
            val newPath = listOf(recoveryResult.closestStation) + divergenceResolution.newPath

            // Update last detected index
            lastDetectedStationIndex = 0 // Reset to beginning of new path

            return LocationProcessingResult.GpsRecoveredWithDivergence(
                recoveredStation = recoveryResult.closestStation,
                inferredStations = divergenceResolution.inferredStations,
                newPath = newPath,
                divergenceInfo = divergenceResolution.divergenceInfo
            )
        }

        // No divergence - just fill gap
        if (recoveryResult.inferredPath.isNotEmpty()) {
            // Save inferred checkpoints
            val lastOrder = lastCheckpoint?.stationOrder ?: 0
            recoveryResult.inferredPath.forEachIndexed { index, station ->
                val inferredCheckpoint = StationCheckpoint(
                    tripId = currentTrip.id,
                    stationId = station.stationId,
                    stationName = station.stationName,
                    stationOrder = lastOrder + index + 1,
                    arrivalTime = Date(),
                    detectionMethod = DetectionMethod.HYBRID,
                    confidence = 0.7f,
                    isInferred = true,
                    inferredReason = "GPS_GAP"
                )
                database.stationCheckpointDao().insertCheckpoint(inferredCheckpoint)
            }
        }

        // Save current station checkpoint
        val currentCheckpoint = StationCheckpoint(
            tripId = currentTrip.id,
            stationId = recoveryResult.closestStation.stationId,
            stationName = recoveryResult.closestStation.stationName,
            stationOrder = (lastCheckpoint?.stationOrder ?: 0) + recoveryResult.inferredPath.size + 1,
            arrivalTime = Date(),
            detectionMethod = DetectionMethod.GPS,
            confidence = 0.85f,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            isInferred = false
        )
        database.stationCheckpointDao().insertCheckpoint(currentCheckpoint)

        // Update index
        val newIndex = journeyPath.indexOfFirst { it.stationId == recoveryResult.closestStation.stationId }
        if (newIndex != -1) {
            lastDetectedStationIndex = newIndex
        }

        return LocationProcessingResult.GpsRecoveredOnPath(
            recoveredStation = recoveryResult.closestStation,
            inferredStations = recoveryResult.inferredPath.map { it.stationId },
            gapDuration = calculateGapDuration()
        )
    }

    /**
     * Check if GPS is considered offline
     */
    private fun isGpsOffline(): Boolean {
        return (System.currentTimeMillis() - lastGpsUpdate) > GPS_TIMEOUT_MS
    }

    /**
     * Calculate gap duration in seconds
     */
    private fun calculateGapDuration(): Long {
        return (System.currentTimeMillis() - lastGpsUpdate) / 1000
    }

    /**
     * Reset state for new journey
     */
    fun resetState() {
        lastDetectedStationIndex = -1
        lastGpsUpdate = System.currentTimeMillis()
        lastKnownLocation = null
        _journeyState.value = JourneyState.Idle
    }

    /**
     * Update journey state
     */
    fun updateState(newState: JourneyState) {
        _journeyState.value = newState
    }
}

/**
 * Journey state enum
 */
sealed class JourneyState {
    object Idle : JourneyState()
    data class Active(val tripId: Long, val currentStationIndex: Int) : JourneyState()
    data class GpsGap(val tripId: Long, val lastKnownStationIndex: Int, val gapStartTime: Long) : JourneyState()
    data class Diverged(val tripId: Long, val divergencePoint: String) : JourneyState()
    object Completed : JourneyState()
}

/**
 * Result of location processing
 */
sealed class LocationProcessingResult {
    data class NoDetection(val stationsChecked: List<String>) : LocationProcessingResult()

    data class SameStation(val station: MetroStation) : LocationProcessingResult()

    data class NewStationDetected(
        val station: MetroStation,
        val confidence: Float,
        val isDestination: Boolean,
        val checkpoint: StationCheckpoint
    ) : LocationProcessingResult()

    data class GpsRecoveredOnPath(
        val recoveredStation: MetroStation,
        val inferredStations: List<String>,
        val gapDuration: Long
    ) : LocationProcessingResult()

    data class GpsRecoveredWithDivergence(
        val recoveredStation: MetroStation,
        val inferredStations: List<String>,
        val newPath: List<MetroStation>,
        val divergenceInfo: RouteDivergence
    ) : LocationProcessingResult()
}