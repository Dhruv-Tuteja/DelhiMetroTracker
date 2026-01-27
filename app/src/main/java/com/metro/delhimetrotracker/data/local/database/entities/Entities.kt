package com.metro.delhimetrotracker.data.local.database.entities // Fixed package

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import androidx.room.ColumnInfo
import com.metro.delhimetrotracker.data.local.database.converters.Converters // Updated
import java.util.Date

@Entity(tableName = "trips")
@TypeConverters(Converters::class) // Use unified class
data class Trip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val sourceStationId: String,
    val sourceStationName: String,
    val destinationStationId: String,
    val destinationStationName: String,
    
    val metroLine: String, // e.g., "Red Line", "Blue Line"
    
    val startTime: Date,
    val endTime: Date? = null,
    
    val durationMinutes: Int? = null,
    
    // Stations visited during the journey
    val visitedStations: List<String>, // List of station IDs
    
    // Optional fare information
    val fare: Double? = null,
    
    // Journey status
    val status: TripStatus = TripStatus.IN_PROGRESS,
    
    // SMS tracking
    val emergencyContact: String, // Phone number
    val smsCount: Int = 0,
    
    // Additional metadata
    val createdAt: Date = Date(),
    val notes: String? = null,

    @ColumnInfo(name = "had_sos_alert")
    val hadSosAlert: Boolean = false,

    @ColumnInfo(name = "sos_station_name")
    val sosStationName: String? = null,

    @ColumnInfo(name = "sos_timestamp")
    val sosTimestamp: Long? = null,

    @ColumnInfo(name = "cancellation_reason")
    val cancellationReason: String? = null
)

enum class TripStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

/**
 * Entity representing a station checkpoint during a trip
 */
@Entity(tableName = "station_checkpoints")
@TypeConverters(Converters::class)
data class StationCheckpoint(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val tripId: Long, // Foreign key to Trip
    
    val stationId: String,
    val stationName: String,
    val stationOrder: Int, // Order in the journey (0 = source, n = destination)
    
    val arrivalTime: Date,
    val departureTime: Date? = null,
    
    // Detection metadata
    val detectionMethod: DetectionMethod,
    val confidence: Float, // 0.0 to 1.0
    
    // SMS status
    val smsSent: Boolean = false,
    val smsTimestamp: Date? = null,
    
    // Location data (if available)
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null
)

enum class DetectionMethod {
    GPS,
    ACCELEROMETER,
    NETWORK_CHANGE,
    TIME_BASED,
    GEOFENCE,
    MANUAL,
    HYBRID // Combination of multiple methods
}

/**
 * Entity representing metro station master data
 */
@Entity(tableName = "metro_stations")
data class MetroStation(
    @PrimaryKey
    val stationId: String,
    val stationName: String,
    val stationNameHindi: String? = null, // Added for bilingual support
    val metroLine: String,
    val lineColor: String,
    val latitude: Double,
    val longitude: Double,
    val sequenceNumber: Int,
    val isInterchange: Boolean = false,
    val interchangeLines: List<String>? = null // Uses the new List converter
)

/**
 * Entity for user settings and preferences
 */
@Entity(tableName = "user_settings")
data class UserSettings(
    @PrimaryKey
    val id: Int = 1, // Single row table
    
    // SMS settings
    val smsEnabled: Boolean = true,
    val emergencyContact: String? = null,
    val emergencyContactName: String? = null,
    
    // Notification settings
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val notificationEnabled: Boolean = true,
    
    // Detection settings
    val useGps: Boolean = true,
    val useAccelerometer: Boolean = true,
    val useNetworkDetection: Boolean = true,
    val detectionSensitivity: Float = 0.7f, // 0.0 to 1.0
    
    // Journey settings
    val autoStartJourney: Boolean = false,
    val saveJourneyHistory: Boolean = true,
    
    // Privacy settings
    val shareLocationData: Boolean = false,
    
    // UI preferences
    val darkMode: Boolean = false,
    val language: String = "en"
)
