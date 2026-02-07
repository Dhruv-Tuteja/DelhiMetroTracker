//package com.metro.delhimetrotracker.ui
//
//import android.Manifest
//import android.animation.ArgbEvaluator
//import android.animation.ObjectAnimator
//import android.animation.ValueAnimator
//import android.app.Activity
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.content.pm.PackageManager
//import android.graphics.Color
//import android.net.Uri
//import android.os.*
//import android.telephony.SmsManager
//import android.view.View
//import android.widget.ArrayAdapter
//import android.widget.Button
//import android.widget.FrameLayout
//import android.widget.Spinner
//import android.widget.TextView
//import android.widget.Toast
//import androidx.activity.enableEdgeToEdge
//import androidx.appcompat.app.AppCompatActivity
//import androidx.constraintlayout.widget.ConstraintLayout
//import androidx.core.app.ActivityCompat
//import androidx.core.view.ViewCompat
//import androidx.core.view.WindowInsetsCompat
//import androidx.lifecycle.lifecycleScope
//import androidx.recyclerview.widget.LinearLayoutManager
//import androidx.recyclerview.widget.RecyclerView
//import com.google.android.gms.location.FusedLocationProviderClient
//import com.google.android.gms.location.LocationServices
//import com.google.android.material.dialog.MaterialAlertDialogBuilder
//import com.google.android.material.progressindicator.LinearProgressIndicator
//import com.metro.delhimetrotracker.MetroTrackerApplication
//import com.metro.delhimetrotracker.R
//import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
//import com.metro.delhimetrotracker.service.JourneyTrackingService
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.tasks.await
//import android.view.KeyEvent
//import com.metro.delhimetrotracker.data.repository.RoutePlanner
//import kotlinx.coroutines.*
//import com.metro.delhimetrotracker.data.repository.MetroRepository
//import kotlinx.coroutines.Job
//import java.text.SimpleDateFormat
//import java.util.Locale
//import android.util.Log
//import java.util.Calendar
//import com.metro.delhimetrotracker.data.repository.GtfsLoader
//import android.os.Parcelable
//import androidx.localbroadcastmanager.content.LocalBroadcastManager
//import com.metro.delhimetrotracker.data.local.database.entities.RouteDivergence
//import com.google.android.material.snackbar.Snackbar
//import androidx.core.content.ContextCompat
//
//class TrackingActivity : AppCompatActivity() {
//
//    private lateinit var adapter: StationAdapter
//    private lateinit var progressIndicator: LinearProgressIndicator
//    private lateinit var fusedLocationClient: FusedLocationProviderClient
//
//    // Speed tracking variables
//    private var locationCallback: com.google.android.gms.location.LocationCallback? = null
//    private var previousLocation: android.location.Location? = null
//
//    // Store journey data for manual update dialog
//    private var currentJourneyPath: List<MetroStation> = emptyList()
//    private var visitedStationIds: List<String> = emptyList()
//    private var emergencyContact: String = ""
//    private var currentTripId: Long = -1L
//
//    private var routePreference: RoutePlanner.RoutePreference = RoutePlanner.RoutePreference.SHORTEST_PATH
//    private var sosTimer: CountDownTimer? = null
//    private var flashAnimator: ObjectAnimator? = null
//
//    private var powerButtonPressCount = 0
//    private var lastPowerButtonPressTime = 0L
//    private val POWER_BUTTON_TIMEOUT = 3000L // 3 seconds window
//
//    private var scheduleJob: Job? = null
//    private lateinit var repository: MetroRepository
//
//
//
//    override fun onNewIntent(intent: Intent) {
//        super.onNewIntent(intent)
//        setIntent(intent) // Important!
//        if (intent.hasExtra("EXTRA_TRIP_ID")) {
//            val newId = intent.getLongExtra("EXTRA_TRIP_ID", -1L)
//            if (newId != -1L) {
//                currentTripId = newId
//            }
//        }
//        if (intent.getBooleanExtra("TRIGGER_SOS", false) ||
//            intent.action == "ACTION_SOS_FROM_NOTIFICATION") {
//            startSosCountdown()
//        }
//    }
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        initializeActivity()
//        setupUIComponents()
//        handleIntentData()
//        observeTripData()
//        registerJourneyUpdateReceiver()
//    }
//    private fun registerJourneyUpdateReceiver() {
//        val intentFilter = IntentFilter().apply {
//            addAction(JourneyTrackingService.ACTION_STATION_DETECTED)
//            addAction(JourneyTrackingService.ACTION_ROUTE_DIVERGENCE)
//            addAction(JourneyTrackingService.ACTION_GPS_RECOVERY)
//        }
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            registerReceiver(journeyUpdateReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
//        } else {
//            registerReceiver(journeyUpdateReceiver, intentFilter)
//        }
//    }
//
//    private fun initializeActivity() {
//        enableEdgeToEdge()
//        setContentView(R.layout.activity_tracking)
//        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
//
//        setupSpeedTracking()
//        checkAndRequestPermissions()
//        handleSosIntent()
//    }
//
//    private fun setupUIComponents() {
//        setupWindowInsets()
//        setupProgressIndicator()
//        setupRecyclerView()
//        setupButtonListeners()
//    }
//
//    private fun handleIntentData() {
//        extractTripIdFromIntent()
//        extractRoutePreferenceFromIntent()
//        initializeRepository()
//        loadGtfsDataAsync()
//    }
//
//    private fun observeTripData() {
//        val db = (application as MetroTrackerApplication).database
//        lifecycleScope.launch {
//            db.tripDao().getTripByIdFlow(currentTripId).collectLatest { trip ->
//                trip?.let { handleTripUpdate(it, db) }
//            }
//        }
//    }
//    private fun setupWindowInsets() {
//        val rootLayout = findViewById<ConstraintLayout>(R.id.trackingRoot)
//            ?: findViewById(android.R.id.content)
//
//        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }
//    }
//
//    private fun setupProgressIndicator() {
//        progressIndicator = findViewById(R.id.journeyProgress)
//    }
//
//    private fun setupRecyclerView() {
//        val rvPath = findViewById<RecyclerView>(R.id.rvStationPath)
//        rvPath.layoutManager = LinearLayoutManager(this)
//    }
//
//    private fun setupButtonListeners() {
//        setupManualUpdateButton()
//        setupSosButton()
//        setupStopJourneyButton()
//    }
//
//    private fun setupManualUpdateButton() {
//        findViewById<Button>(R.id.btnManualUpdate).setOnClickListener {
//            val remainingStations = currentJourneyPath.filter { it.stationId !in visitedStationIds }
//            showManualUpdateDialog(remainingStations)
//        }
//    }
//
//    private fun setupSosButton() {
//        findViewById<Button>(R.id.btnSOS)?.setOnClickListener {
//            startSosCountdown()
//        }
//    }
//
//    private fun setupStopJourneyButton() {
//        findViewById<Button>(R.id.btnStopJourney).setOnClickListener {
//            stopJourneyAndReturnToMain()
//        }
//    }
//
//    private fun stopJourneyAndReturnToMain() {
//        val stopIntent = Intent(this, JourneyTrackingService::class.java).apply {
//            action = JourneyTrackingService.ACTION_STOP_JOURNEY
//        }
//        startService(stopIntent)
//
//        val mainIntent = Intent(this, MainActivity::class.java).apply {
//            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
//        }
//        startActivity(mainIntent)
//        finish()
//    }
//    private fun handleSosIntent() {
//        if (intent.getBooleanExtra("TRIGGER_SOS", false) ||
//            intent.action == "ACTION_SOS_FROM_NOTIFICATION") {
//            startSosCountdown()
//        }
//    }
//
//    private fun extractTripIdFromIntent() {
//        currentTripId = intent.getLongExtra("EXTRA_TRIP_ID", -1L)
//    }
//
//    private fun extractRoutePreferenceFromIntent() {
//        val preferenceString = intent.getStringExtra("ROUTE_PREFERENCE")
//        routePreference = try {
//            RoutePlanner.RoutePreference.valueOf(preferenceString ?: "SHORTEST_PATH")
//        } catch (e: Exception) {
//            RoutePlanner.RoutePreference.SHORTEST_PATH
//        }
//    }
//
//    private fun initializeRepository() {
//        val db = (application as MetroTrackerApplication).database
//        repository = MetroRepository(db)
//    }
//    private fun loadGtfsDataAsync() {
//        val db = (application as MetroTrackerApplication).database
//        lifecycleScope.launch(Dispatchers.IO) {
//            loadGtfsData(db)
//            logGtfsDebugInfo(db)
//        }
//    }
//
//    private suspend fun loadGtfsData(db: com.metro.delhimetrotracker.data.local.database.AppDatabase) {
//        Log.d("DEBUG_GTFS", "Attempting to trigger loader...")
//        val loader = GtfsLoader(applicationContext, db)
//        loader.loadStopTimesIfNeeded()
//    }
//
//    private suspend fun logGtfsDebugInfo(db: com.metro.delhimetrotracker.data.local.database.AppDatabase) {
//        val count = db.stopTimeDao().getCount()
//        Log.d("GTFS_TEST", "Total rows: $count")
//
//        val allStops = db.stopTimeDao().getAllStopIds()
//        if (allStops.isNotEmpty()) {
//            val gtfsId = allStops.first()
//            val samples = db.stopTimeDao().getAnySampleTrains(gtfsId)
//            samples.take(5).forEach {
//                Log.d("GTFS_TEST", "${it.arrival_time} -> ${it.arrival_minutes}")
//            }
//        }
//    }
//    private suspend fun handleTripUpdate(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        db: com.metro.delhimetrotracker.data.local.database.AppDatabase
//    ) {
//        scheduleJob?.cancel()
//        emergencyContact = activeTrip.emergencyContact
//
//        val journeyPath = calculateJourneyPath(activeTrip, db)
//        currentJourneyPath = journeyPath
//        visitedStationIds = activeTrip.visitedStations
//
//        setupStationAdapter()
//        updateCurrentStationDisplay(activeTrip, journeyPath)
//        updateProgressDisplay(activeTrip, journeyPath)
//        updateStationList(activeTrip, journeyPath)
//        checkForDestinationVibration(activeTrip, journeyPath)
//
//        logStationDebugInfo(activeTrip, db)
//        startTrainScheduleMonitoring(activeTrip, journeyPath, db)
//    }
//
//    private suspend fun calculateJourneyPath(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        db: com.metro.delhimetrotracker.data.local.database.AppDatabase
//    ): List<MetroStation> {
//        val routePlanner = RoutePlanner(db)
//        val route = routePlanner.findRoute(
//            activeTrip.sourceStationId,
//            activeTrip.destinationStationId,
//            routePreference
//        )
//        return route?.segments?.flatMap { it.stations } ?: emptyList()
//    }
//
//    private fun setupStationAdapter() {
//        if (!::adapter.isInitialized) {
//            adapter = StationAdapter()
//            findViewById<RecyclerView>(R.id.rvStationPath).adapter = adapter
//        }
//    }
//
//    private fun updateCurrentStationDisplay(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>
//    ) {
//        val nextStation = journeyPath.find {
//            it.stationId == activeTrip.visitedStations.lastOrNull()
//        }
//        findViewById<TextView>(R.id.tvCurrentStation).text =
//            nextStation?.stationName ?: activeTrip.sourceStationName
//    }
//
//    private fun updateProgressDisplay(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>
//    ) {
//        if (journeyPath.isEmpty()) return
//
//        val totalStations = journeyPath.size
//        val currentStationIndex = journeyPath.indexOfLast { station ->
//            activeTrip.visitedStations.contains(station.stationId)
//        }.coerceAtLeast(0)
//
//        updateProgressText(currentStationIndex, totalStations)
//        updateEstimatedTime(currentStationIndex, totalStations)
//        updateProgressBar(currentStationIndex, totalStations)
//    }
//
//    private fun updateProgressText(currentIndex: Int, total: Int) {
//        val progressText = "${currentIndex + 1}/$total stations"
//        findViewById<TextView>(R.id.tvProgressPercent).text = progressText
//    }
//
//    private fun updateEstimatedTime(currentIndex: Int, total: Int) {
//        val stationsLeft = total - (currentIndex + 1)
//        val minutesRemaining = stationsLeft * 2
//        val estTimeText = if (minutesRemaining > 0) "~$minutesRemaining mins left" else "Arriving"
//        findViewById<TextView>(R.id.tvCurrentStationLabel).text = "EST. TIME: $estTimeText"
//    }
//
//    private fun updateProgressBar(currentIndex: Int, total: Int) {
//        val progress = when {
//            currentIndex == 0 -> 0f
//            currentIndex == total - 1 -> 100f
//            else -> {
//                val totalSegments = total - 1
//                (currentIndex.toFloat() / totalSegments.toFloat() * 100)
//            }
//        }
//        progressIndicator.setProgress(progress.toInt(), true)
//    }
//
//    private fun updateStationList(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>
//    ) {
//        adapter.submitData(journeyPath, activeTrip.visitedStations)
//    }
//
//    private fun checkForDestinationVibration(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>
//    ) {
//        val currentId = activeTrip.visitedStations.lastOrNull()
//        if (currentId != null && activeTrip.visitedStations.size > 1) {
//            val penultimateId = if (journeyPath.size >= 2)
//                journeyPath[journeyPath.size - 2].stationId
//            else null
//
//            if (currentId == activeTrip.destinationStationId || currentId == penultimateId) {
//                triggerTripleVibration()
//            }
//        }
//    }
//    private suspend fun logStationDebugInfo(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        db: com.metro.delhimetrotracker.data.local.database.AppDatabase
//    ) {
//        withContext(Dispatchers.IO) {
//            val station = repository.getStationById(activeTrip.sourceStationId)
//            val gtfsId = station?.gtfs_stop_id
//
//            Log.d("METRO_SCHEDULE_DEBUG", "[DEBUG] Testing GTFS data for station: ${station?.stationName}")
//            Log.d("METRO_SCHEDULE_DEBUG", "[DEBUG] GTFS ID: $gtfsId")
//
//            if (gtfsId != null) {
//                val anySampleTrains = db.stopTimeDao().getAnySampleTrains(gtfsId)
//                Log.d("METRO_SCHEDULE_DEBUG", "[DEBUG] Sample trains for this station: ${anySampleTrains.size}")
//                anySampleTrains.take(5).forEach {
//                    Log.d("METRO_SCHEDULE_DEBUG", "[DEBUG]    ${it.trip_id} at ${it.arrival_time}")
//                }
//            }
//        }
//    }
//
//    private fun startTrainScheduleMonitoring(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>,
//        db: com.metro.delhimetrotracker.data.local.database.AppDatabase
//    ) {
//        scheduleJob = lifecycleScope.launch {
//            while (isActive) {
//                updateNextTrainInfo(activeTrip, journeyPath)
//                delay(45000) // Check every 45 seconds
//            }
//        }
//    }
//
//    private suspend fun updateNextTrainInfo(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>
//    ) {
//        val currentStationId = activeTrip.visitedStations.lastOrNull() ?: activeTrip.sourceStationId
//        val currentTime = getCurrentTimeInformation()
//        val nextStationInRoute = getNextStationInRoute(activeTrip, journeyPath)
//
//        val nextTrain = repository.getNextTrainForStation(
//            currentStationId,
//            nextStationInRoute?.stationId,
//            currentTime.minutes
//        )
//
//        logTrainScheduleDebug(currentStationId, nextStationInRoute, currentTime, nextTrain)
//        updateTrainScheduleUI(nextTrain)
//    }
//
//    private data class TimeInformation(
//        val seconds: Int,
//        val minutes: Int,
//        val formattedTime: String
//    )
//
//    private fun getCurrentTimeInformation(): TimeInformation {
//        val cal = Calendar.getInstance()
//        val seconds = (cal.get(Calendar.HOUR_OF_DAY) * 3600) +
//                (cal.get(Calendar.MINUTE) * 60) +
//                cal.get(Calendar.SECOND)
//        val minutes = seconds / 60
//        val formattedTime = formatSeconds(seconds)
//
//        return TimeInformation(seconds, minutes, formattedTime)
//    }
//
//    private fun getNextStationInRoute(
//        activeTrip: com.metro.delhimetrotracker.data.local.database.entities.Trip,
//        journeyPath: List<MetroStation>
//    ): MetroStation? {
//        val nextStationIndex = activeTrip.visitedStations.size
//        return if (nextStationIndex < journeyPath.size) {
//            journeyPath[nextStationIndex]
//        } else null
//    }
//
//    private suspend fun logTrainScheduleDebug(
//        currentStationId: String,
//        nextStationInRoute: MetroStation?,
//        timeInfo: TimeInformation,
//        nextTrain: com.metro.delhimetrotracker.data.local.database.entities.StopTime?
//    ) {
//        val debugStation = repository.getStationById(currentStationId)
//
//        Log.d("METRO_SIMPLE_LOGIC", "━━━━━━━━━━━━━━━━━━━━━━━━━━")
//        Log.d("METRO_SIMPLE_LOGIC", "📍 Current Station: ${debugStation?.stationName}")
//        Log.d("METRO_SIMPLE_LOGIC", "➡️  Next Station: ${nextStationInRoute?.stationName ?: "Destination"}")
//        Log.d("METRO_SIMPLE_LOGIC", "⏰ Current Time: ${timeInfo.formattedTime}")
//        Log.d("METRO_SIMPLE_LOGIC", "🚆 Next Train: ${nextTrain?.arrival_time ?: "None"} (Trip: ${nextTrain?.trip_id ?: "N/A"})")
//    }
//
//    private suspend fun updateTrainScheduleUI(
//        nextTrain: com.metro.delhimetrotracker.data.local.database.entities.StopTime?
//    ) {
//        withContext(Dispatchers.Main) {
//            if (nextTrain != null) {
//                displayNextTrain(nextTrain)
//            } else {
//                displayNoTrains()
//            }
//            Log.d("METRO_SIMPLE_LOGIC", "━━━━━━━━━━━━━━━━━━━━━━━━━━")
//        }
//    }
//
//    private fun displayNextTrain(nextTrain: com.metro.delhimetrotracker.data.local.database.entities.StopTime) {
//        adapter.updateBaseTime(nextTrain.arrival_time)
//
//        findViewById<TextView>(R.id.tvNextTrainSchedule)?.apply {
//            text = "Next Train: ${formatTime(nextTrain.arrival_time)}"
//            setTextColor(Color.parseColor("#4CAF50")) // Green
//            visibility = View.VISIBLE
//        }
//
//        Log.d("METRO_SIMPLE_LOGIC", "✅ Displaying: Next Train at ${nextTrain.arrival_time}")
//        Log.d("METRO_SIMPLE_LOGIC", "📤 Sent to adapter: ${nextTrain.arrival_time}")
//    }
//
//    private fun displayNoTrains() {
//        adapter.updateBaseTime(null)
//
//        findViewById<TextView>(R.id.tvNextTrainSchedule)?.apply {
//            text = "No trains scheduled"
//            setTextColor(Color.parseColor("#FF5252")) // Red
//            visibility = View.VISIBLE
//        }
//
//        Log.d("METRO_SIMPLE_LOGIC", "⚠️ No trains found for this station")
//    }
//    private fun formatSeconds(seconds: Int): String {
//        val hours = (seconds / 3600) % 24
//        val mins = (seconds % 3600) / 60
//        val secs = seconds % 60
//        return String.format("%02d:%02d:%02d", hours, mins, secs)
//    }
//    private fun formatTime(timeStr: String): String {
//        return try {
//            val inputFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
//            val outputFormat = SimpleDateFormat("h:mm a", Locale.US)
//            val date = inputFormat.parse(timeStr)
//            outputFormat.format(date ?: return timeStr)
//        } catch (e: Exception) {
//            timeStr // Return original if parsing fails
//        }
//    }
//    private fun checkAndRequestPermissions() {
//        val permissionsNeeded = mutableListOf<String>()
//
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
//            != PackageManager.PERMISSION_GRANTED) {
//            permissionsNeeded.add(Manifest.permission.SEND_SMS)
//        }
//
//        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
//            != PackageManager.PERMISSION_GRANTED) {
//            permissionsNeeded.add(Manifest.permission.CALL_PHONE)
//        }
//
//        if (permissionsNeeded.isNotEmpty()) {
//            ActivityCompat.requestPermissions(
//                this,
//                permissionsNeeded.toTypedArray(),
//                REQUEST_SOS_PERMISSIONS
//            )
//        }
//    }
//
//    override fun onRequestPermissionsResult(
//        requestCode: Int,
//        permissions: Array<out String>,
//        grantResults: IntArray
//    ) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//
//        if (requestCode == REQUEST_SOS_PERMISSIONS) {
//            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
//            if (allGranted) {
//                Toast.makeText(this, "SOS permissions granted", Toast.LENGTH_SHORT).show()
//            } else {
//                Toast.makeText(this, "⚠️ SOS requires SMS and Call permissions", Toast.LENGTH_LONG).show()
//            }
//        }
//    }
//
//    companion object {
//        private const val REQUEST_SOS_PERMISSIONS = 1001
//    }
//    private fun triggerTripleVibration() {
//        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
//        val pattern = longArrayOf(0, 400, 150, 400, 150, 400, 150, 400, 150, 400, 150, 400, 150, 400, 150, 400)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
//        } else {
//            @Suppress("DEPRECATION")
//            v.vibrate(pattern, -1)
//        }
//    }
//
//    private val smsResultReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            when (resultCode) {
//                Activity.RESULT_OK -> Toast.makeText(context, "✅ SMS Delivered", Toast.LENGTH_SHORT).show()
//                else -> Toast.makeText(context, "❌ SMS Failed", Toast.LENGTH_SHORT).show()
//            }
//        }
//    }
//
//    private val resetReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            if (intent?.action == "ACTION_JOURNEY_STOPPED") {
//                val mainIntent = Intent(this@TrackingActivity, MainActivity::class.java).apply {
//                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
//                }
//                startActivity(mainIntent)
//                finish()
//            }
//        }
//    }
//
//    override fun onStart() {
//        super.onStart()
//        val filterSms = IntentFilter("com.metro.delhimetrotracker.SMS_SENT")
//        val filterReset = IntentFilter("ACTION_JOURNEY_STOPPED")
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            registerReceiver(smsResultReceiver, filterSms, Context.RECEIVER_NOT_EXPORTED)
//            registerReceiver(resetReceiver, filterReset, Context.RECEIVER_NOT_EXPORTED)
//        } else {
//            registerReceiver(smsResultReceiver, filterSms)
//            registerReceiver(resetReceiver, filterReset)
//        }
//
//        // Inside TrackingActivity.kt onCreate or onStart
//        val mockReceiver = object : BroadcastReceiver() {
//            override fun onReceive(context: Context?, intent: Intent?) {
//                // Show a blocking dialog or finish the activity
//                MaterialAlertDialogBuilder(this@TrackingActivity)
//                    .setTitle("Security Alert")
//                    .setMessage("Mock location apps are not allowed. Please disable them to track your journey.")
//                    .setCancelable(false)
//                    .setPositiveButton("Exit") { _, _ -> finish() }
//                    .show()
//            }
//        }
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            registerReceiver(mockReceiver, IntentFilter("ACTION_MOCK_LOCATION_DETECTED"), Context.RECEIVER_NOT_EXPORTED)
//        } else {
//            registerReceiver(mockReceiver, IntentFilter("ACTION_MOCK_LOCATION_DETECTED"))
//        }
//    }
//
//    private var mockReceiver: BroadcastReceiver? = null
//
//    override fun onStop() {
//        super.onStop()
//        try {
//            unregisterReceiver(smsResultReceiver)
//            unregisterReceiver(resetReceiver)
//            mockReceiver?.let { unregisterReceiver(it) }
//        } catch (e: Exception) {
//            // Receiver not registered
//        }
//    }
//
//    private fun showManualUpdateDialog(remainingStations: List<MetroStation>) {
//        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_update, null)
//        val spinnerStation = dialogView.findViewById<Spinner>(R.id.spinnerStation)
//        val spinnerTime = dialogView.findViewById<Spinner>(R.id.spinnerTime)
//        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDialog)
//        val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdateDialog)
//
//        spinnerStation.adapter = ArrayAdapter(
//            this,
//            android.R.layout.simple_spinner_dropdown_item,
//            remainingStations.map { it.stationName }
//        )
//
//        val timeOptions = listOf("Just now", "1 min ago", "2 min ago", "5 min ago", "10 min ago")
//        spinnerTime.adapter = ArrayAdapter(
//            this,
//            android.R.layout.simple_spinner_dropdown_item,
//            timeOptions
//        )
//
//        val dialog = MaterialAlertDialogBuilder(this)
//            .setView(dialogView)
//            .setCancelable(true)
//            .create()
//
//        btnCancel.setOnClickListener {
//            dialog.dismiss()
//        }
//
//        btnUpdate.setOnClickListener {
//            val selectedStation = remainingStations[spinnerStation.selectedItemPosition]
//
//            Log.d("TrackingActivity", "User selected station: ${selectedStation.stationName}, ID: ${selectedStation.stationId}")
//
//
//            val timeOffset = timeOptions[spinnerTime.selectedItemPosition]
//
//            val intent = Intent(this, JourneyTrackingService::class.java).apply {
//                action = JourneyTrackingService.ACTION_MANUAL_STATION_UPDATE
//                putExtra(JourneyTrackingService.EXTRA_STATION_ID, selectedStation.stationId)
//                putExtra(JourneyTrackingService.EXTRA_TIME_OFFSET, timeOffset)
//            }
//            startService(intent)
//
//            dialog.dismiss()
//        }
//
//        dialog.show()
//    }
//
//    private fun startSosCountdown() {
//        val overlay = findViewById<FrameLayout>(R.id.sosOverlay)
//        val tvCountdown = findViewById<TextView>(R.id.tvSosCountdown)
//        overlay.visibility = View.VISIBLE
//
//        // Flashing Red Animation (faster for urgency)
//        flashAnimator = ObjectAnimator.ofInt(
//            overlay, "backgroundColor",
//            Color.parseColor("#88FF0000"), Color.parseColor("#FFFF0000")
//        ).apply {
//            duration = 300
//            setEvaluator(ArgbEvaluator())
//            repeatCount = ValueAnimator.INFINITE
//            repeatMode = ValueAnimator.REVERSE
//            start()
//        }
//
//        // Get vibrator
//        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
//
//        // 5-Second Countdown Timer with vibration every second
//        sosTimer = object : CountDownTimer(5000, 1000) {
//            override fun onTick(millisUntilFinished: Long) {
//                val secondsLeft = (millisUntilFinished / 1000) + 1
//                tvCountdown.text = "🆘 SENDING SOS IN $secondsLeft"
//
//                // Strong vibration pattern every second
//                val vibratePattern = longArrayOf(0, 300, 100, 300)
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    vibrator.vibrate(VibrationEffect.createWaveform(vibratePattern, -1))
//                } else {
//                    @Suppress("DEPRECATION")
//                    vibrator.vibrate(vibratePattern, -1)
//                }
//            }
//
//            override fun onFinish() {
//                // Final long vibration before sending
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                    vibrator.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
//                } else {
//                    @Suppress("DEPRECATION")
//                    vibrator.vibrate(1000)
//                }
//
//                executeEmergencyActions()
//                stopSosUI()
//            }
//        }.start()
//
//        // Double Tap to Cancel
//        var lastClickTime: Long = 0
//        overlay.setOnClickListener {
//            val clickTime = System.currentTimeMillis()
//            if (clickTime - lastClickTime < 300) {
//                cancelSos()
//            }
//            lastClickTime = clickTime
//        }
//    }
//
//    private fun executeEmergencyActions() {
//        if (currentTripId == -1L) {
//            Toast.makeText(this, "Error: Invalid Trip ID", Toast.LENGTH_SHORT).show()
//            return
//        }
//
//        // 1. Get UI data on the Main Thread BEFORE switching to IO
//        val stationName = findViewById<TextView>(R.id.tvCurrentStation)?.text?.toString() ?: "Unknown Station"
//
//        lifecycleScope.launch(Dispatchers.IO) {
//            try {
//                val db = (application as MetroTrackerApplication).database
//                db.tripDao().markTripWithSos(
//                    tripId = currentTripId,
//                    stationName = stationName,
//                    timestamp = System.currentTimeMillis()
//                )
//                Log.d("TrackingActivity", "✅ SOS marked in DB for Trip: $currentTripId")
//            } catch (e: Exception) {
//                Log.e("TrackingActivity", "❌ Failed to mark SOS in DB", e)
//            }
//            withContext(Dispatchers.Main) {
//                // Check Permissions
//                if (ActivityCompat.checkSelfPermission(this@TrackingActivity, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
//                    Toast.makeText(this@TrackingActivity, "⚠️ SOS Recorded, but SMS permission missing!", Toast.LENGTH_LONG).show()
//                    return@withContext
//                }
//
//                try {
//                    // Get Location (Suspend function)
//                    val location = if (ActivityCompat.checkSelfPermission(this@TrackingActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//                        try { fusedLocationClient.lastLocation.await() } catch (e: Exception) { null }
//                    } else null
//
//                    val mapsLink = if (location != null) "https://maps.google.com/?q=${location.latitude},${location.longitude}" else "Location unavailable"
//
//                    val message = """
//                🆘 EMERGENCY ALERT!
//                I need help.
//                Metro Station: $stationName
//                Loc: $mapsLink
//                """.trimIndent()
//
//                    // Send SMS
//                    if (emergencyContact.isNotEmpty()) {
//                        val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
//                        val parts = smsManager.divideMessage(message)
//
//                        if (parts.size > 1) {
//                            smsManager.sendMultipartTextMessage(emergencyContact, null, parts, null, null)
//                        } else {
//                            smsManager.sendTextMessage(emergencyContact, null, message, null, null)
//                        }
//                        Toast.makeText(this@TrackingActivity, "✅ SOS SMS Sent!", Toast.LENGTH_SHORT).show()
//                    }
//
//                    // Make Call (After 1.5s delay)
//                    if (ActivityCompat.checkSelfPermission(this@TrackingActivity, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED && emergencyContact.isNotEmpty()) {
//                        delay(1500)
//                        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$emergencyContact")))
//                    }
//
//                } catch (e: Exception) {
//                    android.util.Log.e("TrackingActivity", "SMS/Call Failed", e)
//                    Toast.makeText(this@TrackingActivity, "SMS failed: ${e.message}", Toast.LENGTH_SHORT).show()
//                }
//            }
//        }
//    }
//
//    private fun cancelSos() {
//        sosTimer?.cancel()
//        flashAnimator?.cancel()
//        stopSosUI()
//        Toast.makeText(this, "SOS Cancelled", Toast.LENGTH_SHORT).show()
//    }
//
//    private fun stopSosUI() {
//        sosTimer?.cancel()
//        flashAnimator?.cancel()
//        findViewById<FrameLayout>(R.id.sosOverlay)?.visibility = View.GONE
//    }
//
//    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
//        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
//            val currentTime = System.currentTimeMillis()
//
//            // Reset count if timeout exceeded
//            if (currentTime - lastPowerButtonPressTime > POWER_BUTTON_TIMEOUT) {
//                powerButtonPressCount = 0
//            }
//
//            powerButtonPressCount++
//            lastPowerButtonPressTime = currentTime
//
//            // Show feedback
//            Toast.makeText(this, "SOS trigger: $powerButtonPressCount/5", Toast.LENGTH_SHORT).show()
//
//            if (powerButtonPressCount >= 5) {
//                powerButtonPressCount = 0
//                startSosCountdown()
//                return true
//            }
//
//            return true
//        }
//        return super.onKeyDown(keyCode, event)
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        stopSosUI()
//        locationCallback?.let {
//            fusedLocationClient.removeLocationUpdates(it)
//        }
//        try {
//            unregisterReceiver(journeyUpdateReceiver)
//        } catch (e: Exception) {
//            Log.e("TrackingActivity", "Error unregistering receiver", e)
//        }
//    }
//    private fun setupSpeedTracking() {
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_FINE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            return
//        }
//
//        val locationRequest = com.google.android.gms.location.LocationRequest.create().apply {
//            interval = 1000 // Update every second
//            fastestInterval = 500
//            priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
//        }
//
//        locationCallback = object : com.google.android.gms.location.LocationCallback() {
//            override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
//                locationResult.lastLocation?.let { location ->
//                    val speedKmh = if (location.hasSpeed()) {
//                        location.speed * 3.6f // Convert m/s to km/h
//                    } else {
//                        calculateSpeedFromDistance(location)
//                    }
//                    updateSpeedDisplay(speedKmh)
//                }
//            }
//        }
//
//        fusedLocationClient.requestLocationUpdates(
//            locationRequest,
//            locationCallback!!,
//            Looper.getMainLooper()
//        )
//    }
//    private fun calculateSpeedFromDistance(currentLocation: android.location.Location): Float {
//        previousLocation?.let { previous ->
//            val distance = previous.distanceTo(currentLocation)
//            val timeDiff = (currentLocation.time - previous.time) / 1000f
//            previousLocation = currentLocation
//
//            return if (timeDiff > 0) (distance / timeDiff) * 3.6f else 0f
//        }
//        previousLocation = currentLocation
//        return 0f
//    }
//    private fun updateSpeedDisplay(speedKmh: Float) {
//        findViewById<TextView>(R.id.tvSpeedValue)?.text = String.format("%.0f", speedKmh.coerceAtLeast(0f))
//    }
//
//    private val journeyUpdateReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context?, intent: Intent?) {
//            when (intent?.action) {
//                JourneyTrackingService.ACTION_STATION_DETECTED -> handleStationDetected(intent)
//                JourneyTrackingService.ACTION_ROUTE_DIVERGENCE -> handleRouteDivergence(intent)
//                JourneyTrackingService.ACTION_GPS_RECOVERY -> handleGPSRecovery(intent)
//            }
//        }
//    }
//
//    private fun handleStationDetected(intent: Intent) {
//        val stationName = intent.getStringExtra("STATION_NAME") ?: return
//
//        // Refresh trip data - the database has already been updated
//        lifecycleScope.launch {
//            val db = (application as MetroTrackerApplication).database
//            val trip = db.tripDao().getTripById(currentTripId)
//            trip?.let {
//                visitedStationIds = it.visitedStations
//                adapter.submitData(currentJourneyPath, visitedStationIds)
//                progressIndicator.progress = visitedStationIds.size
//            }
//        }
//
//        // Show snackbar
//        Snackbar.make(
//            findViewById(android.R.id.content),
//            "✓ Reached $stationName",
//            Snackbar.LENGTH_SHORT
//        ).show()
//    }
//
//    private fun handleRouteDivergence(intent: Intent) {
//        val newPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            intent.getParcelableArrayListExtra("NEW_PATH", MetroStation::class.java)
//        } else {
//            @Suppress("DEPRECATION")
//            intent.getParcelableArrayListExtra<MetroStation>("NEW_PATH")
//        }
//        val reason = intent.getStringExtra("REASON") ?: "Route changed"
//
//        newPath?.let {
//            currentJourneyPath = it
//            adapter.submitData(it, visitedStationIds)
//
//            // Show rerouting snackbar
//            Snackbar.make(
//                findViewById(android.R.id.content),
//                "🔄 $reason - Route updated",
//                Snackbar.LENGTH_LONG
//            ).setAction("OK") {}.show()
//        }
//    }
//
//    private fun handleGPSRecovery(intent: Intent) {
//        val inferredStations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            intent.getParcelableArrayListExtra("INFERRED_STATIONS", MetroStation::class.java)
//        } else {
//            @Suppress("DEPRECATION")
//            intent.getParcelableArrayListExtra<MetroStation>("INFERRED_STATIONS")
//        }
//        val gapDuration = intent.getLongExtra("GAP_DURATION", 0)
//
//        val gapMinutes = (gapDuration / 60000).toInt()
//
//        Snackbar.make(
//            findViewById(android.R.id.content),
//            "📍 GPS recovered (${gapMinutes}m gap) - ${inferredStations?.size ?: 0} stations inferred",
//            Snackbar.LENGTH_LONG
//        ).show()
//    }
//
//}