package com.metro.delhimetrotracker.data.model

import java.util.Date

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

    val hadSosAlert: Boolean? = false,
    val sosStationName: String? = null,
    val sosTimestamp: Long? = null
) {
    val isDelayed: Boolean get() = delayMinutes > 5
    val isSingleLine: Boolean get() = lineColors.size == 1
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

/**
 * Fun contextual messages based on travel time
 */
object TravelTimeContext {
    fun getContextMessage(totalMinutes: Int): String {
        val hours = totalMinutes / 60.0
        return when {
            hours < 1 -> "Just getting started! 🚇"
            hours < 5 -> "Equivalent to watching 2 episodes of your favorite show"
            hours < 10 -> "That's a full workday on the Metro!"
            hours < 24 -> "You've spent a full day exploring Delhi!"
            hours < 50 -> "Enough time to binge-watch Demon Slayer twice! 📺"
            hours < 100 -> "You could've watched the entire Harry Potter series!"
            else -> "You're a true Metro veteran! 🏆"
        }
    }
}