package com.metro.delhimetrotracker.data.repository

import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.data.local.database.entities.StopTime

class MetroRepository(db: AppDatabase) {

    private val stationDao = db.metroStationDao()
    private val stopTimeDao = db.stopTimeDao()

    suspend fun getStationById(id: String) = stationDao.getStationById(id)
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
    suspend fun getAllStations(): List<MetroStation> {
        return stationDao.getAllStations()
    }
}