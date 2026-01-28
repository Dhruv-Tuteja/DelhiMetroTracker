package com.metro.delhimetrotracker.data.local.database.dao // Updated package name

import androidx.room.*
import com.metro.delhimetrotracker.data.local.database.entities.* // Updated import
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: Trip): Long

    @Update
    suspend fun updateTrip(trip: Trip)

    @Delete
    suspend fun deleteTrip(trip: Trip)

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: Long): Trip?

    @Query("SELECT * FROM trips WHERE id = :tripId")
    fun getTripByIdFlow(tripId: Long): Flow<Trip?>

    // Room uses the Date-to-Long TypeConverter automatically for :status parameters
    @Query("SELECT * FROM trips WHERE status = :status ORDER BY startTime DESC")
    fun getTripsByStatus(status: TripStatus): Flow<List<Trip>>

    @Query("SELECT * FROM trips ORDER BY startTime DESC LIMIT :limit")
    fun getRecentTrips(limit: Int = 10): Flow<List<Trip>>

    @Query("SELECT * FROM trips ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE status = 'IN_PROGRESS' LIMIT 1")
    suspend fun getActiveTrip(): Trip?

    @Query("SELECT * FROM trips WHERE status = 'IN_PROGRESS' LIMIT 1")
    fun getActiveTripFlow(): Flow<Trip?>

    // Use Long for time parameters in raw queries if Room's auto-conversion is strict
//    @Query("UPDATE trips SET status = :status, endTime = :endTime, durationMinutes = :duration WHERE id = :tripId")
//    suspend fun completeTrip(tripId: Long, status: TripStatus, endTime: Long, duration: Int)

    @Query("SELECT COUNT(*) FROM trips WHERE status = 'COMPLETED'")
    fun getCompletedTripsCount(): Flow<Int>

    @Query("SELECT SUM(durationMinutes) FROM trips WHERE status = 'COMPLETED'")
    fun getTotalTravelTime(): Flow<Int?>

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: Long)

    @Query("UPDATE trips SET visitedStations = :visitedList WHERE id = :tripId")
    suspend fun updateVisitedStations(tripId: Long, visitedList: List<String>)

    @Query("""
    UPDATE trips 
    SET status = :status, 
        endTime = :endTime, 
        durationMinutes = :duration,
        destinationStationName = :finalDestination 
    WHERE id = :tripId
""")
    suspend fun completeTrip(
        tripId: Long,
        status: TripStatus,
        endTime: Long,
        duration: Int,
        finalDestination: String
    )

    @Query("UPDATE trips SET had_sos_alert = 1, sos_station_name = :stationName, sos_timestamp = :timestamp WHERE id = :tripId")
    suspend fun markTripWithSos(tripId: Long, stationName: String, timestamp: Long)

    @Query("UPDATE trips SET status = :status, endTime = :endTime, durationMinutes = :duration, destinationStationName = :finalDestination, cancellation_reason = :reason WHERE id = :tripId")
    suspend fun completeTripWithReason(
        tripId: Long,
        status: TripStatus,
        endTime: Long,
        duration: Int,
        finalDestination: String,
        reason: String
    )
    @Query("SELECT * FROM trips WHERE status IN ('IN_PROGRESS', 'ACTIVE') ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveTripIfExists(): Trip?

    @Query("SELECT * FROM trips ORDER BY startTime DESC LIMIT 5")
    suspend fun getRecentTripsDebug(): List<Trip>

    @Query("SELECT * FROM station_checkpoints WHERE tripId = :tripId ORDER BY stationOrder ASC")
    suspend fun getCheckpointsForTrip(tripId: Long): List<StationCheckpoint>
}

@Dao
interface StationCheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckpoint(checkpoint: StationCheckpoint): Long

    @Update
    suspend fun updateCheckpoint(checkpoint: StationCheckpoint)

    @Query("SELECT * FROM station_checkpoints WHERE tripId = :tripId ORDER BY stationOrder ASC")
    fun getCheckpointsByTrip(tripId: Long): Flow<List<StationCheckpoint>>

    @Query("SELECT * FROM station_checkpoints WHERE tripId = :tripId ORDER BY stationOrder DESC LIMIT 1")
    suspend fun getLastCheckpoint(tripId: Long): StationCheckpoint?

    @Query("SELECT * FROM station_checkpoints WHERE tripId = :tripId AND smsSent = 0")
    suspend fun getPendingSmsCheckpoints(tripId: Long): List<StationCheckpoint>

    @Query("UPDATE station_checkpoints SET smsSent = 1, smsTimestamp = :timestamp WHERE id = :checkpointId")
    suspend fun markSmsAsSent(checkpointId: Long, timestamp: Long)

    @Query("DELETE FROM station_checkpoints WHERE tripId = :tripId")
    suspend fun deleteCheckpointsByTrip(tripId: Long)
}

@Dao
interface MetroStationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<MetroStation>)

    @Query("SELECT * FROM metro_stations WHERE stationId = :stationId LIMIT 1")
    suspend fun getStationById(stationId: String): MetroStation?

    @Query("SELECT * FROM metro_stations")
    fun getAllStations(): Flow<List<MetroStation>>

    // Used for the AutoComplete search in the dialog
    @Query("SELECT * FROM metro_stations WHERE stationName LIKE '%' || :query || '%'")
    fun searchStations(query: String): Flow<List<MetroStation>>

    @Query("SELECT * FROM metro_stations WHERE metroLine = :lineName ORDER BY sequenceNumber ASC")
    fun getStationsByLine(lineName: String): Flow<List<MetroStation>>

    @Query("SELECT COUNT(*) FROM metro_stations")
    suspend fun getStationCount(): Int
    /**
     * ESSENTIAL FOR INTERCHANGES (BFS)
     * This query finds stations that are physically connected:
     * 1. Stations on the same line with +/- 1 sequence number.
     * 2. Stations with the same name but different IDs (Interchange points).
     */
    @Query("""
        SELECT * FROM metro_stations 
        WHERE (metroLine = :line AND (sequenceNumber = :seq + 1 OR sequenceNumber = :seq - 1))
        OR (stationName = :name AND stationId != :currentId)
    """)
    suspend fun getAdjacentStations(line: String, seq: Int, name: String, currentId: String): List<MetroStation>
}
@Dao
interface UserSettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: UserSettings)

    @Update
    suspend fun updateSettings(settings: UserSettings)

    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun getSettings(): Flow<UserSettings?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun getSettingsOnce(): UserSettings?

    @Query("UPDATE user_settings SET emergencyContact = :contact, emergencyContactName = :name WHERE id = 1")
    suspend fun updateEmergencyContact(contact: String, name: String?)

    @Query("UPDATE user_settings SET smsEnabled = :enabled WHERE id = 1")
    suspend fun setSmsEnabled(enabled: Boolean)

    @Query("UPDATE user_settings SET vibrationEnabled = :enabled WHERE id = 1")
    suspend fun setVibrationEnabled(enabled: Boolean)
}