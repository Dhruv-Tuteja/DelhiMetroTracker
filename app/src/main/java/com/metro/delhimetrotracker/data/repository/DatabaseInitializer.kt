package com.metro.delhimetrotracker.data.repository

import android.content.Context
import androidx.room.Room
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import org.json.JSONObject
import java.util.UUID

object DatabaseInitializer {

    // 1. Singleton Pattern (Fixes 'Unresolved reference getDatabase')
    @Volatile
    private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "delhi_metro_db"
            )
                .fallbackToDestructiveMigration() // Handles version change safely
                .build()
            INSTANCE = instance
            instance
        }
    }

    // 2. Initializer with GTFS Support (Fixes 'missing gtfs_stations')
    suspend fun initializeStations(context: Context, db: AppDatabase) {
        val stationDao = db.metroStationDao()

        // Only initialize if the database is empty
        val existingCount = stationDao.getStationCount()
        if (existingCount > 0) return

        try {
            // Ensure this matches the file name in your assets folder
            val jsonString = context.assets.open("metro_stations_FINAL.json")
                .bufferedReader().use { it.readText() }

            val rootObject = JSONObject(jsonString)
            val linesArray = rootObject.getJSONArray("lines")
            val stations = mutableListOf<MetroStation>()

            for (i in 0 until linesArray.length()) {
                val lineObject = linesArray.getJSONObject(i)
                val metroLine = lineObject.getString("name")
                val lineColor = lineObject.getString("color")
                val stationsArray = lineObject.getJSONArray("stations")

                for (j in 0 until stationsArray.length()) {
                    val stationObject = stationsArray.getJSONObject(j)

                    // --- THE FIX: Parse the GTFS ID ---
                    val gtfsId = if (stationObject.has("gtfs_stop_id")) {
                        stationObject.getString("gtfs_stop_id")
                    } else {
                        null
                    }

                    val station = MetroStation(
                        stationId = UUID.randomUUID().toString(),
                        stationName = stationObject.getString("name"),
                        // Handle optional fields safely
                        stationNameHindi = if (stationObject.has("nameHindi")) stationObject.getString("nameHindi") else null,
                        metroLine = metroLine,
                        lineColor = lineColor,
                        latitude = stationObject.getDouble("latitude"),
                        longitude = stationObject.getDouble("longitude"),
                        sequenceNumber = stationObject.getInt("sequence"),
                        isInterchange = stationObject.optBoolean("interchange", false),

                        // Pass the parsed ID to the entity
                        gtfs_stop_id = gtfsId
                    )
                    stations.add(station)
                }
            }
            stationDao.insertStations(stations)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}