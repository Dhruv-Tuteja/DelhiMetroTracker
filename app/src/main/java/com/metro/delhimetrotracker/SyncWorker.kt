package com.metro.delhimetrotracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // 1. Get access to your Room Database
            val db = (applicationContext as MetroTrackerApplication).database

            // 2. Check if a user is actually logged in
            val auth = FirebaseAuth.getInstance()
            val userId = auth.currentUser?.uid ?: return Result.failure()

            // 3. Refresh token if needed
            try {
                auth.currentUser?.getIdToken(true)?.await()
            } catch (e: Exception) {
                return Result.failure()
            }

            // 4. Get all trips that need syncing
            val pendingTrips = db.tripDao().getPendingTrips().first()
            val firestore = FirebaseFirestore.getInstance()

            val batchSize = 20
            val batches = pendingTrips.chunked(batchSize)

            for (batch in batches) {
                batch.forEach { trip ->
                    try {
                        // ✅ FIXED: Use the suspend function from Daos.kt
                        val checkpoints = db.tripDao().getCheckpointsForTrip(trip.id)

                        // ✅ Convert checkpoints to map list for Firestore
                        val checkpointDataList = checkpoints.map { checkpoint ->
                            mapOf(
                                "stationId" to checkpoint.stationId,
                                "stationName" to checkpoint.stationName,
                                "stationOrder" to checkpoint.stationOrder,
                                "arrivalTime" to checkpoint.arrivalTime.time,
                                "departureTime" to checkpoint.departureTime?.time,
                                "detectionMethod" to checkpoint.detectionMethod.name,
                                "confidence" to checkpoint.confidence,
                                "smsSent" to checkpoint.smsSent,
                                "smsTimestamp" to checkpoint.smsTimestamp?.time,
                                "latitude" to checkpoint.latitude,
                                "longitude" to checkpoint.longitude,
                                "accuracy" to checkpoint.accuracy,
                                "timestamp" to checkpoint.timestamp
                            )
                        }

                        val tripMap = tripToFirestoreMap(trip, checkpointDataList)

                        // Format ID to 4 digits (0001, 0002)
                        val formattedDocId = String.format(java.util.Locale.US, "%04d", trip.id)

                        firestore.collection("users")
                            .document(userId)
                            .collection("trips")
                            .document(formattedDocId)
                            .set(tripMap)
                            .await()

                        // Update local Room status to SYNCED
                        db.tripDao().updateSyncStatus(
                            tripId = trip.id,
                            newState = "SYNCED",
                            timestamp = System.currentTimeMillis()
                        )
                    } catch (e: Exception) {
                        db.tripDao().updateSyncStatus(
                            tripId = trip.id,
                            newState = "PENDING",
                            timestamp = System.currentTimeMillis()
                        )
                    }
                }
            }
            pullFromFirestore(db, userId, firestore)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun pullFromFirestore(
        db: com.metro.delhimetrotracker.data.local.database.AppDatabase,
        userId: String,
        firestore: FirebaseFirestore
    ) {
        // 1. Get the last sync time (You should save this in SharedPrefs ideally)
        // For now, we fetch everything, or you can implement a SharedPrefs check here.
        val lastSyncTime: Long = 0

        val snapshot = firestore.collection("users")
            .document(userId)
            .collection("trips")
            .whereGreaterThan("lastModified", lastSyncTime)
            .get()
            .await()

        for (document in snapshot.documents) {
            // 1. Parse Basic Trip Data
            // Note: Ideally use .toObject(Trip::class.java) if field names match exactly.
            // Here we assume the Trip exists or is inserted via a similar mapping.

            val tripId = document.getLong("id") ?: continue

            // (Optional: Insert the Trip entity here if it doesn't exist locally)
            // val trip = document.toObject(Trip::class.java)
            // db.tripDao().insertTrip(trip)

            // 2. 🔥 PARSE CHECKPOINTS
            val checkpointsList = document.get("checkpoints") as? List<Map<String, Any>>

            checkpointsList?.forEach { map ->
                try {
                    // Handle Enum conversion safely
                    val methodString = map["detectionMethod"] as? String ?: "GPS"
                    val methodEnum = try {
                        com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod.valueOf(methodString)
                    } catch (e: Exception) {
                        com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod.GPS
                    }

                    val checkpoint = com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint(
                        tripId = tripId,
                        stationId = map["stationId"] as String,
                        stationName = map["stationName"] as String,
                        stationOrder = (map["stationOrder"] as Long).toInt(),
                        arrivalTime = java.util.Date(map["arrivalTime"] as Long),
                        departureTime = (map["departureTime"] as? Long)?.let { java.util.Date(it) },

                        // ✅ Fixed: Enum Conversion
                        detectionMethod = methodEnum,

                        confidence = (map["confidence"] as? Double)?.toFloat() ?: 1.0f,
                        smsSent = (map["smsSent"] as? Boolean) ?: false,
                        smsTimestamp = (map["smsTimestamp"] as? Long)?.let { java.util.Date(it) },
                        latitude = map["latitude"] as? Double,
                        longitude = map["longitude"] as? Double,
                        accuracy = (map["accuracy"] as? Double)?.toFloat(),
                        timestamp = (map["timestamp"] as? Long) ?: System.currentTimeMillis()
                    )

                    // Insert into Local Room DB
                    db.stationCheckpointDao().insertCheckpoint(checkpoint)

                } catch (e: Exception) {
                    android.util.Log.e("SyncWorker", "Error parsing checkpoint for trip $tripId", e)
                }
            }
        }
    }

    // ✅ UPDATED: Now accepts checkpoints parameter
    private fun tripToFirestoreMap(trip: Trip, checkpoints: List<Map<String, Any?>>): Map<String, Any?> {
        return hashMapOf(
            "id" to trip.id,
            "sourceStationId" to trip.sourceStationId,
            "sourceStationName" to trip.sourceStationName,
            "destinationStationId" to trip.destinationStationId,
            "destinationStationName" to trip.destinationStationName,
            "metroLine" to trip.metroLine,
            "startTime" to trip.startTime.time,
            "endTime" to trip.endTime?.time,
            "durationMinutes" to trip.durationMinutes,
            "visitedStations" to trip.visitedStations,
            "fare" to trip.fare,
            "status" to trip.status.name,
            "emergencyContact" to trip.emergencyContact,
            "smsCount" to trip.smsCount,
            "createdAt" to trip.createdAt.time,
            "notes" to trip.notes,
            "hadSosAlert" to trip.hadSosAlert,
            "sosStationName" to trip.sosStationName,
            "sosTimestamp" to trip.sosTimestamp,
            "cancellationReason" to trip.cancellationReason,
            "syncState" to trip.syncState,
            "deviceId" to trip.deviceId,
            "lastModified" to trip.lastModified,
            "isDeleted" to trip.isDeleted,
            "schemaVersion" to trip.schemaVersion,
            // ✅ NEW: Add checkpoints array
            "checkpoints" to checkpoints
        )
    }
}