package com.metro.delhimetrotracker.data.repository

import android.util.Log
// IMPORT YOUR GENERATED FILES
import com.google.transit.realtime.FeedMessage
import com.google.transit.realtime.VehiclePosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class GtfsRealtimeRepository {

    companion object {
        private const val TAG = "GTFS_REALTIME"
        private const val API_KEY = "Ex9XdpVhKiJT426Q6ttKZx4E4aFkbGL2"
        private const val BASE_URL = "https://otd.delhi.gov.in/api/realtime/VehiclePositions.pb"
    }

    suspend fun getVehiclePositions(): List<VehiclePosition> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL?key=$API_KEY"
            Log.d(TAG, "Fetching vehicle positions from: $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            if (connection.responseCode == 200) {
                // 1. USE THE ADAPTER (As seen in FeedMessage.kt companion object)
                val feedMessage = FeedMessage.ADAPTER.decode(connection.inputStream)

                // 2. ACCESS 'entity' LIST DIRECTLY (Line 51 of FeedMessage.kt)
                Log.d(TAG, "✅ Received ${feedMessage.entity.size} vehicles")

                // 3. Map to vehicles (Assuming FeedEntity has a nullable 'vehicle' field)
                val vehicles = feedMessage.entity.mapNotNull { it.vehicle }

                connection.disconnect()
                vehicles
            } else {
                Log.e(TAG, "Failed with code: ${connection.responseCode}")
                connection.disconnect()
                emptyList()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error fetching vehicle positions: ${e.message}", e)
            emptyList()
        }
    }
}