//package com.metro.delhimetrotracker.service
//
//import android.app.*
//import android.content.Context
//import android.content.Intent
//import android.content.pm.PackageManager
//import android.content.pm.ServiceInfo
//import android.location.Location
//import android.os.*
//import android.util.Log
//import androidx.core.app.ActivityCompat
//import androidx.core.app.NotificationCompat
//import androidx.lifecycle.LifecycleService
//import androidx.lifecycle.lifecycleScope
//import com.google.android.gms.location.*
//import com.metro.delhimetrotracker.MetroTrackerApplication
//import com.metro.delhimetrotracker.R
//import com.metro.delhimetrotracker.data.local.database.AppDatabase
//import com.metro.delhimetrotracker.data.local.database.entities.*
//import com.metro.delhimetrotracker.utils.sensors.StationDetector
//import com.metro.delhimetrotracker.utils.sms.SmsHelper
//import kotlinx.coroutines.*
//import kotlinx.coroutines.tasks.await
//import java.util.*
//import com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod.MANUAL
//import android.content.BroadcastReceiver
//import android.content.IntentFilter
//import com.metro.delhimetrotracker.ui.TrackingActivity
//import android.widget.Toast
//import android.Manifest
//import com.metro.delhimetrotracker.data.repository.RoutePlanner
//import com.metro.delhimetrotracker.ui.MainActivity
//import androidx.work.*
//import com.metro.delhimetrotracker.worker.SyncWorker
//import java.util.concurrent.TimeUnit
//import org.json.JSONArray
//import com.metro.delhimetrotracker.data.repository.RouteRecoveryManager
//import com.metro.delhimetrotracker.data.repository.StationDetectionEngine
//import com.metro.delhimetrotracker.data.repository.LocationProcessingResult
//import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
//import com.metro.delhimetrotracker.data.local.database.entities.RouteDivergence
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.launch
//import com.metro.delhimetrotracker.data.repository.RoutePlannerAdapter
//import com.metro.delhimetrotracker.data.repository.LocationProviderImpl
//import com.metro.delhimetrotracker.data.repository.JourneyStateManager
//import com.google.gson.Gson
//import java.util.Date
///**
// * Production Foreground service for Delhi Metro Tracking.
// * Uses LifecycleService to provide easy access to lifecycleScope for DB operations.
// */
//class JourneyTrackingService : LifecycleService() {
//
//
//    data class JourneyUpdate(
//        val newStationDetected: Boolean,
//        val detectedStation: MetroStation? = null,
//        val routeChanged: Boolean = false
//    )
//
//    data class RecoveryUpdate(
//        val routeChanged: Boolean,
//        val newPath: List<MetroStation>? = null,
//        val inferredStations: List<MetroStation> = emptyList(),
//        val divergence: RouteDivergence? = null,
//        val reason: String = ""
//    )
//
//    private lateinit var database: AppDatabase
//    private lateinit var stationDetector: StationDetector
//    private lateinit var smsHelper: SmsHelper
//    private lateinit var fusedLocationClient: FusedLocationProviderClient
//    private lateinit var vibrator: Vibrator
//
//    private var currentTrip: Trip? = null
//    private var currentStationIndex = 0
//    private var stationRoute: List<MetroStation> = emptyList()
//
//    private var isTracking = false
//    private var lastDetectedStationId: String? = null
//
//    private var pressCount = 0
//    private var lastPressTime = 0L
//
//    private var routePreference: RoutePlanner.RoutePreference = RoutePlanner.RoutePreference.SHORTEST_PATH
//
//    private lateinit var locationProvider: LocationProviderImpl
//
//    // Use adapter to wrap your existing RoutePlanner
//    private lateinit var routePlannerAdapter: RoutePlannerAdapter
//
//    private lateinit var detectionEngine: StationDetectionEngine
//    private lateinit var recoveryManager: RouteRecoveryManager
//    private lateinit var stateManager: JourneyStateManager
//
//    companion object {
//        private const val TAG = "JourneyTrackingService"
//        private const val NOTIFICATION_ID = 1001
//        private const val CHANNEL_ID = "metro_journey_tracking"
//
//        const val ACTION_START_JOURNEY = "START_JOURNEY"
//        const val ACTION_STOP_JOURNEY = "STOP_JOURNEY"
//        const val ACTION_MANUAL_STATION_UPDATE = "ACTION_MANUAL_STATION_UPDATE"
//        const val EXTRA_TRIP_ID = "trip_id"
//        const val EXTRA_STATION_ID = "EXTRA_STATION_ID"
//        const val EXTRA_TIME_OFFSET = "EXTRA_TIME_OFFSET"
//
//        private const val LOCATION_UPDATE_INTERVAL = 10000L
//        private const val LOCATION_FASTEST_INTERVAL = 5000L
//
//        private lateinit var stationDetectionEngine: StationDetectionEngine
//        private lateinit var routeRecoveryManager: RouteRecoveryManager
//        private lateinit var journeyStateManager: JourneyStateManager
//
//        private var lastGPSTimestamp: Long = 0
//        private var gpsOfflineDuration: Long = 0
//        private val GPS_GAP_THRESHOLD = 60_000L
//
//        const val ACTION_STATION_DETECTED = "com.metro.ACTION_STATION_DETECTED"
//        const val ACTION_ROUTE_DIVERGENCE = "com.metro.ACTION_ROUTE_DIVERGENCE"
//        const val ACTION_GPS_RECOVERY = "com.metro.ACTION_GPS_RECOVERY"
//    }
//
//    override fun onCreate() {
//        super.onCreate()
//        database = (application as MetroTrackerApplication).database
//        stationDetector = StationDetector(this)
//        smsHelper = SmsHelper(applicationContext)
//        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
//
//        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
//            manager.defaultVibrator
//        } else {
//            @Suppress("DEPRECATION")
//            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
//        }
//        createNotificationChannel()
//
//        val filter = IntentFilter().apply {
//            addAction(Intent.ACTION_SCREEN_ON)
//            addAction(Intent.ACTION_SCREEN_OFF)
//        }
//        registerReceiver(powerButtonReceiver, filter)
//
//        locationProvider = LocationProviderImpl(applicationContext)
//
//        // Create adapter for your existing RoutePlanner
//        routePlannerAdapter = RoutePlannerAdapter(database)
//
//        // Initialize detection engine
//        detectionEngine = StationDetectionEngine(database, locationProvider)
//
//        // Initialize recovery manager (uses the adapter)
//        recoveryManager = RouteRecoveryManager(database, routePlannerAdapter)
//
//        // Initialize state manager
//        stateManager = JourneyStateManager(
//            database = database,
//            detectionEngine = detectionEngine,
//            recoveryManager = recoveryManager,
//            locationProvider = locationProvider
//        )
//
//        Log.d(TAG, "✅ All components initialized with existing RoutePlanner")
//    }
//
//    private suspend fun checkStationProximity(location: Location) {
//        val now = System.currentTimeMillis()
//
//        // GPS gap detection
//        if (now - lastGPSTimestamp > GPS_GAP_THRESHOLD) {
//            gpsOfflineDuration = now - lastGPSTimestamp
//            handleGPSRecovery(location)
//            return
//        }
//
//        lastGPSTimestamp = now
//
//        // Get current trip data
//        val trip = currentTrip ?: return
//
//        // Normal multi-station detection using StationDetectionEngine
//        val result = stateManager.processLocationUpdate(
//            location = location,
//            currentTrip = trip,
//            journeyPath = stationRoute
//        )
//
//        when (result) {
//            is LocationProcessingResult.NewStationDetected -> {
//                handleNewStationDetected(result)
//            }
//            is LocationProcessingResult.GpsRecoveredOnPath -> {
//                handleGPSRecovered(result)
//            }
//            is LocationProcessingResult.GpsRecoveredWithDivergence -> {
//                handleRouteDivergence(result)
//            }
//            else -> {
//                // NoDetection or SameStation - do nothing
//            }
//        }
//    }
//
//    private suspend fun handleNewStationDetected(result: LocationProcessingResult.NewStationDetected) {
//        val station = result.station
//
//        // Update current trip
//        currentTrip?.let { trip ->
//            currentTrip = trip.copy(visitedStations = trip.visitedStations + station.stationId)
//            currentStationIndex = stationRoute.indexOfFirst { it.stationId == station.stationId } + 1
//        }
//
//        // Vibrate
//        vibrateOnStationDetection()
//
//        // Broadcast to activity
//        val intent = Intent(ACTION_STATION_DETECTED).apply {
//            putExtra("STATION_ID", station.stationId)
//            putExtra("STATION_NAME", station.stationName)
//            putExtra("STATION", station)
//        }
//        sendBroadcast(intent)
//
//        // Update notification
//        updateNotification("Reached: ${station.stationName}")
//    }
//
//    private suspend fun handleGPSRecovered(result: LocationProcessingResult.GpsRecoveredOnPath) {
//        val intent = Intent(ACTION_GPS_RECOVERY).apply {
//            putExtra("INFERRED_STATIONS", ArrayList(result.inferredStations.map {
//                stationRoute.find { station -> station.stationId == it }
//            }.filterNotNull()))
//            putExtra("GAP_DURATION", result.gapDuration * 1000) // Convert to millis
//        }
//        sendBroadcast(intent)
//    }
//
//    private suspend fun handleRouteDivergence(result: LocationProcessingResult.GpsRecoveredWithDivergence) {
//        // Update local route
//        stationRoute = result.newPath
//
//        val intent = Intent(ACTION_ROUTE_DIVERGENCE).apply {
//            putExtra("NEW_PATH", ArrayList(result.newPath))
//            putExtra("DIVERGENCE", result.divergenceInfo)
//            putExtra("REASON", result.divergenceInfo.reason)
//        }
//        sendBroadcast(intent)
//    }
//
//    private suspend fun handleGPSRecovery(location: Location) {
//        val trip = currentTrip ?: return
//        val result = stateManager.processLocationUpdate(
//            location = location,
//            currentTrip = trip,
//            journeyPath = stationRoute
//        )
//
//        when (result) {
//            is LocationProcessingResult.GpsRecoveredOnPath -> handleGPSRecovered(result)
//            is LocationProcessingResult.GpsRecoveredWithDivergence -> handleRouteDivergence(result)
//            else -> {}
//        }
//
//        // Reset GPS timestamp
//        lastGPSTimestamp = System.currentTimeMillis()
//    }
//
//    private suspend fun handleStationDetection(update: JourneyUpdate) {
//        val detectedStation = update.detectedStation ?: return
//
//        // Update current trip
//        currentTrip?.let { trip ->
//            val updatedVisited = trip.visitedStations + detectedStation.stationId
//            val visitedJson = org.json.JSONArray(updatedVisited).toString()
//            // Update database
//            database.tripDao().updateVisitedStations(
//                tripId = trip.id,
//                visitedListJson = visitedJson
//            )
//
//            // Update local reference
//            currentTrip = trip.copy(visitedStations = updatedVisited)
//
//            // Update station index
//            currentStationIndex = stationRoute.indexOfFirst {
//                it.stationId == detectedStation.stationId
//            } + 1
//        }
//
//        // Trigger haptic feedback
//        vibrateOnStationDetection()
//
//        // Broadcast to TrackingActivity
//        broadcastStationDetection(detectedStation)
//
//        // Update notification
//        updateNotification("Reached: ${detectedStation.stationName}")
//    }
//
//    private fun vibrateOnStationDetection() {
//        try {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
//            } else {
//                @Suppress("DEPRECATION")
//                vibrator.vibrate(200)
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Vibration failed", e)
//        }
//    }
//
//    private fun broadcastStationDetection(station: MetroStation) {
//        val intent = Intent(ACTION_STATION_DETECTED).apply {
//            putExtra("STATION_ID", station.stationId)
//            putExtra("STATION_NAME", station.stationName)
//            putExtra("STATION", station)
//        }
//        sendBroadcast(intent)
//    }
//
//    private fun broadcastRouteChange(update: RecoveryUpdate) {
//        val intent = Intent(ACTION_ROUTE_DIVERGENCE).apply {
//            putExtra("NEW_PATH", ArrayList(update.newPath ?: emptyList()))
//            update.divergence?.let { putExtra("DIVERGENCE", it) }
//            putExtra("REASON", update.reason)
//        }
//        sendBroadcast(intent)
//    }
//
//    private fun broadcastGapRecovery(update: RecoveryUpdate) {
//        val intent = Intent(ACTION_GPS_RECOVERY).apply {
//            putExtra("INFERRED_STATIONS", ArrayList(update.inferredStations))
//            putExtra("GAP_DURATION", gpsOfflineDuration)
//        }
//        sendBroadcast(intent)
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        try {
//            unregisterReceiver(powerButtonReceiver)
//        } catch (e: Exception) {
//            // Receiver not registered
//        }
//    }
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        super.onStartCommand(intent, flags, startId)
//
//        when (intent?.action) {
//            ACTION_START_JOURNEY -> {
//                val tripId = intent.getLongExtra(EXTRA_TRIP_ID, -1L)
//                // Get route preference from intent
//                val preferenceString = intent.getStringExtra("ROUTE_PREFERENCE")
//                routePreference = try {
//                    RoutePlanner.RoutePreference.valueOf(preferenceString ?: "SHORTEST_PATH")
//                } catch (e: Exception) {
//                    RoutePlanner.RoutePreference.SHORTEST_PATH
//                }
//
//                if (tripId != -1L) startJourneyTracking(tripId)
//            }
//            ACTION_STOP_JOURNEY -> stopJourneyTracking()
//            ACTION_MANUAL_STATION_UPDATE -> {
//                val stationId = intent.getStringExtra(EXTRA_STATION_ID)
//                val timeOffset = intent.getStringExtra(EXTRA_TIME_OFFSET)
//                if (stationId != null) handleManualJump(stationId, timeOffset ?: "Just now")
//            }
//        }
//
//        // Only start foreground for actual journey tracking, not for stop commands
//        if (intent?.action == ACTION_START_JOURNEY) {
//            val notification = createNotification()
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
//            } else {
//                startForeground(NOTIFICATION_ID, notification)
//            }
//        }
//
//        return START_STICKY
//    }
//
//    private fun startJourneyTracking(tripId: Long) {
//        lifecycleScope.launch(Dispatchers.IO) {
//            try {
//                // Load trip FIRST
//                currentTrip = database.tripDao().getTripById(tripId) ?: return@launch
//
//                // Now you can safely use currentTrip
//                val initialVisited = listOf(currentTrip!!.sourceStationId)
//                val initialJson = org.json.JSONArray(initialVisited).toString()
//                database.tripDao().updateVisitedStations(tripId, initialJson)
//                currentTrip = currentTrip!!.copy(visitedStations = initialVisited)
//
//                // Load path using selected preference
//                val routePlanner = RoutePlanner(database)
//                val route = routePlanner.findRoute(
//                    currentTrip!!.sourceStationId,
//                    currentTrip!!.destinationStationId,
//                    routePreference
//                )
//                stationRoute = route?.segments?.flatMap { it.stations }?.distinctBy { it.stationId }
//                    ?: emptyList()
//                currentStationIndex = 0
//
//                // Where you populate stationRoute, add:
//                Log.d(TAG, "Route calculated from: ${currentTrip!!.sourceStationId} to ${currentTrip!!.destinationStationId}")
//                Log.d(TAG, "Source station name: ${stationRoute.firstOrNull()?.stationName}")
//                Log.d(TAG, "Destination station name: ${stationRoute.lastOrNull()?.stationName}")
//
//                isTracking = true
//
//                withContext(Dispatchers.Main) {
//                    val notification = createNotification()
//                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
//                    } else {
//                        startForeground(NOTIFICATION_ID, notification)
//                    }
//                    startLocationUpdates()
//                }
//
//                // Safety: Delay accelerometer to prevent false triggers during boarding
//                launch {
//                    delay(15000)
//                    startAccelerometerMonitoring()
//                }
//
//                // Initial Alert - FIXED: Changed parameter names to match SmsHelper
//                smsHelper.sendJourneyStartAlert(
//                    phoneNumber = currentTrip!!.emergencyContact,
//                    source = currentTrip!!.sourceStationName,
//                    dest = currentTrip!!.destinationStationName,
//                    estDuration = stationRoute.size * 2
//                )
//            } catch (e: Exception) {
//                Log.e(TAG, "Start error", e)
//            }
//        }
//    }
//
//    private fun handleManualJump(targetStationId: String, timeOffset: String) {
//
//        Log.d(TAG, "Manual Jump Requested: $targetStationId, CurrentTrip is null? ${currentTrip == null}")
//
//        Log.d(TAG, "stationRoute size: ${stationRoute.size}")
//        Log.d(TAG, "stationRoute station IDs: ${stationRoute.map { it.stationId }}")
//        Log.d(TAG, "Looking for targetStationId: $targetStationId")
//
//        val trip = currentTrip ?: return
//        val targetIndex = stationRoute.indexOfFirst { it.stationId == targetStationId }
//        if (targetIndex == -1) {
//            Log.e(TAG, "Station ID not found in current route!")
//            return
//        }
//
//        val updatedVisitedList = stationRoute.subList(0, targetIndex + 1).map { it.stationId }
//
//        lifecycleScope.launch(Dispatchers.IO) {
//            try {
//                val jsonString = JSONArray(updatedVisitedList).toString()
//                database.tripDao().updateVisitedStations(trip.id, jsonString)
//                database.tripDao().updateSyncStatus(trip.id, "PENDING", System.currentTimeMillis())
//
//                // 🔥 FIX: Update currentTrip in memory!
//                currentTrip = currentTrip!!.copy(visitedStations = updatedVisitedList)
//
//                if (targetIndex >= currentStationIndex) {
//                    val jumpedStations = stationRoute.subList(currentStationIndex, targetIndex + 1)
//
//                    jumpedStations.forEachIndexed { index, station ->
//                        val checkpoint = StationCheckpoint(
//                            tripId = trip.id,
//                            stationId = station.stationId,
//                            stationName = station.stationName,
//                            // Calculate the correct sequence number (0-based or 1-based depending on your preference)
//                            stationOrder = currentStationIndex + index,
//                            arrivalTime = Date(), // Use current time for manual jumps
//                            detectionMethod = MANUAL,
//                            confidence = 1.0f,
//                            latitude = null,
//                            longitude = null
//                        )
//                        database.stationCheckpointDao().insertCheckpoint(checkpoint)
//                        Log.d(TAG, "Created manual checkpoint for: ${station.stationName}")
//                    }
//                }
//
//                val targetStation = stationRoute[targetIndex]
//                val smsMessage = "Manual Update: Reached ${targetStation.stationName} ($timeOffset). Tracking resumed."
//
//                if (trip.emergencyContact.isNotEmpty()) {
//                    smsHelper.sendSms(trip.emergencyContact, smsMessage)
//                }
//
//                currentStationIndex = targetIndex + 1
//                lastDetectedStationId = targetStationId
//
//                withContext(Dispatchers.Main) {
//                    updateNotification("Jumped to: ${targetStation.stationName}")
//                }
//
//                if(currentStationIndex >= stationRoute.size) {
//                    stopJourneyTracking()
//                }
//                Log.d(TAG, "Manual Jump Successful: Updated DB with ${updatedVisitedList.size} stations")
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to update manual jump in DB", e)
//            }
//        }
//    }
//
//    private fun handleStationDetection(method: DetectionMethod, confidence: Float, location: Location? = null) {
//        if (!isTracking || stationRoute.isEmpty()) return
//
//        lifecycleScope.launch(Dispatchers.IO) {
//            val nextStation = stationRoute.getOrNull(currentStationIndex) ?: return@launch
//            if (lastDetectedStationId == nextStation.stationId) return@launch
//
//            try {
//                val currentLocation = location ?: fusedLocationClient.lastLocation.await() ?: return@launch
//
//                val distance = FloatArray(1)
//                Location.distanceBetween(
//                    currentLocation.latitude, currentLocation.longitude,
//                    nextStation.latitude, nextStation.longitude,
//                    distance
//                )
//
//                if (distance[0] > 200f) return@launch
//
//                // 🔍 DEBUG LOG
//                Log.d(TAG, "STATION DETECTED: ${nextStation.stationName} (ID: ${nextStation.stationId}) at index $currentStationIndex")
//
//                // Send SMS Alert
//                smsHelper.sendStationAlert(
//                    currentTrip!!.emergencyContact,
//                    nextStation.stationName,
//                    currentStationIndex + 1,
//                    stationRoute.size,
//                    stationRoute.getOrNull(currentStationIndex + 1)?.stationName,
//                    currentStationIndex == stationRoute.size - 1
//                )
//
//                if (currentStationIndex == stationRoute.size - 2) triggerVibration()
//
//                lastDetectedStationId = nextStation.stationId
//                currentStationIndex++
//
//                val updatedVisited = currentTrip!!.visitedStations.toMutableList().apply {
//                    add(nextStation.stationId)
//                }
//
//                // 🔍 DEBUG LOG
//                Log.d(TAG, "Updated visitedStations: $updatedVisited")
//
//                val updatedJson = org.json.JSONArray(updatedVisited).toString()
//                database.tripDao().updateVisitedStations(currentTrip!!.id, updatedJson)
//                currentTrip = currentTrip!!.copy(visitedStations = updatedVisited)
//
//                val checkpoint = StationCheckpoint(
//                    tripId = currentTrip!!.id,
//                    stationId = nextStation.stationId,
//                    stationName = nextStation.stationName,
//                    stationOrder = currentStationIndex, // 1-based index or 0-based as you prefer
//                    arrivalTime = Date(), // <--- THIS CAPTURES THE TIMESTAMP
//                    detectionMethod = method,
//                    confidence = confidence,
//                    latitude = location?.latitude,
//                    longitude = location?.longitude
//                )
//
//                database.stationCheckpointDao().insertCheckpoint(checkpoint)
//
//                Log.d(TAG, "✅ Checkpoint saved: ${nextStation.stationName} at ${Date()}")
//
//                withContext(Dispatchers.Main) {
//                    updateNotification("Current: ${nextStation.stationName}")
//                }
//
//                if (nextStation.stationId == currentTrip!!.destinationStationId) {
//                    delay(2000)
//                    stopJourneyTracking()
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Detection error", e)
//            }
//        }
//    }
//
//    private fun startLocationUpdates() {
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                android.Manifest.permission.ACCESS_FINE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED &&
//            ActivityCompat.checkSelfPermission(
//                this,
//                android.Manifest.permission.ACCESS_COARSE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            Log.e(TAG, "Location permission not granted")
//            return
//        }
//
//        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL)
//            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL)
//            .build()
//        try {
//            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
//        } catch (e: SecurityException) {
//            Log.e(TAG, "Permission error", e)
//        }
//    }
//
//    private val locationCallback = object : LocationCallback() {
//        override fun onLocationResult(result: LocationResult) {
//            result.lastLocation?.let { location ->
//                // Check for mock location
//                if (isLocationMocked(location)) {
//                    handleMockLocationDetected()
//                    return
//                }
//
//                // Continue with normal station detection logic
//                lifecycleScope.launch(Dispatchers.IO) {
//                    checkStationProximity(location)
//                }
//            }
//        }
//    }
//
//    private fun startAccelerometerMonitoring() {
//        lifecycleScope.launch {
//            stationDetector.monitorAccelerometer().collect { event ->
//                if (event.isAtStation && event.confidence > 0.7f) {
//                    handleStationDetection(DetectionMethod.ACCELEROMETER, event.confidence)
//                }
//            }
//        }
//    }
//
//    private fun stopJourneyTracking() {
//        isTracking = false
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//            fusedLocationClient.removeLocationUpdates(locationCallback)
//        }
//
//        lifecycleScope.launch(Dispatchers.IO) {
//            currentTrip?.let { trip ->
//                val endTime = System.currentTimeMillis()
//                val duration = ((endTime - trip.startTime.time) / 60000).toInt()
//
//                // 🔍 DEBUG LOGS START
//                Log.d(TAG, "========== STOP JOURNEY DEBUG ==========")
//                Log.d(TAG, "Trip ID: ${trip.id}")
//                Log.d(TAG, "Source Station: ${trip.sourceStationName} (ID: ${trip.sourceStationId})")
//                Log.d(TAG, "Destination Station: ${trip.destinationStationName} (ID: ${trip.destinationStationId})")
//                Log.d(TAG, "Visited Stations List: ${trip.visitedStations}")
//                Log.d(TAG, "Visited Stations Count: ${trip.visitedStations.size}")
//                Log.d(TAG, "StationRoute size: ${stationRoute.size}")
//                Log.d(TAG, "StationRoute stations: ${stationRoute.map { "${it.stationName}(${it.stationId})" }}")
//
//                val lastVisitedStationId = trip.visitedStations.lastOrNull() ?: trip.sourceStationId
//                Log.d(TAG, "Last Visited Station ID: $lastVisitedStationId")
//
//                // Try database lookup
//                val actualEndStationObj = database.metroStationDao().getStationById(lastVisitedStationId)
//                Log.d(TAG, "DB Lookup Result: ${actualEndStationObj?.stationName} (ID: ${actualEndStationObj?.stationId})")
//
//                // Try stationRoute lookup
//                val fromRoute = stationRoute.find { it.stationId == lastVisitedStationId }
//                Log.d(TAG, "StationRoute Lookup Result: ${fromRoute?.stationName} (ID: ${fromRoute?.stationId})")
//
//                val actualEndStation = actualEndStationObj?.stationName ?: trip.sourceStationName
//                Log.d(TAG, "Final actualEndStation: $actualEndStation")
//                Log.d(TAG, "========================================")
//                // 🔍 DEBUG LOGS END
//
//                database.tripDao().completeTrip(
//                    trip.id,
//                    TripStatus.COMPLETED,
//                    endTime,
//                    duration,
//                    finalDestination = actualEndStation
//                )
//                scheduleSyncAfterTripEnd()
//
//                val visitedCount = trip.visitedStations.size - 1
//                val totalCount = stationRoute.size
//
//                val message = when {
//                    lastVisitedStationId == trip.destinationStationId ->
//                        "✅ Journey completed. Reached destination: $actualEndStation."
//
//                    visitedCount == 1 ->
//                        "Journey ended at starting point: $actualEndStation."
//
//                    else ->
//                        "Journey ended at $actualEndStation ($visitedCount/$totalCount stations covered)."
//                }
//
//                smsHelper.sendSms(trip.emergencyContact, message)
//
//                withContext(Dispatchers.Main) {
//                    val intent = Intent("ACTION_JOURNEY_STOPPED")
//                    sendBroadcast(intent)
//                    stopForeground(STOP_FOREGROUND_REMOVE)
//                    stopSelf()
//                }
//            }
//        }
//    }
//
//    private fun triggerVibration() {
//        val pattern = longArrayOf(0,1000,200,1000,200,1000,200,1000,200,1000,200)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
//        } else {
//            @Suppress("DEPRECATION")
//            vibrator.vibrate(pattern, -1)
//        }
//    }
//
//    private fun createNotification(): Notification {
//        // Create intent to open app in its current state
//        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
//        }
//        val openAppPendingIntent = PendingIntent.getActivity(
//            this,
//            0,
//            openAppIntent,
//            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
//        )
//
//        val stopIntent = Intent(this, JourneyTrackingService::class.java).apply {
//            action = ACTION_STOP_JOURNEY
//        }
//        val stopPendingIntent = PendingIntent.getService(
//            this,
//            0,
//            stopIntent,
//            PendingIntent.FLAG_IMMUTABLE
//        )
//
//        val totalStations = if (stationRoute.isNotEmpty()) stationRoute.size else 0
//        val stationsDone = currentStationIndex.coerceIn(0, totalStations)
//
//        val progress = if (totalStations > 1) stationsDone else 0
//        val upcomingStation = stationRoute.getOrNull(currentStationIndex)?.stationName
//            ?: currentTrip?.destinationStationName
//            ?: "Destination"
//
//        val sosIntent = Intent(this, TrackingActivity::class.java).apply {
//            action = "ACTION_SOS_FROM_NOTIFICATION"
//            putExtra("EXTRA_TRIP_ID", currentTrip?.id ?: -1L)
//            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
//        }
//        val sosPendingIntent = PendingIntent.getActivity(
//            this,
//            1,
//            sosIntent,
//            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
//        )
//
//        return NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle("Next Stop: $upcomingStation")
//            .setContentText("$stationsDone/$totalStations stations covered")
//            .setSmallIcon(R.drawable.ic_notification)
//            .setOngoing(true)
//            .setOnlyAlertOnce(true)
//            .setProgress(totalStations, stationsDone, false)
//            .setContentIntent(openAppPendingIntent)
//            .addAction(R.drawable.ic_notification, "🆘 SOS", sosPendingIntent)
//            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Journey", stopPendingIntent)
//            .setPriority(NotificationCompat.PRIORITY_LOW)
//            .build()
//    }
//
//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val channel = NotificationChannel(
//                CHANNEL_ID,
//                "Metro Tracking",
//                NotificationManager.IMPORTANCE_LOW
//            )
//            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
//        }
//    }
//
//    private fun updateNotification(status: String) {
//        val notification = createNotification() // Re-creates with latest currentTrip info
//        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
//    }
//
//    // FIXED: Removed nullable parameter
//    override fun onBind(intent: Intent): IBinder? {
//        super.onBind(intent)
//        return null
//    }
//    private val powerButtonReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SCREEN_ON) {
//                val now = System.currentTimeMillis()
//
//                if (now - lastPressTime < 1000) { // Within 1 second
//                    pressCount++
//                } else {
//                    pressCount = 1
//                }
//
//                lastPressTime = now
//
//                if (pressCount >= 5) {
//                    val activeTripId = currentTrip?.id ?: -1L
//                    // Launch TrackingActivity with SOS flag
//                    val sosIntent = Intent(context, TrackingActivity::class.java).apply {
//                        putExtra("TRIGGER_SOS", true)
//                        putExtra("EXTRA_TRIP_ID", activeTripId)
//                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
//                    }
//                    context?.startActivity(sosIntent)
//                    pressCount = 0
//                }
//            }
//        }
//    }
//
//    private fun isLocationMocked(location: Location): Boolean {
//        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//            // Android 12 (API 31) and above
//            location.isMock
//        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
//            // API 18-30
//            @Suppress("DEPRECATION")
//            location.isFromMockProvider
//        } else {
//            false
//        }
//    }
//
//    private fun handleMockLocationDetected() {
//        lifecycleScope.launch(Dispatchers.IO) {
//            val trip = currentTrip
//
//            if (trip != null && trip.emergencyContact.isNotEmpty()) {
//                try {
//                    // Get last known location
//                    val lastLocation = if (ActivityCompat.checkSelfPermission(
//                            this@JourneyTrackingService,
//                            Manifest.permission.ACCESS_FINE_LOCATION
//                        ) == PackageManager.PERMISSION_GRANTED
//                    ) {
//                        fusedLocationClient.lastLocation.await()
//                    } else {
//                        null
//                    }
//
//                    // Get last visited station
//                    val lastVisitedStationId = trip.visitedStations.lastOrNull() ?: trip.sourceStationId
//                    val lastVisitedStation = database.metroStationDao().getStationById(lastVisitedStationId)
//                    val lastStationName = lastVisitedStation?.stationName ?: trip.sourceStationName
//
//                    // Build location string
//                    val locationString = if (lastLocation != null) {
//                        "https://maps.google.com/?q=${lastLocation.latitude},${lastLocation.longitude}"
//                    } else {
//                        "Location unavailable"
//                    }
//
//                    // Send emergency alert
//                    val alertMessage = """
//⚠️ SECURITY ALERT - FAKE GPS DETECTED!
//
//Mock location detected on traveler's device.
//Last Known Station: $lastStationName
//Last Known Location: $locationString
//
//Trip tracking has been terminated. Please contact the traveler immediately.
//
//- Delhi Metro Tracker Security
//                    """.trimIndent()
//
//                    smsHelper.sendSms(trip.emergencyContact, alertMessage)
//                    Log.d(TAG, "Mock location alert sent to ${trip.emergencyContact}")
//
//                    // Mark trip as cancelled in database
//                    database.tripDao().completeTripWithReason(
//                        trip.id,
//                        TripStatus.CANCELLED,
//                        System.currentTimeMillis(),
//                        ((System.currentTimeMillis() - trip.startTime.time) / 60000).toInt(),
//                        finalDestination = lastStationName,
//                        reason = "FAKE_GPS_DETECTED"
//                    )
//
//                } catch (e: Exception) {
//                    Log.e(TAG, "Failed to send mock location alert", e)
//                }
//            }
//
//            // Stop tracking and notify user
//            withContext(Dispatchers.Main) {
//                Toast.makeText(
//                    this@JourneyTrackingService,
//                    "⚠️ Mock location detected! Trip tracking disabled.",
//                    Toast.LENGTH_LONG
//                ).show()
//
//                val intent = Intent("ACTION_MOCK_LOCATION_DETECTED")
//                sendBroadcast(intent)
//
//                // Redirect to MainActivity with alert flag
//                val mainIntent = Intent(this@JourneyTrackingService, MainActivity::class.java).apply {
//                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
//                    putExtra("MOCK_LOCATION_DETECTED", true)
//                }
//                startActivity(mainIntent)
//
//                stopForeground(STOP_FOREGROUND_REMOVE)
//                stopSelf()
//            }
//        }
//    }
//    private fun scheduleSyncAfterTripEnd() {
//        val constraints = Constraints.Builder()
//            .setRequiredNetworkType(NetworkType.CONNECTED)
//            .build()
//
//        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
//            .setConstraints(constraints)
//            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
//            .build()
//
//        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
//            "TripSync",
//            ExistingWorkPolicy.KEEP,
//            syncRequest
//        )
//    }
//}