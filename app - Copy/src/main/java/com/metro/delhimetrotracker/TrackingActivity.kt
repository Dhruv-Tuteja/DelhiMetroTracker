package com.metro.delhimetrotracker.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.service.JourneyTrackingService
import com.metro.delhimetrotracker.utils.MetroNavigator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TrackingActivity : AppCompatActivity() {

    private lateinit var adapter: StationAdapter
    private lateinit var progressIndicator: LinearProgressIndicator

    // Store journey data for manual update dialog
    private var currentJourneyPath: List<MetroStation> = emptyList()
    private var visitedStationIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_tracking)

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
            // Filter remaining stations (not yet visited)
            val remainingStations = currentJourneyPath.filter { it.stationId !in visitedStationIds }
            showManualUpdateDialog(remainingStations)
        }

        rvPath.layoutManager = LinearLayoutManager(this)
        val tripId = intent.getLongExtra("EXTRA_TRIP_ID", -1L)
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
                    val journeyPath = MetroNavigator.findShortestPath(
                        db.metroStationDao(),
                        activeTrip.sourceStationId,
                        activeTrip.destinationStationId
                    )

                    // Store for manual update dialog
                    currentJourneyPath = journeyPath

                    if (currentJourneyPath.isEmpty()) {
                        currentJourneyPath = MetroNavigator.findShortestPath(
                            db.metroStationDao(),
                            activeTrip.sourceStationId,
                            activeTrip.destinationStationId
                        )
                    }

                    visitedStationIds = activeTrip.visitedStations

                    if (!::adapter.isInitialized) {
                        adapter = StationAdapter()
                        rvPath.adapter = adapter
                    }

                    // Update Hero Text: Showing the next unvisited station
                    val nextStation = journeyPath.find { it.stationId == (activeTrip.visitedStations.lastOrNull()) }
                    findViewById<TextView>(R.id.tvCurrentStation).text = nextStation?.stationName ?: activeTrip.sourceStationName

                    // Update Progress Bar
                    if (journeyPath.isNotEmpty()) {
                        val totalStations = journeyPath.size

                        // Find the index of the last visited station in the journey path
                        val currentStationIndex = journeyPath.indexOfLast { station ->
                            activeTrip.visitedStations.contains(station.stationId)
                        }.coerceAtLeast(0)

                        val progress = when {
                            // At starting station (index 0)
                            currentStationIndex == 0 -> 0f

                            // At destination
                            currentStationIndex == totalStations - 1 -> 100f

                            // In between
                            else -> {
                                val totalSegments = totalStations - 1
                                (currentStationIndex.toFloat() / totalSegments.toFloat() * 100)
                            }
                        }
                        val progressPercent = findViewById<TextView>(R.id.tvProgressPercent)
                        progressPercent.text = "${progress.toInt()}%" // Dynamic update
                        progressIndicator.setProgress(progress.toInt(), true)
                    }

                    adapter.submitData(journeyPath, activeTrip.visitedStations)

                    // Logic for vibration alerts at destination
                    val currentId = activeTrip.visitedStations.lastOrNull()
                    if (currentId != null && activeTrip.visitedStations.size > 1) {
                        val penultimateId = if (journeyPath.size >= 2) journeyPath[journeyPath.size - 2].stationId else null
                        if (currentId == activeTrip.destinationStationId || currentId == penultimateId) {
                            triggerTripleVibration()
                        }
                    }
                }
            }
        }
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
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(smsResultReceiver)
            unregisterReceiver(resetReceiver)
        } catch (e: Exception) {
            // Log.e("TrackingActivity", "Receiver not registered")
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

        // Create dialog without title and buttons (we have custom ones in the layout)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        // Handle Cancel button
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        // Handle Update button
        btnUpdate.setOnClickListener {
            val selectedStation = remainingStations[spinnerStation.selectedItemPosition]
            val timeOffset = timeOptions[spinnerTime.selectedItemPosition]

            // Send command to Service
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
}