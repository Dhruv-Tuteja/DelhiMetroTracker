package com.metro.delhimetrotracker.data.model

import java.util.Date
import com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint

/**
 * Data transfer object for dashboard statistics
 */
data class DashboardStats(
    val totalTrips: Int = 0,
    val totalMinutes: Int = 0,
    val uniqueStationsVisited: Int = 0,
    val carbonSavedKg: Double = 0.0,
    val totalStationsInNetwork: Int = 250
)

/**
 * Enriched trip data for history display
 */
data class TripCardData(
    val tripId: Long,
    val sourceStationName: String,
    val destinationStationName: String,
    val startTime: Date,
    val endTime: Date?,
    val durationMinutes: Int,
    val stationCount: Int,
    val interchangeStations: List<String>,
    val lineColors: List<String>,
    val expectedDurationMinutes: Int,
    val delayMinutes: Int,
    val checkpoints: List<StationCheckpoint> = emptyList(), // Add this
    val hadSosAlert: Boolean? = false,
    val sosStationName: String? = null,
    val sosTimestamp: Long? = null
) {
    val isDelayed: Boolean get() = delayMinutes > 5
}

/**
 * Frequent route pattern for predictive insights
 */
data class FrequentRoute(
    val sourceStationId: String,
    val sourceStationName: String,
    val destinationStationId: String,
    val destinationStationName: String,
    val tripCount: Int,
    val avgDuration: Int,
    val commonDayOfWeek: Int,
    val commonHourOfDay: Int
)

/**
 * UI state for dashboard screen
 */
sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(
        val stats: DashboardStats,
        val frequentRoutes: List<FrequentRoute>,
        val recentTrips: List<TripCardData>
    ) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}