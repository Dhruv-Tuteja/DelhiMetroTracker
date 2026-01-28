package com.metro.delhimetrotracker.ui

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.telephony.SmsManager
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.service.JourneyTrackingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.view.KeyEvent
import com.metro.delhimetrotracker.data.repository.RoutePlanner
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class TrackingActivity : AppCompatActivity() {

    private lateinit var adapter: StationAdapter
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Speed tracking variables
    private var locationCallback: com.google.android.gms.location.LocationCallback? = null
    private var previousLocation: android.location.Location? = null


    // Store journey data for manual update dialog
    private var currentJourneyPath: List<MetroStation> = emptyList()
    private var visitedStationIds: List<String> = emptyList()
    private var emergencyContact: String = ""
    private var currentTripId: Long = -1L

    private var routePreference: RoutePlanner.RoutePreference = RoutePlanner.RoutePreference.SHORTEST_PATH
    private var sosTimer: CountDownTimer? = null
    private var flashAnimator: ObjectAnimator? = null

    private var powerButtonPressCount = 0
    private var lastPowerButtonPressTime = 0L
    private val POWER_BUTTON_TIMEOUT = 3000L // 3 seconds window



    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important!
        if (intent.hasExtra("EXTRA_TRIP_ID")) {
            val newId = intent.getLongExtra("EXTRA_TRIP_ID", -1L)
            if (newId != -1L) {
                currentTripId = newId
            }
        }
        if (intent.getBooleanExtra("TRIGGER_SOS", false) ||
            intent.action == "ACTION_SOS_FROM_NOTIFICATION") {
            startSosCountdown()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tracking)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Start speed tracking
        setupSpeedTracking()

        // Request SOS permissions
        checkAndRequestPermissions()

        if (intent.getBooleanExtra("TRIGGER_SOS", false) ||
            intent.action == "ACTION_SOS_FROM_NOTIFICATION") {
            startSosCountdown()
        }

        // Ensure these IDs match your XML exactly
        val rootLayout = findViewById<ConstraintLayout>(R.id.trackingRoot) ?: findViewById(android.R.id.content)
        val rvPath = findViewById<RecyclerView>(R.id.rvStationPath)
        progressIndicator = findViewById(R.id.journeyProgress)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btnManualUpdate).setOnClickListener {
            val remainingStations = currentJourneyPath.filter { it.stationId !in visitedStationIds }
            showManualUpdateDialog(remainingStations)
        }

        // SOS Button Click Listener
        findViewById<Button>(R.id.btnSOS)?.setOnClickListener {
            startSosCountdown()
        }

        rvPath.layoutManager = LinearLayoutManager(this)
        val tripId = intent.getLongExtra("EXTRA_TRIP_ID", -1L)
        currentTripId = tripId
        // Get route preference from intent
        val preferenceString = intent.getStringExtra("ROUTE_PREFERENCE")
        routePreference = try {
            RoutePlanner.RoutePreference.valueOf(preferenceString ?: "SHORTEST_PATH")
        } catch (e: Exception) {
            RoutePlanner.RoutePreference.SHORTEST_PATH
        }
        val db = (application as MetroTrackerApplication).database

        val btnStop = findViewById<Button>(R.id.btnStopJourney)
        btnStop.setOnClickListener {
            val stopIntent = Intent(this, JourneyTrackingService::class.java).apply {
                action = JourneyTrackingService.ACTION_STOP_JOURNEY
            }
            startService(stopIntent)

            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(mainIntent)
            finish()
        }

        lifecycleScope.launch {
            db.tripDao().getTripByIdFlow(tripId).collectLatest { trip ->
                trip?.let { activeTrip ->
                    // Store emergency contact
                    emergencyContact = activeTrip.emergencyContact
                    val routePlanner = RoutePlanner(db)
                    val route = routePlanner.findRoute(
                        activeTrip.sourceStationId,
                        activeTrip.destinationStationId,
                        routePreference
                    )
                    val journeyPath = route?.segments?.flatMap { it.stations } ?: emptyList()
                    currentJourneyPath = journeyPath

                    visitedStationIds = activeTrip.visitedStations

                    if (!::adapter.isInitialized) {
                        adapter = StationAdapter()
                        rvPath.adapter = adapter
                    }

                    val nextStation = currentJourneyPath.find { it.stationId == (activeTrip.visitedStations.lastOrNull()) }
                    findViewById<TextView>(R.id.tvCurrentStation).text = nextStation?.stationName ?: activeTrip.sourceStationName

                    if (currentJourneyPath.isNotEmpty()) {
                        val totalStations = currentJourneyPath.size

                        val currentStationIndex = currentJourneyPath.indexOfLast { station ->
                            activeTrip.visitedStations.contains(station.stationId)
                        }.coerceAtLeast(0)
                        val stationsLeft = totalStations - (currentStationIndex + 1)
                        val progressText = "${currentStationIndex + 1}/$totalStations stations"
                        findViewById<TextView>(R.id.tvProgressPercent).text = progressText

                        val minutesRemaining = stationsLeft * 2
                        val estTimeText = if (minutesRemaining > 0) "~$minutesRemaining mins left" else "Arriving"
                        findViewById<TextView>(R.id.tvCurrentStationLabel).text = "EST. TIME: $estTimeText"

                        val progress = when {
                            currentStationIndex == 0 -> 0f
                            currentStationIndex == totalStations - 1 -> 100f
                            else -> {
                                val totalSegments = totalStations - 1
                                (currentStationIndex.toFloat() / totalSegments.toFloat() * 100)
                            }
                        }
                        progressIndicator.setProgress(progress.toInt(), true)
                    }

                    adapter.submitData(currentJourneyPath, activeTrip.visitedStations)

                    val currentId = activeTrip.visitedStations.lastOrNull()
                    if (currentId != null && activeTrip.visitedStations.size > 1) {
                        val penultimateId = if (currentJourneyPath.size >= 2) currentJourneyPath[currentJourneyPath.size - 2].stationId else null
                        if (currentId == activeTrip.destinationStationId || currentId == penultimateId) {
                            triggerTripleVibration()
                        }
                    }
                }
            }
        }
    }
    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.SEND_SMS)
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                REQUEST_SOS_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_SOS_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "SOS permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ SOS requires SMS and Call permissions", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val REQUEST_SOS_PERMISSIONS = 1001
    }
    private fun triggerTripleVibration() {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(pattern, -1)
        }
    }

    private val smsResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (resultCode) {
                Activity.RESULT_OK -> Toast.makeText(context, "✅ SMS Delivered", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(context, "❌ SMS Failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val resetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "ACTION_JOURNEY_STOPPED") {
                val mainIntent = Intent(this@TrackingActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(mainIntent)
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filterSms = IntentFilter("com.metro.delhimetrotracker.SMS_SENT")
        val filterReset = IntentFilter("ACTION_JOURNEY_STOPPED")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsResultReceiver, filterSms, RECEIVER_EXPORTED)
            registerReceiver(resetReceiver, filterReset, RECEIVER_EXPORTED)
        } else {
            registerReceiver(smsResultReceiver, filterSms)
            registerReceiver(resetReceiver, filterReset)
        }

        // Inside TrackingActivity.kt onCreate or onStart
        val mockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Show a blocking dialog or finish the activity
                MaterialAlertDialogBuilder(this@TrackingActivity)
                    .setTitle("Security Alert")
                    .setMessage("Mock location apps are not allowed. Please disable them to track your journey.")
                    .setCancelable(false)
                    .setPositiveButton("Exit") { _, _ -> finish() }
                    .show()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mockReceiver, IntentFilter("ACTION_MOCK_LOCATION_DETECTED"), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mockReceiver, IntentFilter("ACTION_MOCK_LOCATION_DETECTED"))
        }
    }

    private var mockReceiver: BroadcastReceiver? = null

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(smsResultReceiver)
            unregisterReceiver(resetReceiver)
            mockReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun showManualUpdateDialog(remainingStations: List<MetroStation>) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_update, null)
        val spinnerStation = dialogView.findViewById<Spinner>(R.id.spinnerStation)
        val spinnerTime = dialogView.findViewById<Spinner>(R.id.spinnerTime)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDialog)
        val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdateDialog)

        spinnerStation.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            remainingStations.map { it.stationName }
        )

        val timeOptions = listOf("Just now", "1 min ago", "2 min ago", "5 min ago", "10 min ago")
        spinnerTime.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            timeOptions
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnUpdate.setOnClickListener {
            val selectedStation = remainingStations[spinnerStation.selectedItemPosition]
            val timeOffset = timeOptions[spinnerTime.selectedItemPosition]

            val intent = Intent(this, JourneyTrackingService::class.java).apply {
                action = JourneyTrackingService.ACTION_MANUAL_STATION_UPDATE
                putExtra(JourneyTrackingService.EXTRA_STATION_ID, selectedStation.stationId)
                putExtra(JourneyTrackingService.EXTRA_TIME_OFFSET, timeOffset)
            }
            startService(intent)

            dialog.dismiss()
        }

        dialog.show()
    }

    private fun startSosCountdown() {
        val overlay = findViewById<FrameLayout>(R.id.sosOverlay)
        val tvCountdown = findViewById<TextView>(R.id.tvSosCountdown)
        overlay.visibility = View.VISIBLE

        // Flashing Red Animation (faster for urgency)
        flashAnimator = ObjectAnimator.ofInt(
            overlay, "backgroundColor",
            Color.parseColor("#88FF0000"), Color.parseColor("#FFFF0000")
        ).apply {
            duration = 300
            setEvaluator(ArgbEvaluator())
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        // Get vibrator
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        // 5-Second Countdown Timer with vibration every second
        sosTimer = object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = (millisUntilFinished / 1000) + 1
                tvCountdown.text = "🆘 SENDING SOS IN $secondsLeft"

                // Strong vibration pattern every second
                val vibratePattern = longArrayOf(0, 300, 100, 300)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(vibratePattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(vibratePattern, -1)
                }
            }

            override fun onFinish() {
                // Final long vibration before sending
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(1000)
                }

                executeEmergencyActions()
                stopSosUI()
            }
        }.start()

        // Double Tap to Cancel
        var lastClickTime: Long = 0
        overlay.setOnClickListener {
            val clickTime = System.currentTimeMillis()
            if (clickTime - lastClickTime < 300) {
                cancelSos()
            }
            lastClickTime = clickTime
        }
    }

    private fun executeEmergencyActions() {
        if (currentTripId == -1L) {
            Toast.makeText(this, "Error: Invalid Trip ID", Toast.LENGTH_SHORT).show()
            return
        }

        // 1. Get UI data on the Main Thread BEFORE switching to IO
        val stationName = findViewById<TextView>(R.id.tvCurrentStation)?.text?.toString() ?: "Unknown Station"

        lifecycleScope.launch(Dispatchers.IO) {
            // ==============================================================
            // 🚨 STEP 1: FAIL-SAFE DB UPDATE (IO Thread)
            // ==============================================================
            try {
                val db = (application as MetroTrackerApplication).database
                db.tripDao().markTripWithSos(
                    tripId = currentTripId,
                    stationName = stationName,
                    timestamp = System.currentTimeMillis()
                )
                android.util.Log.d("TrackingActivity", "✅ SOS marked in DB for Trip: $currentTripId")
            } catch (e: Exception) {
                android.util.Log.e("TrackingActivity", "❌ Failed to mark SOS in DB", e)
            }

            // ==============================================================
            // 🚨 STEP 2: SMS & CALL (Switch to Main Thread)
            // ==============================================================
            withContext(Dispatchers.Main) {
                // Check Permissions
                if (ActivityCompat.checkSelfPermission(this@TrackingActivity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this@TrackingActivity, "⚠️ SOS Recorded, but SMS permission missing!", Toast.LENGTH_LONG).show()
                    return@withContext
                }

                try {
                    // Get Location (Suspend function)
                    val location = if (ActivityCompat.checkSelfPermission(this@TrackingActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        try { fusedLocationClient.lastLocation.await() } catch (e: Exception) { null }
                    } else null

                    val mapsLink = if (location != null) "https://maps.google.com/?q=${location.latitude},${location.longitude}" else "Location unavailable"

                    val message = """
                🆘 EMERGENCY ALERT!
                I need help.
                Metro Station: $stationName
                Loc: $mapsLink
                """.trimIndent()

                    // Send SMS
                    if (emergencyContact.isNotEmpty()) {
                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                        val parts = smsManager.divideMessage(message)

                        if (parts.size > 1) {
                            smsManager.sendMultipartTextMessage(emergencyContact, null, parts, null, null)
                        } else {
                            smsManager.sendTextMessage(emergencyContact, null, message, null, null)
                        }
                        Toast.makeText(this@TrackingActivity, "✅ SOS SMS Sent!", Toast.LENGTH_SHORT).show()
                    }

                    // Make Call (After 1.5s delay)
                    if (ActivityCompat.checkSelfPermission(this@TrackingActivity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED && emergencyContact.isNotEmpty()) {
                        delay(1500)
                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$emergencyContact")))
                    }

                } catch (e: Exception) {
                    android.util.Log.e("TrackingActivity", "SMS/Call Failed", e)
                    Toast.makeText(this@TrackingActivity, "SMS failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun cancelSos() {
        sosTimer?.cancel()
        flashAnimator?.cancel()
        stopSosUI()
        Toast.makeText(this, "SOS Cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun stopSosUI() {
        sosTimer?.cancel()
        flashAnimator?.cancel()
        findViewById<FrameLayout>(R.id.sosOverlay)?.visibility = View.GONE
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val currentTime = System.currentTimeMillis()

            // Reset count if timeout exceeded
            if (currentTime - lastPowerButtonPressTime > POWER_BUTTON_TIMEOUT) {
                powerButtonPressCount = 0
            }

            powerButtonPressCount++
            lastPowerButtonPressTime = currentTime

            // Show feedback
            Toast.makeText(this, "SOS trigger: $powerButtonPressCount/5", Toast.LENGTH_SHORT).show()

            if (powerButtonPressCount >= 5) {
                powerButtonPressCount = 0
                startSosCountdown()
                return true
            }

            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSosUI()
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
    }

    /**
     * Setup simple speed tracking
     */
    private fun setupSpeedTracking() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val locationRequest = com.google.android.gms.location.LocationRequest.create().apply {
            interval = 1000 // Update every second
            fastestInterval = 500
            priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : com.google.android.gms.location.LocationCallback() {
            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val speedKmh = if (location.hasSpeed()) {
                        location.speed * 3.6f // Convert m/s to km/h
                    } else {
                        calculateSpeedFromDistance(location)
                    }
                    updateSpeedDisplay(speedKmh)
                }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    /**
     * Calculate speed from distance between points
     */
    private fun calculateSpeedFromDistance(currentLocation: android.location.Location): Float {
        previousLocation?.let { previous ->
            val distance = previous.distanceTo(currentLocation)
            val timeDiff = (currentLocation.time - previous.time) / 1000f
            previousLocation = currentLocation

            return if (timeDiff > 0) (distance / timeDiff) * 3.6f else 0f
        }
        previousLocation = currentLocation
        return 0f
    }

    /**
     * Update speed TextView
     */
    private fun updateSpeedDisplay(speedKmh: Float) {
        findViewById<TextView>(R.id.tvSpeedValue)?.text = String.format("%.0f", speedKmh.coerceAtLeast(0f))
    }
}