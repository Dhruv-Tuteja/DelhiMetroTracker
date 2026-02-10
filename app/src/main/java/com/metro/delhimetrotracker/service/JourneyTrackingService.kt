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
import com.metro.delhimetrotracker.utils.sms.SmsHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*
import com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod.MANUAL
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.metro.delhimetrotracker.ui.TrackingActivity
import com.metro.delhimetrotracker.data.model.TripCardData
import android.widget.Toast
import android.Manifest
import com.metro.delhimetrotracker.data.repository.RoutePlanner
import com.metro.delhimetrotracker.ui.MainActivity
// Add these imports at the top of JourneyTrackingService.kt
import androidx.work.*
import com.metro.delhimetrotracker.worker.SyncWorker
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import kotlinx.coroutines.flow.firstOrNull
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Production Foreground service for Delhi Metro Tracking.
 * Uses LifecycleService to provide easy access to lifecycleScope for DB operations.
 */
class JourneyTrackingService : LifecycleService() {

    private lateinit var database: AppDatabase
    private lateinit var smsHelper: SmsHelper
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    private var lastLatitude: Double? = null
    private var lastLongitude: Double? = null

    private var currentTrip: Trip? = null
    private var currentStationIndex = 0
    private var stationRoute: List<MetroStation> = emptyList()

    private var isTracking = false
    private var lastDetectedStationId: String? = null

    private var pressCount = 0
    private var lastPressTime = 0L

    private var routePreference: RoutePlanner.RoutePreference = RoutePlanner.RoutePreference.SHORTEST_PATH

    private var lastStationDetectionTime: Long = System.currentTimeMillis()
    private var autoSosCheckJob: Job? = null

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

    fun shouldAutoSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getBoolean("auto_sync", true)
    }


    override fun onCreate() {
        super.onCreate()
        database = (application as MetroTrackerApplication).database
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

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(powerButtonReceiver, filter)
    }
    override fun onDestroy() {
        setTripActive(false)
        super.onDestroy()
        try {
            unregisterReceiver(powerButtonReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }
    private suspend fun isSmsEnabled(): Boolean {
        return database.userSettingsDao()
            .getUserSettings()
            .firstOrNull()
            ?.smsEnabled
            ?: true // default = enabled
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_JOURNEY -> {
                val tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1L)
                // Get route preference from intent
                val preferenceString = intent.getStringExtra("ROUTE_PREFERENCE")
                routePreference = try {
                    RoutePlanner.RoutePreference.valueOf(preferenceString ?: "SHORTEST_PATH")
                } catch (e: Exception) {
                    RoutePlanner.RoutePreference.SHORTEST_PATH
                }

                if (tripId != -1L) startJourneyTracking(tripId)
            }
            ACTION_STOP_JOURNEY -> stopJourneyTracking()
            ACTION_MANUAL_STATION_UPDATE -> {
                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
                val timeOffset = intent.getStringExtra(EXTRA_TIME_OFFSET)
                if (stationId != null) handleManualJump(stationId, timeOffset ?: "Just now")
            }
        }

        // Only start foreground for actual journey tracking, not for stop commands
        if (intent?.action == ACTION_START_JOURNEY) {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
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
                val initialJson = org.json.JSONArray(initialVisited).toString()
                database.tripDao().updateVisitedStations(tripId, initialJson)
                currentTrip = currentTrip!!.copy(visitedStations = initialVisited)

                // Load path using selected preference
                val routePlanner = RoutePlanner(database)
                val route = routePlanner.findRoute(
                    currentTrip!!.sourceStationId,
                    currentTrip!!.destinationStationId,
                    routePreference
                )
                stationRoute = route?.segments?.flatMap { it.stations }?.distinctBy { it.stationId }
                    ?: emptyList()
                currentStationIndex = 0

                isTracking = true
                setTripActive(true)


                withContext(Dispatchers.Main) {
                    val notification = createNotification()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    startLocationUpdates()
                    startAutoSosMonitoring()
                }

                if (isSmsEnabled()) {
                    smsHelper.sendJourneyStartAlert(
                        phoneNumber = currentTrip!!.emergencyContact,
                        source = currentTrip!!.sourceStationName,
                        dest = currentTrip!!.destinationStationName,
                        estDuration = stationRoute.size * 2
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Start error", e)
            }
        }
    }
    private fun setTripActive(active: Boolean) {
        getSharedPreferences("trip_state", MODE_PRIVATE)
            .edit()
            .putBoolean("is_trip_active", active)
            .apply()
    }


    private fun handleManualJump(targetStationId: String, timeOffset: String) {

        Log.d(TAG, "Manual Jump Requested: $targetStationId, CurrentTrip is null? ${currentTrip == null}")

        val trip = currentTrip ?: return
        val targetIndex = stationRoute.indexOfFirst { it.stationId == targetStationId }
        if (targetIndex == -1) {
            Log.e(TAG, "Station ID not found in current route!")
            return
        }

        val updatedVisitedList = stationRoute.subList(0, targetIndex + 1).map { it.stationId }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val jsonString = JSONArray(updatedVisitedList).toString()
                database.tripDao().updateVisitedStations(trip.id, jsonString)
                database.tripDao().updateSyncStatus(trip.id, "PENDING", System.currentTimeMillis())

                // 🔥 FIX: Update currentTrip in memory!
                currentTrip = currentTrip!!.copy(visitedStations = updatedVisitedList)

                if (targetIndex >= currentStationIndex) {
                    val jumpedStations = stationRoute.subList(currentStationIndex, targetIndex + 1)

                    jumpedStations.forEachIndexed { index, station ->
                        val checkpoint = StationCheckpoint(
                            tripId = trip.id,
                            stationId = station.stationId,
                            stationName = station.stationName,
                            // Calculate the correct sequence number (0-based or 1-based depending on your preference)
                            stationOrder = currentStationIndex + index,
                            arrivalTime = Date(), // Use current time for manual jumps
                            detectionMethod = MANUAL,
                            confidence = 1.0f,
                            latitude = null,
                            longitude = null
                        )
                        database.stationCheckpointDao().insertCheckpoint(checkpoint)
                        Log.d(TAG, "Created manual checkpoint for: ${station.stationName}")
                    }
                }

                val targetStation = stationRoute[targetIndex]
                val smsMessage = "Manual Update: Reached ${targetStation.stationName} ($timeOffset). Tracking resumed."

                if (trip.emergencyContact.isNotEmpty() && isSmsEnabled()) {
                    smsHelper.sendSms(trip.emergencyContact, smsMessage)
                }


                currentStationIndex = targetIndex + 1
                lastDetectedStationId = targetStationId
                lastStationDetectionTime = System.currentTimeMillis() // ADD THIS LINE


                withContext(Dispatchers.Main) {
                    updateNotification("Jumped to: ${targetStation.stationName}")
                }

                if(currentStationIndex >= stationRoute.size) {
                    stopJourneyTracking()
                }
                Log.d(TAG, "Manual Jump Successful: Updated DB with ${updatedVisitedList.size} stations")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update manual jump in DB", e)
            }
        }
    }

    private fun handleStationDetection(
        method: DetectionMethod,
        confidence: Float,
        location: Location? = null
    ) {
        if (!isTracking || stationRoute.isEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentLocation =
                    location ?: fusedLocationClient.lastLocation.await() ?: return@launch

                lastLatitude = currentLocation.latitude
                lastLongitude = currentLocation.longitude

                // 1️⃣ Build candidate stations (max 4 ahead)
                val candidates = stationRoute
                    .drop(currentStationIndex)
                    .take(4)
                    .ifEmpty { return@launch }

                var bestMatch: MetroStation? = null
                var bestIndex = -1
                var bestDistance = Float.MAX_VALUE

                candidates.forEachIndexed { offset, station ->
                    val result = FloatArray(1)
                    Location.distanceBetween(
                        currentLocation.latitude,
                        currentLocation.longitude,
                        station.latitude,
                        station.longitude,
                        result
                    )

                    val distance = result[0]
                    if (distance <= 200f && distance < bestDistance) {
                        bestMatch = station
                        bestIndex = currentStationIndex + offset
                        bestDistance = distance
                    }
                }

                if (bestMatch == null || bestIndex < currentStationIndex) return@launch

                val trip = currentTrip ?: return@launch

                // 2️⃣ Update visited stations
                val newVisited = stationRoute
                    .subList(0, bestIndex + 1)
                    .map { it.stationId }

                database.tripDao().updateVisitedStations(trip.id, JSONArray(newVisited).toString())
                database.tripDao().updateSyncStatus(trip.id, "PENDING", System.currentTimeMillis())

                currentTrip = trip.copy(visitedStations = newVisited)

                // 3️⃣ Insert checkpoints
                for (i in currentStationIndex..bestIndex) {
                    val station = stationRoute[i]
                    val checkpoint = StationCheckpoint(
                        tripId = trip.id,
                        stationId = station.stationId,
                        stationName = station.stationName,
                        stationOrder = i,
                        arrivalTime = Date(),
                        detectionMethod = method,
                        confidence = confidence,
                        latitude = currentLocation.latitude,
                        longitude = currentLocation.longitude
                    )
                    database.stationCheckpointDao().insertCheckpoint(checkpoint)
                }

                currentStationIndex = bestIndex + 1
                lastDetectedStationId = bestMatch.stationId
                lastStationDetectionTime = System.currentTimeMillis()

                // 4️⃣ Prepare SMS details
                val reachedStation = bestMatch.stationName
                val upcomingStation =
                    stationRoute.getOrNull(currentStationIndex)?.stationName ?: "Destination"

                val stationsLeft = (stationRoute.size - currentStationIndex).coerceAtLeast(0)

                val batteryIntent = registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val batteryPercent =
                    if (level >= 0 && scale > 0) (level * 100 / scale) else -1

                val batteryText =
                    if (batteryPercent >= 0) "$batteryPercent%" else "Unknown"

                val smsMessage = """
🚉 Metro Update

Reached: $reachedStation
Next: $upcomingStation
Stations left: $stationsLeft
Battery: $batteryText

- Delhi Metro Tracker
            """.trimIndent()

                // 5️⃣ Send SMS (if enabled)
                if (trip.emergencyContact.isNotEmpty() && isSmsEnabled()) {
                    smsHelper.sendSms(trip.emergencyContact, smsMessage)
                }

                // 6️⃣ Update notification
                withContext(Dispatchers.Main) {
                    updateNotification("Current: $reachedStation")
                }

                // 7️⃣ End journey if destination reached
                if (bestMatch.stationId == trip.destinationStationId) {
                    delay(1500)
                    stopJourneyTracking()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Station detection error", e)
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
    // Inside JourneyTrackingService.kt

    private fun subscribeToLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()

        // ADD THE PERMISSION CHECK HERE
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } else {
            // Fallback if permission was revoked while service was running
            Log.e("JourneyService", "Location permission not granted")
            stopSelf()
        }
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                // Check for mock location
                if (isLocationMocked(location)) {
                    handleMockLocationDetected()
                    return
                }

                // Continue with normal station detection logic
                handleStationDetection(DetectionMethod.GPS, 1.0f, location)
            }
        }
    }

    private fun stopJourneyTracking() {
        isTracking = false
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

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
                scheduleSyncAfterTripEnd()

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

                if (isSmsEnabled()) {
                    smsHelper.sendSms(trip.emergencyContact, message)
                }

                withContext(Dispatchers.Main) {
                    val intent = Intent("ACTION_JOURNEY_STOPPED")
                    sendBroadcast(intent)
                    setTripActive(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun triggerVibration() {
        val pattern = longArrayOf(0,1000,200,1000,200,1000,200,1000,200,1000,200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun createNotification(): Notification {
        // Create intent to open app in its current state
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, JourneyTrackingService::class.java).apply {
            action = ACTION_STOP_JOURNEY
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val totalStations = if (stationRoute.isNotEmpty()) stationRoute.size else 0
        val stationsDone = currentStationIndex.coerceIn(0, totalStations)

        val progress = if (totalStations > 1) stationsDone else 0
        val upcomingStation = stationRoute.getOrNull(currentStationIndex)?.stationName
            ?: currentTrip?.destinationStationName
            ?: "Destination"

        val sosIntent = Intent(this, TrackingActivity::class.java).apply {
            action = "ACTION_SOS_FROM_NOTIFICATION"
            putExtra("EXTRA_TRIP_ID", currentTrip?.id ?: -1L)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sosPendingIntent = PendingIntent.getActivity(
            this,
            1,
            sosIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Next Stop: $upcomingStation")
            .setContentText("$stationsDone/$totalStations stations covered")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(totalStations, stationsDone, false)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_notification, "🆘 SOS", sosPendingIntent)
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
    private val powerButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SCREEN_ON) {
                val now = System.currentTimeMillis()

                if (now - lastPressTime < 1000) { // Within 1 second
                    pressCount++
                } else {
                    pressCount = 1
                }

                lastPressTime = now

                if (pressCount >= 5) {
                    val activeTripId = currentTrip?.id ?: -1L
                    // Launch TrackingActivity with SOS flag
                    val sosIntent = Intent(context, TrackingActivity::class.java).apply {
                        putExtra("TRIGGER_SOS", true)
                        putExtra("EXTRA_TRIP_ID", activeTripId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context?.startActivity(sosIntent)
                    pressCount = 0
                }
            }
        }
    }

    private fun isLocationMocked(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 (API 31) and above
            location.isMock
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            // API 18-30
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        } else {
            false
        }
    }

    private fun handleMockLocationDetected() {
        lifecycleScope.launch(Dispatchers.IO) {
            val trip = currentTrip

            if (trip != null && trip.emergencyContact.isNotEmpty()) {
                try {
                    // Get last known location
                    val lastLocation = if (ActivityCompat.checkSelfPermission(
                            this@JourneyTrackingService,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        fusedLocationClient.lastLocation.await()
                    } else {
                        null
                    }

                    // Get last visited station
                    val lastVisitedStationId = trip.visitedStations.lastOrNull() ?: trip.sourceStationId
                    val lastVisitedStation = database.metroStationDao().getStationById(lastVisitedStationId)
                    val lastStationName = lastVisitedStation?.stationName ?: trip.sourceStationName

                    // Build location string
                    val locationString = if (lastLocation != null) {
                        "https://maps.google.com/?q=${lastLocation.latitude},${lastLocation.longitude}"
                    } else {
                        "Location unavailable"
                    }

                    // Send emergency alert
                    val alertMessage = """
⚠️ SECURITY ALERT - FAKE GPS DETECTED!

Mock location detected on traveler's device.
Last Known Station: $lastStationName
Last Known Location: $locationString

Trip tracking has been terminated. Please contact the traveler immediately.

- Delhi Metro Tracker Security
                    """.trimIndent()

                    if (isSmsEnabled()) {
                        smsHelper.sendSms(trip.emergencyContact, alertMessage)
                    }
                    Log.d(TAG, "Mock location alert sent to ${trip.emergencyContact}")

                    // Mark trip as cancelled in database
                    database.tripDao().completeTripWithReason(
                        trip.id,
                        TripStatus.CANCELLED,
                        System.currentTimeMillis(),
                        ((System.currentTimeMillis() - trip.startTime.time) / 60000).toInt(),
                        finalDestination = lastStationName,
                        reason = "FAKE_GPS_DETECTED"
                    )

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send mock location alert", e)
                }
            }

            // Stop tracking and notify user
            // Stop tracking and notify user
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@JourneyTrackingService,
                    "⚠️ Mock location detected! Trip tracking disabled.",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent("ACTION_MOCK_LOCATION_DETECTED")
                sendBroadcast(intent)

                // Redirect to MainActivity with alert flag
                val mainIntent = Intent(this@JourneyTrackingService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("MOCK_LOCATION_DETECTED", true)
                }
                startActivity(mainIntent)

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    private fun scheduleSyncAfterTripEnd() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        if (shouldAutoSync(applicationContext)) {
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                "TripSync",
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
        } else {
            Log.d("Sync", "Auto-sync skipped due to settings")
        }
    }
    private fun startAutoSosMonitoring() {
        // Cancel any existing monitoring
        autoSosCheckJob?.cancel()

        // Check every minute if auto-SOS should trigger
        autoSosCheckJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive && isTracking) {
                delay(60_000) // Check every 1 minute
                checkAutoSos()
            }
        }
    }

    private fun stopAutoSosMonitoring() {
        autoSosCheckJob?.cancel()
        autoSosCheckJob = null
    }

    private fun checkAutoSos() {
        // Check if auto-SOS is enabled in settings
        val sosEnabled = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("sos_enabled", false)
        val autoSosEnabled = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("auto_sos_enabled", false)

        // If either SOS or auto-SOS is disabled, don't check
        if (!sosEnabled || !autoSosEnabled) {
            Log.d(TAG, "Auto-SOS is disabled in settings")
            return
        }

        // Check if journey is in progress
        if (!isTracking || currentTrip == null) {
            Log.d(TAG, "Auto-SOS check: No active journey")
            return
        }

        // Check if we've reached the destination
        if (currentStationIndex >= stationRoute.size) {
            Log.d(TAG, "Auto-SOS check: Journey completed")
            return
        }

        val currentTime = System.currentTimeMillis()
        val timeSinceLastStation = currentTime - lastStationDetectionTime
        val fifteenMinutes = 15 * 60 * 1000L // 15 minutes in milliseconds

        Log.d(TAG, "Auto-SOS check: ${timeSinceLastStation / 1000 / 60} minutes since last station")

        if (timeSinceLastStation > fifteenMinutes) {
            Log.w(TAG, "⚠️ AUTO-SOS TRIGGERED: No station detected for 15+ minutes")

            // Trigger SOS on main thread
            lifecycleScope.launch(Dispatchers.Main) {
                triggerAutoSos()
            }
        }
    }

    private fun triggerAutoSos() {
        val trip = currentTrip ?: return
        val emergencyContact = trip.emergencyContact

        if (emergencyContact.isEmpty()) {
            Log.e(TAG, "Auto-SOS failed: No emergency contact")
            return
        }

        Log.e(TAG, "🆘 AUTO-SOS TRIGGERED")

        // Determine current station
        val currentStationName = if (
            currentStationIndex > 0 &&
            currentStationIndex <= stationRoute.size
        ) {
            stationRoute[currentStationIndex - 1].stationName
        } else {
            "Unknown Station"
        }

        val locationString = if (lastLatitude != null && lastLongitude != null) {
            "https://maps.google.com/?q=$lastLatitude,$lastLongitude"
        } else {
            "Location unavailable"
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ===============================
                // 🔴 STEP 1: MARK SOS IN DATABASE
                // ===============================
                database.tripDao().markTripWithSos(
                    tripId = trip.id,
                    stationName = currentStationName,
                    timestamp = System.currentTimeMillis()
                )

                Log.d(TAG, "✅ Auto-SOS marked in trip history")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to mark Auto-SOS in DB", e)
            }

            // ===============================
            // 🔴 STEP 2: SMS + CALL (MAIN THREAD)
            // ===============================
            withContext(Dispatchers.Main) {

                val sosMessage = """
🆘 AUTO-SOS ALERT

Trip: ${trip.sourceStationName} → ${trip.destinationStationName}
Last Known Station: $currentStationName
Live Location:
$locationString

No station update for 15+ minutes.
Please check immediately.

- Delhi Metro Tracker
""".trimIndent()

                // Send SMS
                if (isSmsEnabled()) {
                    try {
                        smsHelper.sendSms(emergencyContact, sosMessage)
                        Log.d(TAG, "✅ Auto-SOS SMS sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Auto-SOS SMS failed", e)
                    }
                }

                // Delay slightly to ensure SMS dispatch
                delay(1500)

                // Make Emergency Call (SAME AS MANUAL SOS)
                try {
                    if (ActivityCompat.checkSelfPermission(
                            this@JourneyTrackingService,
                            Manifest.permission.CALL_PHONE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val callIntent = Intent(Intent.ACTION_CALL).apply {
                            data = android.net.Uri.parse("tel:$emergencyContact")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(callIntent)
                        Log.d(TAG, "📞 Auto-SOS call initiated")
                    } else {
                        Log.e(TAG, "❌ CALL_PHONE permission missing")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Auto-SOS call failed", e)
                }

                // ===============================
                // 🔴 STEP 3: UPDATE NOTIFICATION
                // ===============================
                updateNotification("🆘 AUTO-SOS SENT")

                // ===============================
                // 🔴 STEP 4: STRONG VIBRATION
                // ===============================
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 500, 200, 500, 200, 500),
                                -1
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(longArrayOf(0, 500, 200, 500, 200, 500), -1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Vibration failed", e)
                }

                Toast.makeText(
                    this@JourneyTrackingService,
                    "🆘 Auto-SOS triggered. Emergency contact notified.",
                    Toast.LENGTH_LONG
                ).show()

                // Stop auto-SOS monitoring to prevent spam
                stopAutoSosMonitoring()
            }
        }
    }

}