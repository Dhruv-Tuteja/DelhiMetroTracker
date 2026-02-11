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

    // Improved Time Parser: Handles "8:05", "08:05", " 8:05 "
    private fun toMinutes(t: String): Int {
        return try {
            val cleanTime = t.trim()
            val parts = cleanTime.split(":")
            if (parts.size >= 2) {
                val hours = parts[0].toInt()
                val minutes = parts[1].toInt()
                (hours * 60) + minutes
            } else {
                0
            }
        } catch (_: Exception) {
            Log.e("GTFS_TIME", "Failed to parse time: $t")
            0
        }
    }

    // ADDED 'forceRefresh' parameter
    suspend fun loadStopTimesIfNeeded(forceRefresh: Boolean = false) = withContext(Dispatchers.IO) {
        val count = db.stopTimeDao().getCount()

        // CHECK: If data exists AND we are not forcing a refresh, skip.
        if (count > 0 && !forceRefresh) {
            Log.d("DEBUG_GTFS", "⚠️ Data loaded ($count rows). Skipping.")
            return@withContext
        }

        // RESET: If forcing refresh, wipe table first
        if (forceRefresh) {
            Log.d("DEBUG_GTFS", "♻️ Force Refresh detected. Wiping old data...")
            db.stopTimeDao().deleteAll()
        }

        Log.d("DEBUG_GTFS", "📥 Starting Fresh Import...")

        try {
            val inputStream = context.assets.open("stop_times.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))
            reader.readLine() // Skip Header

            val batch = ArrayList<StopTime>(3000)
            var line: String? = reader.readLine()
            var totalRows = 0

            while (line != null) {
                val tokens = line.split(",")
                if (tokens.size >= 5) {
                    val arrival = tokens[1].trim()

                    // PARSE MINUTES HERE
                    val mins = toMinutes(arrival)

                    // Only add valid times
                    if (mins > 0) {
                        batch.add(StopTime(
                            trip_id = tokens[0].trim(),
                            arrival_time = arrival,
                            departure_time = tokens[2].trim(),
                            stop_id = tokens[3].trim(),
                            arrival_minutes = mins, // <--- Correct integer value
                            stop_sequence = tokens[4].trim().toIntOrNull() ?: 0
                        ))
                        totalRows++
                    }
                }

                if (batch.size >= 3000) {
                    db.stopTimeDao().insertAll(batch)
                    batch.clear()
                    Log.d("DEBUG_GTFS", "⏳ Imported $totalRows rows...")
                }
                line = reader.readLine()
            }

            if (batch.isNotEmpty()) {
                db.stopTimeDao().insertAll(batch)
            }
            Log.d("DEBUG_GTFS", "🎉 RE-IMPORT COMPLETE! Total: $totalRows")

        } catch (e: Exception) {
            Log.e("DEBUG_GTFS", "❌ Error importing", e)
        }
    }
}