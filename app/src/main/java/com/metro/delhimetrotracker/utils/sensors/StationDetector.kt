package com.metro.delhimetrotracker.utils.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * Station detector that uses multiple sensors to detect when the train stops at a station
 */
class StationDetector(private val context: Context) {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    companion object {
        private const val TAG = "StationDetector"
        
        // Thresholds for station detection
        private const val MOTION_THRESHOLD = 0.5f // m/s² - below this = stationary
        private const val STOP_DURATION_MS = 15000L // 15 seconds - minimum stop time
        private const val MOVEMENT_WINDOW_MS = 3000L // 3 seconds - check movement over this window
        
        // Confidence levels
        private const val GPS_CONFIDENCE = 0.95f
        private const val ACCELEROMETER_CONFIDENCE = 0.75f
        private const val TIME_BASED_CONFIDENCE = 0.60f
        private const val NETWORK_CONFIDENCE = 0.85f
    }
    
    data class StationDetectionEvent(
        val isAtStation: Boolean,
        val confidence: Float,
        val detectionMethod: DetectionMethod,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    /**
     * Monitor accelerometer to detect stops
     */
    fun monitorAccelerometer(): Flow<StationDetectionEvent> = callbackFlow {
        val listener = object : SensorEventListener {
            private var lastStopTime: Long = 0
            private var isCurrentlyStopped = false
            private var hasNotifiedForThisStop = false
            private val accelerationHistory = mutableListOf<Float>()

            override fun onSensorChanged(event: SensorEvent?) {
                event ?: return

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Calculate magnitude of acceleration (excluding gravity)
                val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

                // Add to history
                accelerationHistory.add(kotlin.math.abs(acceleration))
                if (accelerationHistory.size > 30) {
                    accelerationHistory.removeAt(0)
                }

                // 🔧 NEED AT LEAST 30 READINGS (1 second) BEFORE MAKING DECISIONS
                if (accelerationHistory.size < 30) return

                // Calculate average acceleration over window
                val avgAcceleration = accelerationHistory.average().toFloat()

                val currentTime = System.currentTimeMillis()

                // 🔧 REQUIRE CONSISTENT STATE FOR 2 SECONDS BEFORE SWITCHING
                val STATE_CHANGE_DELAY = 2000L

                // Detect if train is stopped
                if (avgAcceleration < MOTION_THRESHOLD) {
                    if (!isCurrentlyStopped) {
                        // Possible stop detected - wait to confirm
                        if (lastStopTime == 0L) {
                            lastStopTime = currentTime
                            return
                        }

                        // 🔧 Must be stopped consistently for 2 seconds
                        if (currentTime - lastStopTime >= STATE_CHANGE_DELAY) {
                            isCurrentlyStopped = true
                            hasNotifiedForThisStop = false
                            Log.d(TAG, "Train stopped detected")
                        }
                    } else {
                        // Still stopped - check if long enough to be a station
                        val stopDuration = currentTime - lastStopTime
                        if (stopDuration >= STOP_DURATION_MS && !hasNotifiedForThisStop) {
                            trySend(
                                StationDetectionEvent(
                                    isAtStation = true,
                                    confidence = ACCELEROMETER_CONFIDENCE,
                                    detectionMethod = DetectionMethod.ACCELEROMETER,
                                    metadata = mapOf(
                                        "stopDuration" to stopDuration,
                                        "avgAcceleration" to avgAcceleration
                                    )
                                )
                            )
                            hasNotifiedForThisStop = true
                        }
                    }
                } else {
                    // Movement detected
                    if (isCurrentlyStopped) {
                        // Started moving again
                        isCurrentlyStopped = false
                        hasNotifiedForThisStop = false
                        lastStopTime = 0L  // 🔧 RESET
                        Log.d(TAG, "Train started moving")

                        trySend(
                            StationDetectionEvent(
                                isAtStation = false,
                                confidence = ACCELEROMETER_CONFIDENCE,
                                detectionMethod = DetectionMethod.ACCELEROMETER,
                                metadata = mapOf("avgAcceleration" to avgAcceleration)
                            )
                        )
                    } else {
                        // Still moving or brief movement - reset any partial stop detection
                        lastStopTime = 0L
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        
        sensorManager.registerListener(
            listener,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
    
    /**
     * Check if location is near a station
     */
    fun isNearStation(
        currentLocation: Location,
        stationLatitude: Double,
        stationLongitude: Double,
        radiusMeters: Float = 100f
    ): StationDetectionEvent {
        val stationLocation = Location("").apply {
            latitude = stationLatitude
            longitude = stationLongitude
        }
        
        val distance = currentLocation.distanceTo(stationLocation)
        val isNear = distance <= radiusMeters
        
        // Confidence based on distance (closer = higher confidence)
        val confidence = if (isNear) {
            (1f - (distance / radiusMeters)) * GPS_CONFIDENCE
        } else {
            0f
        }
        
        return StationDetectionEvent(
            isAtStation = isNear,
            confidence = confidence,
            detectionMethod = DetectionMethod.GPS,
            metadata = mapOf(
                "distance" to distance,
                "accuracy" to currentLocation.accuracy
            )
        )
    }
    
    /**
     * Time-based detection - estimate station based on elapsed time and known route
     */
    fun estimateStationByTime(
        startTime: Long,
        averageTimeBetweenStations: Long = 120000L, // 2 minutes default
        stationCount: Int
    ): StationDetectionEvent {
        val elapsedTime = System.currentTimeMillis() - startTime
        val estimatedStationIndex = (elapsedTime / averageTimeBetweenStations).toInt()
        
        val isValidEstimate = estimatedStationIndex in 0 until stationCount
        
        return StationDetectionEvent(
            isAtStation = isValidEstimate,
            confidence = TIME_BASED_CONFIDENCE,
            detectionMethod = DetectionMethod.TIME_BASED,
            metadata = mapOf(
                "elapsedTime" to elapsedTime,
                "estimatedStation" to estimatedStationIndex
            )
        )
    }
    
    /**
     * Combine multiple detection methods for higher accuracy
     */
    fun combineDetectionMethods(
        events: List<StationDetectionEvent>
    ): StationDetectionEvent {
        if (events.isEmpty()) {
            return StationDetectionEvent(
                isAtStation = false,
                confidence = 0f,
                detectionMethod = DetectionMethod.HYBRID
            )
        }
        
        // Weighted average of confidences
        val totalConfidence = events.sumOf { it.confidence.toDouble() }.toFloat()
        val avgConfidence = totalConfidence / events.size
        
        // Majority vote for isAtStation
        val atStationVotes = events.count { it.isAtStation }
        val isAtStation = atStationVotes > events.size / 2
        
        return StationDetectionEvent(
            isAtStation = isAtStation,
            confidence = avgConfidence,
            detectionMethod = DetectionMethod.HYBRID,
            metadata = mapOf(
                "methods" to events.map { it.detectionMethod.name },
                "votes" to atStationVotes
            )
        )
    }
    // Add to StationDetector.kt
    fun getMockEvent(isAtStation: Boolean): StationDetectionEvent {
        return StationDetectionEvent(
            isAtStation = isAtStation,
            confidence = 1.0f, // 100% confidence for testing
            detectionMethod = DetectionMethod.HYBRID,
            metadata = mapOf("simulation" to true)
        )
    }
}
