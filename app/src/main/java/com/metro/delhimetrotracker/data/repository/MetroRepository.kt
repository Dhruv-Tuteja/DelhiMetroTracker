package com.metro.delhimetrotracker.data.repository

import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation // <--- ADD THIS
import com.metro.delhimetrotracker.data.local.database.entities.StopTime
import kotlinx.coroutines.flow.Flow // <--- ADD THIS
import java.util.Calendar

class MetroRepository(private val db: AppDatabase) {

    private val stationDao = db.metroStationDao()
    private val stopTimeDao = db.stopTimeDao()

    suspend fun getStationById(id: String) = stationDao.getStationById(id)

    // --- FIX: GET NEXT TRAIN (WITH FALLBACK) ---
    suspend fun getNextTrainForStation(
        currentStationId: String,
        nextStationId: String?,
        currentMinutes: Int
    ): StopTime? {

        val currentStation = stationDao.getStationById(currentStationId)
        val currentGtfsId = currentStation?.gtfs_stop_id ?: return null

        // 1. Try Strict Directional Search (Train going A -> B)
        if (nextStationId != null) {
            val nextStation = stationDao.getStationById(nextStationId)
            val nextGtfsId = nextStation?.gtfs_stop_id

            if (nextGtfsId != null) {
                val directionalTrain =
                    stopTimeDao.getNextTrainInDirection(
                        currentGtfsId,
                        nextGtfsId,
                        currentMinutes
                    )
                if (directionalTrain != null) {
                    return directionalTrain
                }
                Log.w(
                    "MetroRepo",
                    "⚠️ Strict search failed for $currentGtfsId -> $nextGtfsId. Using fallback."
                )
            }
        }

        // 2. FALLBACK: Return ANY train at this station
        return stopTimeDao.getNextTrain(currentGtfsId, currentMinutes)
    }

    // --- FIX: GET LAST DEPARTED TRAIN (WITH FALLBACK) ---
    suspend fun getLastDepartedTrain(
        currentStationId: String,
        nextStationId: String?,
        currentMinutes: Int
    ): StopTime? {

        val currentStation = stationDao.getStationById(currentStationId)
        val currentGtfsId = currentStation?.gtfs_stop_id ?: return null

        // 1. Try Strict Directional Search
        if (nextStationId != null) {
            val nextStation = stationDao.getStationById(nextStationId)
            val nextGtfsId = nextStation?.gtfs_stop_id

            if (nextGtfsId != null) {
                val directionalTrain =
                    stopTimeDao.getPreviousTrainInDirection(
                        currentGtfsId,
                        nextGtfsId,
                        currentMinutes
                    )
                if (directionalTrain != null) {
                    return directionalTrain
                }
            }
        }

        // 2. FALLBACK: Return ANY previous train
        return stopTimeDao.getPreviousTrain(currentGtfsId, currentMinutes)
    }

    // --- HELPER: Get Arrival Time at Specific Station ---
    suspend fun getArrivalForTrip(tripId: String, stationId: String): String? {
        val station = stationDao.getStationById(stationId)
        val gtfsId = station?.gtfs_stop_id ?: return null

        val stopTime = stopTimeDao.getArrivalForTrip(tripId, gtfsId)
        return stopTime?.arrival_time
    }

    // Helper needed for other parts of your app
    suspend fun getNextTrainsForStation(stationId: String): List<StopTime> {
        val station = stationDao.getStationById(stationId)
        val gtfsId = station?.gtfs_stop_id ?: return emptyList()

        val cal = Calendar.getInstance()
        val nowMinutes =
            cal.get(Calendar.HOUR_OF_DAY) * 60 +
                    cal.get(Calendar.MINUTE)

        return stopTimeDao.getNextTrains(gtfsId, nowMinutes, 3)
    }

    // Fix: Using the imported MetroStation entity
    suspend fun getAllStations(): List<MetroStation> {
        return stationDao.getAllStations()
    }

    // Fix: Using the imported Flow and MetroStation
    fun getStationsStream(): Flow<List<MetroStation>> {
        return stationDao.getAllStationsFlow()
    }
}