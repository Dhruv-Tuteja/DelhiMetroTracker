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
    @Query("SELECT * FROM trips WHERE status = :status AND isDeleted = 0 ORDER BY startTime DESC")
    fun getTripsByStatus(status: TripStatus): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE isDeleted = 0 ORDER BY startTime DESC LIMIT :limit")
    fun getRecentTrips(limit: Int = 10): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE isDeleted = 0 ORDER BY startTime DESC")
    fun getAllTrips(): Flow<List<Trip>>

    @Query("UPDATE trips SET isDeleted = 0, syncState = 'PENDING', lastModified = :timestamp WHERE id = :tripId")
    suspend fun restoreTrip(tripId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM trips WHERE status = 'IN_PROGRESS' AND isDeleted = 0 LIMIT 1")
    suspend fun getActiveTrip(): Trip?

    @Query("SELECT * FROM trips WHERE status = 'IN_PROGRESS' AND isDeleted = 0 LIMIT 1")
    fun getActiveTripFlow(): Flow<Trip?>

    // Use Long for time parameters in raw queries if Room's auto-conversion is strict
//    @Query("UPDATE trips SET status = :status, endTime = :endTime, durationMinutes = :duration WHERE id = :tripId")
//    suspend fun completeTrip(tripId: Long, status: TripStatus, endTime: Long, duration: Int)

    @Query("SELECT COUNT(*) FROM trips WHERE status = 'COMPLETED'AND isDeleted = 0")
    fun getCompletedTripsCount(): Flow<Int>

    @Query("SELECT SUM(durationMinutes) FROM trips WHERE status = 'COMPLETED'AND isDeleted = 0")
    fun getTotalTravelTime(): Flow<Int?>

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: Long)

    @Query("""
        UPDATE trips 
        SET visitedStations = :visitedListJson, 
            syncState = 'PENDING', 
            lastModified = :updateTime 
        WHERE id = :tripId
    """)
    suspend fun updateVisitedStations(
        tripId: Long,
        visitedListJson: String, // Changed from List<String> to String
        updateTime: Long = System.currentTimeMillis()
    )

    @Query("""
    UPDATE trips 
    SET status = :status, 
        endTime = :endTime, 
        durationMinutes = :duration,
        destinationStationName = :finalDestination,
        syncState = 'PENDING',  
        lastModified = :updateTime
    WHERE id = :tripId
    """)
    suspend fun completeTrip(
        tripId: Long,
        status: TripStatus,
        endTime: Long,
        duration: Int,
        finalDestination: String,
        updateTime: Long = System.currentTimeMillis() // Added timestamp
    )

    @Query("""
    UPDATE trips 
    SET had_sos_alert = 1, 
        sos_station_name = :stationName, 
        sos_timestamp = :timestamp,
        syncState = 'PENDING', 
        lastModified = :timestamp
    WHERE id = :tripId
""")
    suspend fun markTripWithSos(tripId: Long, stationName: String, timestamp: Long = System.currentTimeMillis())

    @Query("""
    UPDATE trips 
    SET status = :status, 
        endTime = :endTime, 
        durationMinutes = :duration, 
        destinationStationName = :finalDestination, 
        cancellation_reason = :reason,
        syncState = 'PENDING',
        lastModified = :updateTime
    WHERE id = :tripId
    """)
    suspend fun completeTripWithReason(
        tripId: Long,
        status: TripStatus,
        endTime: Long,
        duration: Int,
        finalDestination: String,
        reason: String,
        updateTime: Long = System.currentTimeMillis() // Added timestamp
    )
    @Query("SELECT * FROM trips WHERE status IN ('IN_PROGRESS', 'ACTIVE') AND isDeleted = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveTripIfExists(): Trip?

    @Query("SELECT * FROM trips WHERE isDeleted = 0 ORDER BY startTime DESC LIMIT 5")
    suspend fun getRecentTripsDebug(): List<Trip>

    @Query("SELECT * FROM station_checkpoints WHERE tripId = :tripId ORDER BY stationOrder ASC")
    suspend fun getCheckpointsForTrip(tripId: Long): List<StationCheckpoint>

    @Query("SELECT * FROM trips WHERE syncState = 'PENDING'")
    fun getPendingTrips(): Flow<List<Trip>>

    /**
     * Get all trips marked as deleted (for cloud cleanup)
     */
    @Query("SELECT * FROM trips WHERE isDeleted = 1")
    fun getDeletedTrips(): Flow<List<Trip>>
    // Inside TripDao interface
    @Query("DELETE FROM trips") // Make sure table name matches your Entity
    suspend fun deleteAllTrips()
    /**
     * Update sync status for a specific trip
     */
    @Query("UPDATE trips SET syncState = :newState, lastModified = :timestamp WHERE id = :tripId")
    suspend fun updateSyncStatus(tripId: Long, newState: String, timestamp: Long = System.currentTimeMillis())

    /**
     * Mark a trip as deleted (tombstoning)
     */
    @Query("UPDATE trips SET isDeleted = 1, syncState = 'PENDING', lastModified = :timestamp WHERE id = :tripId")
    suspend fun markTripAsDeleted(tripId: Long, timestamp: Long = System.currentTimeMillis())

    /**
     * Get trips that have conflicts (same trip modified on multiple devices)
     */
    @Query("SELECT * FROM trips WHERE syncState = 'CONFLICT'")
    fun getConflictedTrips(): Flow<List<Trip>>

    @Query("UPDATE trips SET isDeleted = 1 WHERE id = :id")
    suspend fun markTripAsDeleted(id: Long)

    @Query("UPDATE trips SET isDeleted = 0 WHERE id = :id")
    suspend fun restoreTrip(id: Long)

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

    // --- RENAMED TO AVOID CONFLICT ---
    @Query("SELECT * FROM metro_stations")
    fun getAllStationsFlow(): Flow<List<MetroStation>>

    // Used for the AutoComplete search in the dialog
    @Query("SELECT * FROM metro_stations WHERE stationName LIKE '%' || :query || '%'")
    fun searchStations(query: String): Flow<List<MetroStation>>

    @Query("SELECT * FROM metro_stations WHERE metroLine = :lineName ORDER BY sequenceNumber ASC")
    fun getStationsByLine(lineName: String): Flow<List<MetroStation>>

    @Query("SELECT COUNT(*) FROM metro_stations")
    suspend fun getStationCount(): Int

    @Query("""
        SELECT * FROM metro_stations 
        WHERE (metroLine = :line AND (sequenceNumber = :seq + 1 OR sequenceNumber = :seq - 1))
        OR (stationName = :name AND stationId != :currentId)
    """)
    suspend fun getAdjacentStations(line: String, seq: Int, name: String, currentId: String): List<MetroStation>

    // --- FIX: RENAMED FROM 'getAllStation' TO 'getAllStations' ---
    // --- FIX: This is the one MainActivity needs ---
    @Query("SELECT * FROM metro_stations ORDER BY stationName ASC")
    suspend fun getAllStations(): List<MetroStation>

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

// ... existing UserSettingsDao interface ...

@Dao
interface StopTimeDao {
    // 1. Efficient Batch Insert (for loading the text file)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stopTimes: List<StopTime>)

    // 2. Check if data exists (to avoid re-loading on every launch)
    @Query("SELECT COUNT(*) FROM stop_times")
    suspend fun getCount(): Int

    /**
     * 3. THE MAGIC QUERY: "Get Next Trains"
     * Finds trains arriving at THIS station (stop_id)
     * AFTER the current time (arrival_time > :time)
     * Ordered by soonest first.
     */
    @Query("""
    SELECT * FROM stop_times 
    WHERE stop_id = :gtfsStopId 
    AND arrival_minutes > :currentMinutes 
    ORDER BY arrival_minutes ASC 
    LIMIT :limit
""")
    suspend fun getNextTrains(gtfsStopId: String, currentMinutes: Int, limit: Int): List<StopTime>


    // In Daos.kt -> StopTimeDao

    // Finds the most recent train that has already passed
    @Query("SELECT * FROM stop_times WHERE stop_id = :gtfsStopId AND arrival_minutes < :currentMinutes ORDER BY arrival_minutes DESC LIMIT 1")
    suspend fun getPreviousTrain(gtfsStopId: String, currentMinutes: Int): StopTime?

    @Query("SELECT * FROM stop_times WHERE stop_id = :gtfsStopId AND arrival_minutes > :currentMinutes ORDER BY arrival_minutes ASC LIMIT 1")
    suspend fun getNextTrain(gtfsStopId: String, currentMinutes: Int): StopTime?


    @Query("""
    SELECT * FROM stop_times 
    WHERE trip_id = :tripId 
    AND stop_id = :gtfsStopId 
    LIMIT 1
""")
    suspend fun getArrivalForTrip(
        tripId: String,
        gtfsStopId: String
    ): StopTime?

    // In Daos.kt -> StopTimeDao

    // Finds a train that connects Current Station -> Next Station (Correct Direction)
    @Query("""
SELECT start_node.* FROM stop_times AS start_node
INNER JOIN stop_times AS end_node 
ON start_node.trip_id = end_node.trip_id
WHERE start_node.stop_id = :currentGtfsId 
AND end_node.stop_id = :nextGtfsId
AND start_node.arrival_minutes > :currentMinutes
AND end_node.stop_sequence > start_node.stop_sequence
ORDER BY start_node.arrival_minutes ASC
LIMIT 1
""")
    suspend fun getNextTrainInDirection(
        currentGtfsId: String,
        nextGtfsId: String,
        currentMinutes: Int
    ): StopTime?


    // Finds the LAST departed train in the correct direction
    @Query("""
SELECT start_node.* FROM stop_times AS start_node
INNER JOIN stop_times AS end_node 
ON start_node.trip_id = end_node.trip_id
WHERE start_node.stop_id = :currentGtfsId 
AND end_node.stop_id = :nextGtfsId
AND start_node.arrival_minutes < :currentMinutes
AND end_node.stop_sequence > start_node.stop_sequence
ORDER BY start_node.arrival_minutes DESC
LIMIT 1
""")
    suspend fun getPreviousTrainInDirection(
        currentGtfsId: String,
        nextGtfsId: String,
        currentMinutes: Int
    ): StopTime?

    @Query("DELETE FROM stop_times")
    suspend fun deleteAll()


    @Query("SELECT DISTINCT stop_id FROM stop_times LIMIT 50")
    suspend fun getAllStopIds(): List<String>

    @Query("SELECT * FROM stop_times WHERE stop_id = :stopId LIMIT 5")
    suspend fun getAnySampleTrains(stopId: String): List<StopTime>
}

@Dao
interface ScheduledTripDao {

    @Query("SELECT * FROM scheduled_trips WHERE isActive = 1 AND isDeleted = 0 ORDER BY scheduledTimeHour, scheduledTimeMinute")
    fun getAllActiveScheduledTrips(): Flow<List<ScheduledTrip>>

    @Query("SELECT * FROM scheduled_trips WHERE id = :id")
    suspend fun getScheduledTripById(id: Long): ScheduledTrip?

    @Insert
    suspend fun insert(scheduledTrip: ScheduledTrip): Long

    @Update
    suspend fun update(scheduledTrip: ScheduledTrip)

    @Delete
    suspend fun delete(scheduledTrip: ScheduledTrip)

    @Query("UPDATE scheduled_trips SET isActive = 0 WHERE id = :id")
    suspend fun deactivate(id: Long)

    @Query("DELETE FROM scheduled_trips WHERE isActive = 0 AND scheduledDate < :timestamp")
    suspend fun deleteExpiredOneTimeTrips(timestamp: Long)

    @Query("DELETE FROM scheduled_trips")
    suspend fun deleteAll()

    @Query("SELECT * FROM scheduled_trips WHERE syncState = 'PENDING'")
    fun getPendingScheduledTrips(): Flow<List<ScheduledTrip>>

    @Query("UPDATE scheduled_trips SET syncState = :newState, lastModified = :timestamp WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, newState: String, timestamp: Long = System.currentTimeMillis())
}