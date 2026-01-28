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
                        val tripMap = tripToFirestoreMap(trip)

                        // ========================================================
                        // 🔥 CRITICAL FIX: Format ID to 4 digits (0001, 0002)
                        // ========================================================
                        val formattedDocId = String.format(java.util.Locale.US, "%04d", trip.id)

                        firestore.collection("users")
                            .document(userId)
                            .collection("trips")
                            .document(formattedDocId) // <--- CHANGED HERE
                            .set(tripMap)
                            .await()

                        // 7. Update local Room status to SYNCED
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

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun tripToFirestoreMap(trip: Trip): Map<String, Any?> {
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
            "schemaVersion" to trip.schemaVersion
        )
    }
}