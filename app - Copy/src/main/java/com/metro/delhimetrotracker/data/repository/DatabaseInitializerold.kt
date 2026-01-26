package com.metro.delhimetrotracker.data.repository

import android.content.Context
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import org.json.JSONObject

object DatabaseInitializerold {

    suspend fun initializeStations(context: Context, db: AppDatabase) {
        val stationDao = db.metroStationDao()

        // Only initialize if the database is empty
        val existingCount = stationDao.getStationCount()
        if (existingCount > 0) return

        try {
            val jsonString = context.assets.open("metro_stations3.json")
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
                    val station = MetroStation(
                        stationId = stationObject.getString("id"),
                        stationName = stationObject.getString("name"),
                        stationNameHindi = stationObject.optString("nameHindi", ""),
                        metroLine = metroLine,
                        lineColor = lineColor,
                        latitude = stationObject.getDouble("latitude"),
                        longitude = stationObject.getDouble("longitude"),
                        sequenceNumber = stationObject.getInt("sequence"),
                        isInterchange = stationObject.optBoolean("interchange", false)
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