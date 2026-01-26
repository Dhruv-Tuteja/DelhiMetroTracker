package com.metro.delhimetrotracker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.*
import com.metro.delhimetrotracker.utils.MetroNavigator
import com.metro.delhimetrotracker.utils.sensors.StationDetector
import com.metro.delhimetrotracker.utils.sms.SmsHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

/**
 * Production Foreground service for Delhi Metro Tracking.
 * Uses LifecycleService to provide easy access to lifecycleScope for DB operations.
 */
class JourneyTrackingService : LifecycleService() {

    private lateinit var database: AppDatabase
    private lateinit var stationDetector: StationDetector
    private lateinit var smsHelper: SmsHelper
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator

    private var currentTrip: Trip? = null
    private var currentStationIndex = 0
    private var stationRoute: List<MetroStation> = emptyList()

    private var isTracking = false
    private var lastDetectedStationId: String? = null

    companion object {
        private const val TAG = "JourneyTrackingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "metro_journey_tracking"

        const val ACTION_START_JOURNEY = "START_JOURNEY"
        const val ACTION_STOP_JOURNEY = "STOP_JOURNEY"
        const val ACTION_MANUAL_STATION_UPDATE = "ACTION_MANUAL_STATION_UPDATE"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_STATION_ID = "EXTRA_STATION_ID"
        const val EXTRA_TIME_OFFSET = "EXTRA_TIME_OFFSET"

        private const val LOCATION_UPDATE_INTERVAL = 10000L
        private const val LOCATION_FASTEST_INTERVAL = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        database = (application as MetroTrackerApplication).database
        stationDetector = StationDetector(this)
        smsHelper = SmsHelper(applicationContext)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_JOURNEY -> {
                val tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1L)
                if (tripId != -1L) startJourneyTracking(tripId)
            }
            ACTION_STOP_JOURNEY -> stopJourneyTracking()
            ACTION_MANUAL_STATION_UPDATE -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
                val timeOffset = intent.getStringExtra(EXTRA_TIME_OFFSET)
                if (stationId != null) handleManualJump(stationId, timeOffset ?: "Just now")
            }
        }

        // Start as foreground immediately to satisfy Android 12+ requirements
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun startJourneyTracking(tripId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Load trip FIRST
                currentTrip = database.tripDao().getTripById(tripId) ?: return@launch

                // Now you can safely use currentTrip
                val initialVisited = listOf(currentTrip!!.sourceStationId)
                database.tripDao().updateVisitedStations(tripId, initialVisited)
                currentTrip = currentTrip!!.copy(visitedStations = initialVisited)

                // Load path from database
                val path = MetroNavigator.findShortestPath(
                    database.metroStationDao(),
                    currentTrip!!.sourceStationId,
                    currentTrip!!.destinationStationId
                )
                stationRoute = path.distinctBy { it.stationId }
                currentStationIndex = 0

                isTracking = true

                withContext(Dispatchers.Main) {
                    startLocationUpdates()
                }

                // Safety: Delay accelerometer to prevent false triggers during boarding
                launch {
                    delay(15000)
                    startAccelerometerMonitoring()
                }

                // Initial Alert - FIXED: Changed parameter names to match SmsHelper
                smsHelper.sendJourneyStartAlert(
                    phoneNumber = currentTrip!!.emergencyContact,
                    source = currentTrip!!.sourceStationName,
                    dest = currentTrip!!.destinationStationName,
                    estDuration = stationRoute.size * 2
                )
            } catch (e: Exception) {
                Log.e(TAG, "Start error", e)
            }
        }
    }

    private fun handleManualJump(targetStationId: String, timeOffset: String) {
        val trip = currentTrip ?: return
        val targetIndex = stationRoute.indexOfFirst { it.stationId == targetStationId }
        if (targetIndex == -1) return

        val updatedVisitedList = stationRoute.subList(0, targetIndex + 1).map { it.stationId }

        lifecycleScope.launch(Dispatchers.IO) {
            database.tripDao().updateVisitedStations(trip.id, updatedVisitedList)

            // 🔥 FIX: Update currentTrip in memory!
            currentTrip = currentTrip!!.copy(visitedStations = updatedVisitedList)

            val targetStation = stationRoute[targetIndex]
            val smsMessage = "Manual Update: Reached ${targetStation.stationName} ($timeOffset). Tracking resumed."

            if (trip.emergencyContact.isNotEmpty()) {
                smsHelper.sendSms(trip.emergencyContact, smsMessage)
            }

            currentStationIndex = targetIndex + 1
            lastDetectedStationId = targetStationId

            withContext(Dispatchers.Main) {
                updateNotification("Jumped to: ${targetStation.stationName}")
            }

            if (currentStationIndex >= stationRoute.size) {
                stopJourneyTracking()
            }
        }
    }

    private fun handleStationDetection(method: DetectionMethod, confidence: Float, location: Location? = null) {
        if (!isTracking || stationRoute.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val nextStation = stationRoute.getOrNull(currentStationIndex) ?: return@launch
            if (lastDetectedStationId == nextStation.stationId) return@launch

            try {
                val currentLocation = location ?: fusedLocationClient.lastLocation.await() ?: return@launch

                val distance = FloatArray(1)
                Location.distanceBetween(
                    currentLocation.latitude, currentLocation.longitude,
                    nextStation.latitude, nextStation.longitude,
                    distance
                )

                if (distance[0] > 200f) return@launch

                // 🔍 DEBUG LOG
                Log.d(TAG, "STATION DETECTED: ${nextStation.stationName} (ID: ${nextStation.stationId}) at index $currentStationIndex")

                // Send SMS Alert
                smsHelper.sendStationAlert(
                    currentTrip!!.emergencyContact,
                    nextStation.stationName,
                    currentStationIndex + 1,
                    stationRoute.size,
                    stationRoute.getOrNull(currentStationIndex + 1)?.stationName,
                    currentStationIndex == stationRoute.size - 1
                )

                if (currentStationIndex == stationRoute.size - 2) triggerVibration()

                lastDetectedStationId = nextStation.stationId
                currentStationIndex++

                val updatedVisited = currentTrip!!.visitedStations.toMutableList().apply {
                    add(nextStation.stationId)
                }

                // 🔍 DEBUG LOG
                Log.d(TAG, "Updated visitedStations: $updatedVisited")

                database.tripDao().updateVisitedStations(currentTrip!!.id, updatedVisited)
                currentTrip = currentTrip!!.copy(visitedStations = updatedVisited)

                withContext(Dispatchers.Main) {
                    updateNotification("Current: ${nextStation.stationName}")
                }

                if (nextStation.stationId == currentTrip!!.destinationStationId) {
                    delay(2000)
                    stopJourneyTracking()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Detection error", e)
            }
        }
    }

    private fun startLocationUpdates() {
        // FIXED: Added permission check
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission not granted")
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error", e)
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                handleStationDetection(DetectionMethod.GPS, 1.0f, location)
            }
        }
    }

    private fun startAccelerometerMonitoring() {
        lifecycleScope.launch {
            stationDetector.monitorAccelerometer().collect { event ->
                if (event.isAtStation && event.confidence > 0.7f) {
                    handleStationDetection(DetectionMethod.ACCELEROMETER, event.confidence)
                }
            }
        }
    }

    private fun stopJourneyTracking() {
        isTracking = false
        fusedLocationClient.removeLocationUpdates(locationCallback)

        lifecycleScope.launch(Dispatchers.IO) {
            currentTrip?.let { trip ->
                val endTime = System.currentTimeMillis()
                val duration = ((endTime - trip.startTime.time) / 60000).toInt()

                // 🔍 DEBUG LOGS START
                Log.d(TAG, "========== STOP JOURNEY DEBUG ==========")
                Log.d(TAG, "Trip ID: ${trip.id}")
                Log.d(TAG, "Source Station: ${trip.sourceStationName} (ID: ${trip.sourceStationId})")
                Log.d(TAG, "Destination Station: ${trip.destinationStationName} (ID: ${trip.destinationStationId})")
                Log.d(TAG, "Visited Stations List: ${trip.visitedStations}")
                Log.d(TAG, "Visited Stations Count: ${trip.visitedStations.size}")
                Log.d(TAG, "StationRoute size: ${stationRoute.size}")
                Log.d(TAG, "StationRoute stations: ${stationRoute.map { "${it.stationName}(${it.stationId})" }}")

                val lastVisitedStationId = trip.visitedStations.lastOrNull() ?: trip.sourceStationId
                Log.d(TAG, "Last Visited Station ID: $lastVisitedStationId")

                // Try database lookup
                val actualEndStationObj = database.metroStationDao().getStationById(lastVisitedStationId)
                Log.d(TAG, "DB Lookup Result: ${actualEndStationObj?.stationName} (ID: ${actualEndStationObj?.stationId})")

                // Try stationRoute lookup
                val fromRoute = stationRoute.find { it.stationId == lastVisitedStationId }
                Log.d(TAG, "StationRoute Lookup Result: ${fromRoute?.stationName} (ID: ${fromRoute?.stationId})")

                val actualEndStation = actualEndStationObj?.stationName ?: trip.sourceStationName
                Log.d(TAG, "Final actualEndStation: $actualEndStation")
                Log.d(TAG, "========================================")
                // 🔍 DEBUG LOGS END

                database.tripDao().completeTrip(
                    trip.id,
                    TripStatus.COMPLETED,
                    endTime,
                    duration,
                    finalDestination = actualEndStation
                )

                val visitedCount = trip.visitedStations.size - 1
                val totalCount = stationRoute.size

                val message = when {
                    lastVisitedStationId == trip.destinationStationId ->
                        "✅ Journey completed. Reached destination: $actualEndStation."

                    visitedCount == 1 ->
                        "Journey ended at starting point: $actualEndStation."

                    else ->
                        "Journey ended at $actualEndStation ($visitedCount/$totalCount stations covered)."
                }

                smsHelper.sendSms(trip.emergencyContact, message)

                withContext(Dispatchers.Main) {
                    val intent = Intent("ACTION_JOURNEY_STOPPED")
                    sendBroadcast(intent)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun triggerVibration() {
        val pattern = longArrayOf(0, 500, 200, 500)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, JourneyTrackingService::class.java).apply {
            action = ACTION_STOP_JOURNEY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val totalStations = stationRoute.size
        val stationsDone = currentStationIndex // currentStationIndex tracks next unvisited

        // Calculate station progress for the notification bar
        val progress = if (totalStations > 1) stationsDone else 0
        val upcomingStation = stationRoute.getOrNull(currentStationIndex)?.stationName ?: "Destination"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Next Stop: $upcomingStation") // Show upcoming station in title
            .setContentText("$stationsDone/$totalStations stations covered") // Show stations count
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(totalStations, stationsDone, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Journey", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Metro Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(status: String) {
        val notification = createNotification() // Re-creates with latest currentTrip info
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    // FIXED: Removed nullable parameter
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}