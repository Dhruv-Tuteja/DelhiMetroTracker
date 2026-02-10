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
import java.util.Date
import android.util.Log
import com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod
import com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.TripStatus
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
        db: AppDatabase,
        userId: String,
        firestore: FirebaseFirestore
    ) {
        val snapshot = firestore.collection("users")
            .document(userId)
            .collection("trips")
            .get()
            .await()

        for (document in snapshot.documents) {

            val tripId = document.getLong("id") ?: continue
            val isDeleted = document.getBoolean("isDeleted") ?: false
            val cloudLastModified = document.getLong("lastModified") ?: 0L

            // ✅ CONFLICT RESOLUTION: Check local trip state
            val localTrip = db.tripDao().getTripByIdIncludingDeleted(tripId)

            if (isDeleted) {
                // Trip is deleted in cloud
                if (localTrip != null && !localTrip.isDeleted && localTrip.lastModified > cloudLastModified) {
                    // ✅ Local version is RESTORED and NEWER - keep it and skip cloud update
                    android.util.Log.d("SYNC_CONFLICT", "Trip $tripId: Local restoration is newer, keeping local version")
                    continue
                } else {
                    // Cloud deletion is authoritative
                    db.tripDao().markTripAsDeleted(tripId)
                    db.stationCheckpointDao().deleteCheckpointsByTrip(tripId)
                    android.util.Log.d("SYNC_DELETE", "Trip $tripId deleted from cloud, marked as deleted locally")
                    continue
                }
            }

            // ✅ If local trip is deleted but cloud has it restored, respect cloud version
            if (localTrip != null && localTrip.isDeleted && !isDeleted && cloudLastModified > localTrip.lastModified) {
                android.util.Log.d("SYNC_RESTORE", "Trip $tripId: Cloud restoration is newer, restoring from cloud")
                // Continue to restore the trip below
            }

            // 1️⃣ Parse checkpoints FIRST
            val checkpointsRaw =
                document.get("checkpoints") as? List<Map<String, Any>> ?: emptyList()

            val orderedStations = checkpointsRaw
                .sortedBy { (it["stationOrder"] as? Long) ?: 0L }
                .mapNotNull { it["stationId"] as? String }
                .distinct()

            // 2️⃣ Insert Trip WITHOUT visitedStations
            val trip = Trip(
                id = tripId,
                sourceStationId = document.getString("sourceStationId") ?: "",
                sourceStationName = document.getString("sourceStationName") ?: "",
                destinationStationId = document.getString("destinationStationId") ?: "",
                destinationStationName = document.getString("destinationStationName") ?: "",
                metroLine = document.getString("metroLine") ?: "",
                startTime = Date(document.getLong("startTime") ?: 0L),
                endTime = document.getLong("endTime")?.let { Date(it) },
                durationMinutes = document.getLong("durationMinutes")?.toInt(),
                visitedStations = orderedStations, // IMPORTANT
                fare = document.getDouble("fare"),
                status = TripStatus.valueOf(document.getString("status") ?: "COMPLETED"),
                emergencyContact = document.getString("emergencyContact") ?: "",
                smsCount = document.getLong("smsCount")?.toInt() ?: 0,
                createdAt = Date(document.getLong("createdAt") ?: System.currentTimeMillis()),
                notes = document.getString("notes"),
                hadSosAlert = document.getBoolean("hadSosAlert") ?: false,
                sosStationName = document.getString("sosStationName"),
                sosTimestamp = document.getLong("sosTimestamp"),
                cancellationReason = document.getString("cancellationReason"),
                syncState = "SYNCED",
                deviceId = document.getString("deviceId") ?: "cloud",
                lastModified = document.getLong("lastModified") ?: System.currentTimeMillis(),
                isDeleted = false,
                schemaVersion = document.getLong("schemaVersion")?.toInt() ?: 1
            )

            db.tripDao().insertTrip(trip)

            // 3️⃣ Replace checkpoints
            db.stationCheckpointDao().deleteCheckpointsByTrip(tripId)

            checkpointsRaw.forEach { map ->
                val checkpoint = StationCheckpoint(
                    tripId = tripId,
                    stationId = map["stationId"] as String,
                    stationName = map["stationName"] as String,
                    stationOrder = (map["stationOrder"] as Long).toInt(),
                    arrivalTime = Date(map["arrivalTime"] as Long),
                    departureTime = (map["departureTime"] as? Long)?.let { Date(it) },
                    detectionMethod = DetectionMethod.valueOf(map["detectionMethod"] as String),
                    confidence = (map["confidence"] as? Double)?.toFloat() ?: 1f,
                    smsSent = map["smsSent"] as? Boolean ?: false,
                    smsTimestamp = (map["smsTimestamp"] as? Long)?.let { Date(it) },
                    latitude = map["latitude"] as? Double,
                    longitude = map["longitude"] as? Double,
                    accuracy = (map["accuracy"] as? Double)?.toFloat(),
                    timestamp = map["timestamp"] as? Long
                )
                db.stationCheckpointDao().insertCheckpoint(checkpoint)
            }
//            val gson = com.google.gson.Gson()
//            // 4️⃣ 🔥 FORCE UPDATE visitedStations (THIS TRIGGERS FLOW)
//            if (orderedStations.isNotEmpty()) {
//                db.tripDao().updateVisitedStations(
//                    tripId = tripId,
//                    visitedListJson = gson.toJson(orderedStations), // ✅ JSON ARRAY
//                    updateTime = System.currentTimeMillis()
//                )
//            }

            android.util.Log.d(
                "SYNC_REBUILD",
                "Trip $tripId → stations=${orderedStations.size}, checkpoints=${checkpointsRaw.size}"
            )
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