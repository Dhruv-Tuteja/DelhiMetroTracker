package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import com.metro.delhimetrotracker.data.local.database.entities.TripStatus
import com.metro.delhimetrotracker.data.model.DashboardStats
import com.metro.delhimetrotracker.data.model.FrequentRoute
import com.metro.delhimetrotracker.data.model.TripCardData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Calendar

/**
 * Repository for dashboard analytics
 */
class DashboardRepository(private val database: AppDatabase) {

    private val tripDao = database.tripDao()
    private val stationDao = database.metroStationDao()

    suspend fun deleteTrip(tripId: Long) {
        // We use the "Soft Delete" method we added to the DAO
        database.tripDao().markTripAsDeleted(tripId)
    }
    suspend fun restoreTrip(tripId: Long) {
        database.tripDao().restoreTrip(tripId)
    }
    fun getDashboardStats(): Flow<DashboardStats> {
        return combine(
            tripDao.getCompletedTripsCount(),
            tripDao.getTotalTravelTime(),
            getUniqueStationsFlow(),
            tripDao.getTripsByStatus(TripStatus.COMPLETED) // Get all completed trips
        ) { tripCount, totalMinutes, uniqueStations, completedTrips ->

            // Calculate total stations from all trips
            val totalStations = completedTrips.sumOf { it.visitedStations.size }

            // Calculate total distance traveled
            val kmPerStation = 1.2
            val totalKm = totalStations * kmPerStation

            val carbonSaved = calculateCarbonSaved(totalKm)

            DashboardStats(
                totalTrips = tripCount,
                totalMinutes = totalMinutes ?: 0,
                uniqueStationsVisited = uniqueStations,
                carbonSavedKg = carbonSaved,
                totalStationsInNetwork = 250
            )
        }
    }


    /**
     * Get unique stations visited
     */
    private fun getUniqueStationsFlow(): Flow<Int> {
        return tripDao.getTripsByStatus(TripStatus.COMPLETED).map { completedTrips ->
            completedTrips.flatMap { it.visitedStations }.distinct().size
        }
    }

    /**
     * Calculate CO2 saved vs car travel
     */
    private fun calculateCarbonSaved(totalKm: Double): Double {
        // Real-world average distance between Delhi Metro stations is ~1.2km
        val co2SavingsPerKm = 32.38 / 1000.0 // Specific to transit vs car offset
        return totalKm * co2SavingsPerKm
    }
    suspend fun deleteTripById(id: Long) {
        database.tripDao().deleteById(id)
    }

    /**
     * Get frequent routes with time patterns
     */
    suspend fun getFrequentRoutes(): List<FrequentRoute> {
        val completedTrips = tripDao.getTripsByStatus(TripStatus.COMPLETED).first()

        val routeGroups = completedTrips.groupBy {
            "${it.sourceStationId}_${it.destinationStationId}"
        }

        return routeGroups.map { (_, trips) ->
            val first = trips.first()
            val avgDuration = trips.mapNotNull { it.durationMinutes }.average().toInt()

            val dayOfWeek = trips.groupingBy {
                Calendar.getInstance().apply { time = it.startTime }.get(Calendar.DAY_OF_WEEK)
            }.eachCount().maxByOrNull { it.value }?.key ?: 0

            val hourOfDay = trips.groupingBy {
                Calendar.getInstance().apply { time = it.startTime }.get(Calendar.HOUR_OF_DAY)
            }.eachCount().maxByOrNull { it.value }?.key ?: 0

            FrequentRoute(
                sourceStationId = first.sourceStationId,
                sourceStationName = first.sourceStationName,
                destinationStationId = first.destinationStationId,
                destinationStationName = first.destinationStationName,
                tripCount = trips.size,
                avgDuration = avgDuration,
                commonDayOfWeek = dayOfWeek,
                commonHourOfDay = hourOfDay
            )
        }.sortedByDescending { it.tripCount }.take(5)
    }

    /**
     * Convert Trip entities to enriched TripCardData
     */
    suspend fun getEnrichedTripHistory(): Flow<List<TripCardData>> {
        return combine(
            tripDao.getRecentTrips(50),
            stationDao.getAllStations()
        ) { trips, allStations ->
            trips.mapNotNull { trip ->
                enrichTripData(trip, allStations)
            }
        }
    }

    /**
     * Enrich a single trip with station metadata
     */
    private fun enrichTripData(trip: Trip, allStations: List<MetroStation>): TripCardData? {
        if (trip.endTime == null || trip.durationMinutes == null) return null

        val stationMap = allStations.associateBy { it.stationId }
        val visitedStations = trip.visitedStations.mapNotNull { stationMap[it] }

        val lineColors = visitedStations.map { it.lineColor }.distinct()

        val interchangeStations = visitedStations
            .filter { it.isInterchange }
            .map { it.stationName }
            .distinct()

        val expectedDuration = visitedStations.size * 2
        val delayMinutes = trip.durationMinutes - expectedDuration

        return TripCardData(
            tripId = trip.id,
            sourceStationName = trip.sourceStationName,
            destinationStationName = trip.destinationStationName,
            startTime = trip.startTime,
            endTime = trip.endTime,
            durationMinutes = trip.durationMinutes,
            stationCount = visitedStations.size,
            interchangeStations = interchangeStations,
            lineColors = lineColors,
            expectedDurationMinutes = expectedDuration,
            delayMinutes = delayMinutes,
            hadSosAlert = trip.hadSosAlert,
            sosStationName = trip.sosStationName,
            sosTimestamp = trip.sosTimestamp
        )
    }
}