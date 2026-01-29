package com.metro.delhimetrotracker.data.repository

import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.StopTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// If you are not using Dagger/Hilt, you can remove @Inject and just pass the db manually
class MetroRepository(private val db: AppDatabase) {

    private val stationDao = db.metroStationDao()
    private val stopTimeDao = db.stopTimeDao()

    /**
     * Finds the next upcoming trains for a specific station ID (UUID)
     */
    suspend fun getNextTrainsForStation(stationId: String): List<StopTime> {
        // 1. Get the station details (to find the GTFS ID)
        val station = stationDao.getStationById(stationId)

        // 2. Check if we have a linked GTFS ID
        val gtfsId = station?.gtfs_stop_id

        if (gtfsId == null) {
            Log.e("MetroRepo", "No GTFS ID found for station: ${station?.stationName}")
            return emptyList()
        }

        // 3. Calculate current time in "HH:mm:ss" format
        // GTFS uses strict 24-hour format like "14:30:00"
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // 4. Query the schedule
        return stopTimeDao.getNextTrains(
            gtfsStopId = gtfsId,
            currentTime = currentTime,
            limit = 3 // Get next 3 trains
        )
    }
}