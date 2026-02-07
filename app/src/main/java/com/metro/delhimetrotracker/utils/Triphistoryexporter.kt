package com.metro.delhimetrotracker.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TripHistoryExporter(private val context: Context) {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun exportToCSV(trips: List<Trip>): Uri? {
        try {
            val fileName = "metro_trips_${System.currentTimeMillis()}.csv"
            val file = File(context.cacheDir, fileName)

            file.bufferedWriter().use { writer ->
                // Write CSV header
                writer.write("Trip ID,Source Station,Destination Station,Metro Line,Start Time,End Time,Duration (min),Status,Emergency Contact,SMS Count,Had SOS Alert,SOS Station,Fare\n")

                // Write trip data
                trips.forEach { trip ->
                    val line = buildString {
                        append("${trip.id},")
                        append("\"${trip.sourceStationName}\",")
                        append("\"${trip.destinationStationName}\",")
                        append("\"${trip.metroLine}\",")
                        append("\"${dateFormat.format(trip.startTime)}\",")
                        append("\"${trip.endTime?.let { dateFormat.format(it) } ?: "N/A"}\",")
                        append("${trip.durationMinutes ?: "N/A"},")
                        append("${trip.status},")
                        append("\"${trip.emergencyContact}\",")
                        append("${trip.smsCount},")
                        append("${trip.hadSosAlert},")
                        append("\"${trip.sosStationName ?: "N/A"}\",")
                        append("${trip.fare ?: "N/A"}")
                    }
                    writer.write("$line\n")
                }
            }

            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun exportToJSON(trips: List<Trip>): Uri? {
        try {
            val fileName = "metro_trips_${System.currentTimeMillis()}.json"
            val file = File(context.cacheDir, fileName)

            val jsonArray = JSONArray()

            trips.forEach { trip ->
                val jsonTrip = JSONObject().apply {
                    put("tripId", trip.id)
                    put("sourceStation", JSONObject().apply {
                        put("id", trip.sourceStationId)
                        put("name", trip.sourceStationName)
                    })
                    put("destinationStation", JSONObject().apply {
                        put("id", trip.destinationStationId)
                        put("name", trip.destinationStationName)
                    })
                    put("metroLine", trip.metroLine)
                    put("startTime", dateFormat.format(trip.startTime))
                    put("endTime", trip.endTime?.let { dateFormat.format(it) })
                    put("durationMinutes", trip.durationMinutes)
                    put("status", trip.status.toString())
                    put("emergencyContact", trip.emergencyContact)
                    put("smsCount", trip.smsCount)
                    put("hadSosAlert", trip.hadSosAlert)
                    put("sosStation", trip.sosStationName)
                    put("sosTimestamp", trip.sosTimestamp?.let { Date(it) }?.let { dateFormat.format(it) })
                    put("fare", trip.fare)
                    put("visitedStations", JSONArray(trip.visitedStations))
                    put("notes", trip.notes)
                }
                jsonArray.put(jsonTrip)
            }

            val exportData = JSONObject().apply {
                put("exportDate", dateFormat.format(Date()))
                put("totalTrips", trips.size)
                put("trips", jsonArray)
            }

            file.writeText(exportData.toString(2))

            return FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}