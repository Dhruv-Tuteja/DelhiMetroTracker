package com.metro.delhimetrotracker.data.repository

import android.content.Context
import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.StopTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class GtfsLoader(private val context: Context, private val db: AppDatabase) {

    suspend fun loadStopTimesIfNeeded() = withContext(Dispatchers.IO) {
        val count = db.stopTimeDao().getCount()
        if (count > 0) {
            Log.d("GTFS", "Data already loaded. Skipping.")
            return@withContext
        }

        Log.d("GTFS", "Starting Import... This might take a moment.")

        try {
            val inputStream = context.assets.open("stop_times.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))

            reader.readLine() // Skip header

            val batch = ArrayList<StopTime>(2000)
            var line: String? = reader.readLine()

            while (line != null) {
                val tokens = line.split(",")
                if (tokens.size >= 5) {
                    // Mapping based on your file: trip_id(0), arrival(1), departure(2), stop_id(3), seq(4)
                    val stopTime = StopTime(
                        trip_id = tokens[0],
                        arrival_time = tokens[1],
                        departure_time = tokens[2],
                        stop_id = tokens[3],
                        stop_sequence = tokens[4].toIntOrNull() ?: 0
                    )
                    batch.add(stopTime)
                }

                if (batch.size >= 2000) {
                    db.stopTimeDao().insertAll(batch)
                    batch.clear()
                }

                line = reader.readLine()
            }

            if (batch.isNotEmpty()) {
                db.stopTimeDao().insertAll(batch)
            }
            Log.d("GTFS", "Import Complete!")

        } catch (e: Exception) {
            Log.e("GTFS", "Error importing stop_times", e)
        }
    }
}