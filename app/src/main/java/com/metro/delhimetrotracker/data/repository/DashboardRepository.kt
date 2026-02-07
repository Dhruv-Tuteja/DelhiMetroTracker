package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import android.util.Log
import com.metro.delhimetrotracker.data.model.DashboardStats
import com.metro.delhimetrotracker.data.model.FrequentRoute
import com.metro.delhimetrotracker.data.model.TripCardData
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import java.util.Calendar
import com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint
import kotlinx.coroutines.flow.flatMapLatest

class DashboardRepository(private val database: AppDatabase) {

    private val tripDao = database.tripDao()
    private val stationDao = database.metroStationDao()
    private val scheduledTripDao = database.scheduledTripDao()

    private val stationCheckpointDao =  database.stationCheckpointDao() // 1. Inject this DAO

    suspend fun deleteTrip(tripId: Long) {
        tripDao.markTripAsDeleted(tripId)
    }
    suspend fun restoreTrip(tripId: Long) {
        tripDao.restoreTrip(tripId)
    }
    fun getAllScheduledTrips(): Flow<List<ScheduledTrip>> {
        return database.scheduledTripDao().getAllActiveScheduledTrips()
    }
    suspend fun getAllStationNames(): List<String> {
        return stationDao.getAllStations().map { it.stationName }.sorted()
    }

    // --- ADDED: Scheduled Trips for the 2nd Tab ---
    fun getScheduledTrips(): Flow<List<ScheduledTrip>> {
        return scheduledTripDao.getAllActiveScheduledTrips()
    }
    fun getDashboardStats(): Flow<DashboardStats> {
        return combine(
            tripDao.getCompletedTripsCount(),
            tripDao.getTotalTravelTime(),
            getUniqueStationsFlow(),
            tripDao.getCompletedTripsSafe()
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
                totalStationsInNetwork = 289
            )
        }
    }


    /**
     * Get unique stations visited
     */
    private fun getUniqueStationsFlow(): Flow<Int> {
        return tripDao.getCompletedTripsSafe().map { completedTrips ->
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
    /**
     * Get frequent routes with time patterns
     */
    suspend fun getFrequentRoutes(): List<FrequentRoute> {
        val completedTrips = tripDao.getCompletedTripsSafe().first()

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
    fun getEnrichedTripHistory(): Flow<List<TripCardData>> {
        return tripDao.getRecentTrips(50).flatMapLatest { trips ->
            if (trips.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    trips.map { trip ->
                        stationCheckpointDao.getCheckpointsForTripFlow(trip.id)
                            .map { checkpoints -> trip to checkpoints }
                    }
                ) { tripCheckpointPairs ->

                    val allStations = database.metroStationDao().getAllStations()

                    tripCheckpointPairs.mapNotNull { (trip, checkpoints) ->
                        enrichTripData(trip, allStations, checkpoints)
                    }
                }
            }
        }
    }


    /**
     * Enrich a single trip with station metadata and checkpoints
     */
    private fun enrichTripData(
        trip: Trip,
        allStations: List<MetroStation>,
        checkpoints: List<StationCheckpoint> // 3. Add parameter
    ): TripCardData? {

        if (trip.endTime == null || trip.durationMinutes == null) return null

        // ✅ CHANGED: Match by station NAME instead of UUID
        val stationMapByName = allStations.associateBy { it.stationName }

        val visitedStations = checkpoints
            .sortedBy { it.stationOrder }
            .mapNotNull { checkpoint ->
                stationMapByName[checkpoint.stationName]
            }

        // ✅ CHANGED: Use only SOURCE and DESTINATION line colors (like Scheduled Trips)
        val sourceStation = stationMapByName[trip.sourceStationName]
        val destStation = stationMapByName[trip.destinationStationName]

        val lineColors = mutableSetOf<String>()
        sourceStation?.let { lineColors.add(it.lineColor) }
        destStation?.let { lineColors.add(it.lineColor) }

        val interchangeStations = visitedStations
            .filter { it.isInterchange }
            .map { it.stationName }
            .distinct()

        val expectedDuration = visitedStations.size * 2
        val delayMinutes = trip.durationMinutes - expectedDuration

        Log.d(
            "DASHBOARD_VERIFY",
            "trip=${trip.id}, checkpoints=${checkpoints.size}, visitedStations=${trip.visitedStations.size}"
        )


        return TripCardData(
            tripId = trip.id,
            sourceStationName = trip.sourceStationName,
            destinationStationName = trip.destinationStationName,
            startTime = trip.startTime,
            endTime = trip.endTime,
            durationMinutes = trip.durationMinutes,
            stationCount = visitedStations.size,
            interchangeStations = interchangeStations,
            lineColors = lineColors.toList(),
            expectedDurationMinutes = expectedDuration,
            delayMinutes = delayMinutes,
            hadSosAlert = trip.hadSosAlert,
            sosStationName = trip.sosStationName,
            sosTimestamp = trip.sosTimestamp,

            // 4. Pass the checkpoints to the UI model
            checkpoints = checkpoints
        )
    }
}