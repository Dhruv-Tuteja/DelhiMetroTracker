package com.metro.delhimetrotracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder
import com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
import com.google.android.gms.common.api.ApiException
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import com.metro.delhimetrotracker.data.local.database.entities.TripStatus
import com.metro.delhimetrotracker.data.repository.DatabaseInitializer
import com.metro.delhimetrotracker.data.repository.GtfsLoader
import com.metro.delhimetrotracker.data.repository.MetroRepository
import com.metro.delhimetrotracker.data.repository.RoutePlanner
import com.metro.delhimetrotracker.receivers.ScheduledTripAlarmManager
import com.metro.delhimetrotracker.service.JourneyTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import androidx.appcompat.app.AppCompatDelegate
import android.view.Menu
import android.view.MenuItem
import com.metro.delhimetrotracker.ui.dashboard.DashboardFragment
import android.widget.ImageView

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var repository: MetroRepository
    private val auth = FirebaseAuth.getInstance()

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNavigation: BottomNavigationView
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Toast.makeText(this, "✅ Permissions granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Tracking will not work correctly without permissions.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    loadFragment(HomeFragment())
                    true
                }
                R.id.nav_trips -> {
                    // TODO: Replace with your actual Dashboard fragment class name
                    loadFragment(DashboardFragment())
                    true
                }
                R.id.nav_account -> {
                    loadFragment(AccountFragment())
                    true
                }
                else -> false
            }
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            firebaseAuthWithGoogle(account.idToken!!)
        } catch (e: ApiException) {
            Toast.makeText(this, "Google Sign-In failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    // ===== FIX 1: IMMEDIATE SYNC ON TRIP DELETE =====
    fun deleteTrip(tripId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val appDb = (application as MetroTrackerApplication).database

            // Soft delete the trip
            appDb.tripDao().markTripAsDeleted(tripId)

            // IMMEDIATE SYNC: Upload to cloud right away if user is signed in
            val user = auth.currentUser
            if (user != null) {
                try {
                    syncDeletedTripToCloud(tripId)
                    Log.d("Sync", "Trip $tripId marked as deleted and synced to cloud")
                } catch (e: Exception) {
                    Log.e("Sync", "Failed to sync deleted trip: ${e.message}")
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Trip deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== FIX 2: IMMEDIATE SYNC ON SCHEDULED TRIP CREATE/DELETE =====
    fun deleteScheduledTrip(tripId: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val appDb = (application as MetroTrackerApplication).database
            val trip = appDb.scheduledTripDao().getScheduledTripById(tripId)

            if (trip != null) {
                // Mark as deleted (soft delete)
                ScheduledTripAlarmManager.cancelTrip(applicationContext, trip.id)

                val updatedTrip = trip.copy(
                    isDeleted = true,
                    syncState = "PENDING",
                    lastModified = System.currentTimeMillis()
                )
                appDb.scheduledTripDao().update(updatedTrip)
                uploadScheduledTripsToCloud()
            }
        }
    }

    // ===== NEW: RESTORE DELETED TRIP WITH IMMEDIATE SYNC =====
    fun restoreTrip(tripId: Long, onComplete: () -> Unit = {}) {
        lifecycleScope.launch(Dispatchers.IO) {
            val appDb = (application as MetroTrackerApplication).database

            // Restore the trip locally
            appDb.tripDao().restoreTrip(tripId)

            // Get the full trip data to sync back to cloud
            val trip = appDb.tripDao().getTripByIdIncludingDeleted(tripId)

            if (trip != null) {
                // IMMEDIATE SYNC: Upload restored trip to cloud
                val user = auth.currentUser
                if (user != null) {
                    try {
                        syncRestoredTripToCloud(tripId)
                        Log.d("Sync", "Trip $tripId restored and synced to cloud")

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Trip restored", Toast.LENGTH_SHORT).show()
                            onComplete()
                        }
                    } catch (e: Exception) {
                        Log.e("Sync", "Failed to sync restored trip: ${e.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Restored locally, will sync later", Toast.LENGTH_SHORT).show()
                            onComplete()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Trip restored", Toast.LENGTH_SHORT).show()
                        onComplete()
                    }
                }
            }
        }
    }

    // ===== HELPER: SYNC RESTORED TRIP TO CLOUD =====
    private suspend fun syncRestoredTripToCloud(tripId: Long) {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        val appDb = (application as MetroTrackerApplication).database

        try {
            val trip = appDb.tripDao().getTripByIdIncludingDeleted(tripId) ?: return
            val checkpoints = appDb.tripDao().getCheckpointsForTrip(tripId)

            // Format ID to 4 digits to match SyncWorker format
            val formattedDocId = String.format(java.util.Locale.US, "%04d", tripId)

            // Convert checkpoints to Firestore format
            val checkpointDataList = checkpoints.map { checkpoint ->
                mapOf(
                    "stationId" to checkpoint.stationId,
                    "stationName" to checkpoint.stationName,
                    "stationOrder" to checkpoint.stationOrder,
                    "arrivalTime" to checkpoint.arrivalTime.time,
                    "departureTime" to checkpoint.departureTime?.time,
                    "detectionMethod" to checkpoint.detectionMethod.name,
                    "confidence" to checkpoint.confidence,
                    "smsSent" to checkpoint.smsSent,
                    "smsTimestamp" to checkpoint.smsTimestamp?.time,
                    "latitude" to checkpoint.latitude,
                    "longitude" to checkpoint.longitude,
                    "accuracy" to checkpoint.accuracy,
                    "timestamp" to checkpoint.timestamp
                )
            }

            // Create full trip data map
            val tripMap = hashMapOf(
                "id" to trip.id,
                "sourceStationId" to trip.sourceStationId,
                "sourceStationName" to trip.sourceStationName,
                "destinationStationId" to trip.destinationStationId,
                "destinationStationName" to trip.destinationStationName,
                "metroLine" to trip.metroLine,
                "startTime" to trip.startTime.time,
                "endTime" to trip.endTime?.time,
                "durationMinutes" to trip.durationMinutes,
                "visitedStations" to trip.visitedStations,
                "fare" to trip.fare,
                "status" to trip.status.name,
                "emergencyContact" to trip.emergencyContact,
                "smsCount" to trip.smsCount,
                "createdAt" to trip.createdAt.time,
                "notes" to trip.notes,
                "hadSosAlert" to trip.hadSosAlert,
                "sosStationName" to trip.sosStationName,
                "sosTimestamp" to trip.sosTimestamp,
                "cancellationReason" to trip.cancellationReason,
                "syncState" to "SYNCED",
                "deviceId" to getUniqueDeviceId(),
                "lastModified" to System.currentTimeMillis(),
                "isDeleted" to false,  // ✅ Mark as NOT deleted
                "schemaVersion" to trip.schemaVersion,
                "checkpoints" to checkpointDataList
            )

            // Upload full trip to cloud
            db.collection("users")
                .document(user.uid)
                .collection("trips")
                .document(formattedDocId)
                .set(tripMap)
                .addOnSuccessListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.tripDao().updateSyncStatus(tripId, "SYNCED")
                    }
                }
                .await()
        } catch (e: Exception) {
            Log.e("Sync", "Failed to sync restored trip: ${e.message}")
            throw e
        }
    }

    // ===== HELPER: SYNC SINGLE DELETED TRIP TO CLOUD =====
    private suspend fun syncDeletedTripToCloud(tripId: Long) {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        val appDb = (application as MetroTrackerApplication).database

        try {
            // Format ID to 4 digits to match SyncWorker format (0001, 0002, etc.)
            val formattedDocId = String.format(java.util.Locale.US, "%04d", tripId)

            // Upload tombstone to cloud
            db.collection("users")
                .document(user.uid)
                .collection("trips")
                .document(formattedDocId)  // ✅ Use formatted ID
                .set(hashMapOf(
                    "id" to tripId,
                    "isDeleted" to true,
                    "lastModified" to System.currentTimeMillis(),
                    "deviceId" to getUniqueDeviceId()
                ))
                .addOnSuccessListener {
                    lifecycleScope.launch(Dispatchers.IO) {
                        appDb.tripDao().updateSyncStatus(tripId, "SYNCED")
                    }
                }
                .await()
        } catch (e: Exception) {
            Log.e("Sync", "Failed to sync deleted trip: ${e.message}")
        }
    }

    // ===== FIX 3: IMPROVED MANUAL SYNC (CALLED ON SWIPE DOWN) =====
    fun performManualSync(onComplete: () -> Unit={}) {
        val user = auth.currentUser
        if (user == null) {
            onComplete()
            return
        }

        // 1. TRIGGER PUSH (Upload pending trips with isDeleted flag)
        val workManager = androidx.work.WorkManager.getInstance(applicationContext)
        val syncRequest = androidx.work.OneTimeWorkRequest.Builder(com.metro.delhimetrotracker.worker.SyncWorker::class.java)
            .build()
        workManager.enqueue(syncRequest)

        // Also sync scheduled trips
        uploadScheduledTripsToCloud()

        // 2. TRIGGER PULL (Download trips from cloud)
        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(user.uid)
            .collection("trips")
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    onComplete()
                    return@addOnSuccessListener
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val appDb = (application as MetroTrackerApplication).database
                    var changesCount = 0

                    for (document in documents) {
                        try {
                            val id = document.getLong("id") ?: 0L
                            val cloudLastModified = document.getLong("lastModified") ?: 0L
                            val cloudIsDeleted = document.getBoolean("isDeleted") ?: false

                            // 🛑 CRITICAL: Check local state first
                            val localTrip = appDb.tripDao().getTripByIdIncludingDeleted(id)

                            if (localTrip?.isDeleted == true) {
                                Log.d("Sync", "Skipping cloud revive for deleted trip $id")
                                continue
                            }


                            // If local trip has PENDING changes, don't overwrite with cloud data
                            if (localTrip != null && localTrip.syncState == "PENDING") {
                                Log.d("Sync", "Skipping Trip $id - Local changes pending sync")
                                continue
                            }

                            // If local is newer than cloud, skip cloud update
                            if (localTrip != null && localTrip.lastModified > cloudLastModified) {
                                Log.d("Sync", "Skipping Trip $id - Local is newer")
                                continue
                            }

                            // Download the trip from cloud (including isDeleted state)
                            val trip = Trip(
                                id = id,
                                sourceStationId = document.getString("sourceStationId") ?: "",
                                sourceStationName = document.getString("sourceStationName") ?: "",
                                destinationStationId = document.getString("destinationStationId") ?: "",
                                destinationStationName = document.getString("destinationStationName") ?: "",
                                metroLine = document.getString("metroLine") ?: "",
                                startTime = Date(document.getLong("startTime") ?: 0L),
                                endTime = document.getLong("endTime")?.let { Date(it) },
                                durationMinutes = document.getLong("durationMinutes")?.toInt(),
                                // ✅ FIX: Safely cast Firestore array
                                visitedStations = (document.get("visitedStations") as? ArrayList<*>)
                                    ?.filterIsInstance<String>() ?: emptyList(),
                                fare = document.getDouble("fare"),
                                status = TripStatus.valueOf(
                                    document.getString("status") ?: "COMPLETED"
                                ),
                                emergencyContact = document.getString("emergencyContact") ?: "",
                                smsCount = document.getLong("smsCount")?.toInt() ?: 0,
                                createdAt = document.getLong("createdAt")?.let { Date(it) } ?: Date(),
                                notes = document.getString("notes"),
                                hadSosAlert = document.getBoolean("hadSosAlert") ?: false,
                                sosStationName = document.getString("sosStationName"),
                                sosTimestamp = document.getLong("sosTimestamp"),
                                cancellationReason = document.getString("cancellationReason"),
                                syncState = "SYNCED",
                                deviceId = document.getString("deviceId") ?: "cloud",
                                lastModified = cloudLastModified,
                                isDeleted = if (localTrip != null && localTrip.isDeleted && localTrip.lastModified > cloudLastModified) {
                                    true
                                } else {
                                    cloudIsDeleted
                                },// ← Keep the deletion state from cloud
                                schemaVersion = document.getLong("schemaVersion")?.toInt() ?: 1
                            )
                            if (localTrip != null && localTrip.isDeleted) {
                                // Local trip is deleted, keep it deleted regardless of cloud state
                                Log.d("Sync", "Skipping Trip $id - Locally deleted")
                                continue
                            }

                            appDb.tripDao().insertTrip(trip)

                            // ✅ NEW: Delete old checkpoints and restore new ones
                            appDb.stationCheckpointDao().deleteCheckpointsByTrip(id)

                            val checkpointsData = document.get("checkpoints") as? ArrayList<*>
                            checkpointsData?.forEach { checkpointObj ->
                                try {
                                    val checkpointMap = checkpointObj as? Map<*, *>
                                    if (checkpointMap != null) {
                                        val checkpoint = com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint(
                                            tripId = id,
                                            stationId = checkpointMap["stationId"] as? String ?: "",
                                            stationName = checkpointMap["stationName"] as? String ?: "",
                                            stationOrder = (checkpointMap["stationOrder"] as? Long)?.toInt() ?: 0,
                                            arrivalTime = Date((checkpointMap["arrivalTime"] as? Long) ?: 0L),
                                            departureTime = (checkpointMap["departureTime"] as? Long)?.let { Date(it) },
                                            detectionMethod = com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod.valueOf(
                                                checkpointMap["detectionMethod"] as? String ?: "MANUAL"
                                            ),
                                            confidence = (checkpointMap["confidence"] as? Double)?.toFloat() ?: 1.0f,
                                            smsSent = checkpointMap["smsSent"] as? Boolean ?: false,
                                            smsTimestamp = (checkpointMap["smsTimestamp"] as? Long)?.let { Date(it) },
                                            latitude = checkpointMap["latitude"] as? Double,
                                            longitude = checkpointMap["longitude"] as? Double,
                                            accuracy = (checkpointMap["accuracy"] as? Double)?.toFloat(),
                                            timestamp = checkpointMap["timestamp"] as? Long
                                        )
                                        appDb.stationCheckpointDao().insertCheckpoint(checkpoint)
                                    }
                                } catch (e: Exception) {
                                    Log.e("Sync", "Failed to parse checkpoint: ${e.message}")
                                }
                            }

                            changesCount++

                        } catch (e: Exception) {
                            Log.e("Sync", "Error parsing trip: ${e.message}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (changesCount > 0) {
                            Toast.makeText(this@MainActivity, "Synced trips", Toast.LENGTH_SHORT).show()
                        }
                        onComplete()
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Sync Failed", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        downloadScheduledTripsFromCloud()

        onComplete()
    }

    private fun downloadScheduledTripsFromCloud() {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(user.uid)
            .collection("scheduled_trips")
            .get()
            .addOnSuccessListener { documents ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val appDb = (application as MetroTrackerApplication).database

                    for (document in documents) {
                        try {
                            val id = document.getLong("id") ?: 0L
                            val cloudLastModified = document.getLong("lastModified") ?: 0L
                            val cloudIsDeleted = document.getBoolean("isDeleted") ?: false

                            val localTrip = appDb.scheduledTripDao().getScheduledTripById(id)

                            // Skip if local has pending changes
                            if (localTrip != null && localTrip.syncState == "PENDING") {
                                continue
                            }

                            // Skip if local is newer
                            if (localTrip != null && localTrip.lastModified > cloudLastModified) {
                                continue
                            }

                            val trip = ScheduledTrip(
                                id = id,
                                sourceStationId = document.getString("sourceStationId") ?: "",
                                sourceStationName = document.getString("sourceStationName") ?: "",
                                destinationStationId = document.getString("destinationStationId") ?: "",
                                destinationStationName = document.getString("destinationStationName") ?: "",
                                scheduledTimeHour = document.getLong("scheduledTimeHour")?.toInt() ?: 0,
                                scheduledTimeMinute = document.getLong("scheduledTimeMinute")?.toInt() ?: 0,
                                reminderMinutesBefore = document.getLong("reminderMinutesBefore")?.toInt() ?: 15,
                                isRecurring = document.getBoolean("isRecurring") ?: false,
                                recurringDays = document.getString("recurringDays"),
                                isActive = document.getBoolean("isActive") ?: true,
                                createdAt = document.getLong("createdAt") ?: System.currentTimeMillis(),
                                scheduledDate = document.getLong("scheduledDate"),
                                syncState = "SYNCED",
                                deviceId = document.getString("deviceId") ?: "cloud",
                                lastModified = cloudLastModified,
                                isDeleted = cloudIsDeleted // ← Keep deletion state from cloud
                            )

                            appDb.scheduledTripDao().insert(trip)

                        } catch (e: Exception) {
                            Log.e("Sync", "Error downloading scheduled trip: ${e.message}")
                        }
                    }
                }
            }
    }

    private fun getUniqueDeviceId(): String {
        val sharedPrefs = getSharedPreferences("metro_tracker_prefs", MODE_PRIVATE)
        var deviceId = sharedPrefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            sharedPrefs.edit { putString("device_id", deviceId) }
        }

        return deviceId
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        setupGoogleSignIn()
        updateSignInButton()

        // 1. Initialize DB & Repository
        val db = (application as MetroTrackerApplication).database
        repository = MetroRepository(db)
        val appDb = DatabaseInitializer.getDatabase(this)

        // 1. Initialize Database (Load stations from Assets)
        lifecycleScope.launch {
            DatabaseInitializer.initializeStations(this@MainActivity, db)

            val loader = GtfsLoader(applicationContext, appDb)
            loader.loadStopTimesIfNeeded()

            Toast.makeText(this@MainActivity, "Metro data ready!", Toast.LENGTH_SHORT).show()
        }
        if (intent.getBooleanExtra("MOCK_LOCATION_DETECTED", false)) {
            showMockLocationDialog()
        }
        if (auth.currentUser != null) {
            restoreTripsFromCloud()
            restoreScheduledTripsFromCloud()
        }

        val isDarkMode = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("dark_mode", false)

        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode)
                AppCompatDelegate.MODE_NIGHT_YES
            else
                AppCompatDelegate.MODE_NIGHT_NO
        )

        bottomNavigation = findViewById(R.id.bottomNavigation)
        setupBottomNavigation()
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
            bottomNavigation.selectedItemId = R.id.nav_home
        }

        // 3. Setup UI
        requestAllPermissions()
        checkBatteryOptimization()
        //setupRecyclerView()
        setupClickListeners()
        checkActiveTrip()

        // 4. Handle Notification Intent
        handleNotificationIntent(intent)

        if (intent.getBooleanExtra("OPEN_SCHEDULE_DIALOG", false)) {
            val src = intent.getStringExtra("PREFILL_SOURCE")
            val dest = intent.getStringExtra("PREFILL_DEST")
            showStationSelectionDialog(src, dest)
        }
        //updateStartJourneyButton()

    }
//    fun updateStartJourneyButton() {
//        lifecycleScope.launch(Dispatchers.IO) {
//            val db = (application as MetroTrackerApplication).database
//            val activeTrip = db.tripDao().getActiveTrip()
//
//            withContext(Dispatchers.Main) {
//                val btn = findViewById<MaterialButton>(R.id.btnStartJourney)
//
//                if (activeTrip != null) {
//                    // ACTIVE TRIP EXISTS
//                    btn.text = "Go to Active Trip"
//                    btn.setOnClickListener {
//                        val intent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
//                            putExtra("EXTRA_TRIP_ID", activeTrip.id)
//                            putExtra("SOURCE_ID", activeTrip.sourceStationId)
//                            putExtra("DEST_ID", activeTrip.destinationStationId)
//                        }
//                        startActivity(intent)
//                    }
//                } else {
//                    // NO ACTIVE TRIP
//                    btn.text = "Start Your Journey"
//                    btn.setOnClickListener {
//                        showStationSelectionDialog(null, null)
//                    }
//                }
//            }
//        }
//    }
//    override fun onResume() {
//        super.onResume()
//        updateStartJourneyButton()
//    }


    fun openStationSelector() {
        showStationSelectionDialog()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                )
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)

        if (intent.getBooleanExtra("OPEN_SCHEDULE_DIALOG", false)) {
            val src = intent.getStringExtra("PREFILL_SOURCE")
            val dest = intent.getStringExtra("PREFILL_DEST")
            showStationSelectionDialog(src, dest)
        }
    }

    private fun setupGoogleSignIn() {
        val gso = Builder(DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Firebase generates this automatically
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }
    private fun updateSignInButton() {
//        val btnAccount = findViewById<MaterialCardView>(R.id.btnAccount)
//        btnAccount?.let {
//            // TODO: Update UI based on currentUser != null
//        }
    }
    @SuppressLint("UnsafeIntentLaunch")
    private fun performSignOut() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Wipe Data (both trips AND scheduled trips)
            val db = (application as MetroTrackerApplication).database
            db.tripDao().deleteAllTrips()
            db.scheduledTripDao().deleteAll() // Delete scheduled trips too
            getSharedPreferences("MetroPrefs", MODE_PRIVATE).edit { clear() }

            withContext(Dispatchers.Main) {
                // 2. Sign Out Cloud
                auth.signOut()
                googleSignInClient.signOut()
                Toast.makeText(this@MainActivity, "Signed out", Toast.LENGTH_SHORT).show()

                // 3. Restart to clear UI
                val intent = intent
                finish()
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }
    }
    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    Toast.makeText(this, "✅ Signed in as ${user?.email}", Toast.LENGTH_SHORT).show()
                    updateSignInButton()

                    val currentFragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
                    if (currentFragment is AccountFragment) {
                        loadFragment(AccountFragment())
                    }

                    // 🛑 CRITICAL FIX: Wipe "Guest" trips AND scheduled trips before loading "User" data
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = (application as MetroTrackerApplication).database

                        // Delete local "Guest" trips and scheduled trips so they don't mix with the new account
                        db.tripDao().deleteAllTrips()
                        db.scheduledTripDao().deleteAll()

                        // Now download the real user's data
                        withContext(Dispatchers.Main) {
                            restoreTripsFromCloud()
                            restoreScheduledTripsFromCloud()
                        }
                    }
                } else {
                    Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            }
    }
    private fun restoreTripsFromCloud() {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        // Show a loading indicator if possible, or just a toast
        Toast.makeText(this, "Restoring your trips...", Toast.LENGTH_SHORT).show()

        db.collection("users")
            .document(user.uid)
            .collection("trips")
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) return@addOnSuccessListener

                lifecycleScope.launch(Dispatchers.IO) {
                    val appDb = (application as MetroTrackerApplication).database
                    var restoreCount = 0

                    for (document in documents) {
                        try {
                            val id = document.getLong("id") ?: 0L
                            // 1. CHECK FOR DELETION FLAG
                            val isDeleted = document.getBoolean("isDeleted") ?: false

                            // 🛑 STOP: If it's deleted in the cloud, DO NOT restore it locally.
                            if (isDeleted) {
                                Log.d("Restore", "Skipping deleted trip $id")
                                continue
                            }

                            // 2. Check if trip already exists locally
                            val existingTrip = appDb.tripDao().getTripById(id)

                            // 🛑 STOP: If trip exists locally and is deleted, don't overwrite
                            if (existingTrip != null && existingTrip.isDeleted) {
                                Log.d("Restore", "Skipping locally deleted trip $id")
                                continue
                            }

                            val trip = Trip(
                                id = id,
                                sourceStationId = document.getString("sourceStationId") ?: "",
                                sourceStationName = document.getString("sourceStationName") ?: "",
                                destinationStationId = document.getString("destinationStationId") ?: "",
                                destinationStationName = document.getString("destinationStationName") ?: "",
                                metroLine = document.getString("metroLine") ?: "",
                                startTime = Date(document.getLong("startTime") ?: 0L),
                                endTime = document.getLong("endTime")?.let { Date(it) },
                                durationMinutes = document.getLong("durationMinutes")?.toInt(),
                                // ✅ FIX: Safely cast Firestore array
                                visitedStations = (document.get("visitedStations") as? ArrayList<*>)
                                    ?.filterIsInstance<String>() ?: emptyList(),
                                fare = document.getDouble("fare"),
                                status = TripStatus.valueOf(document.getString("status") ?: "COMPLETED"),
                                emergencyContact = document.getString("emergencyContact") ?: "",
                                smsCount = document.getLong("smsCount")?.toInt() ?: 0,
                                createdAt = document.getLong("createdAt")?.let { Date(it) } ?: Date(),
                                notes = document.getString("notes"),
                                hadSosAlert = document.getBoolean("hadSosAlert") ?: false,
                                sosStationName = document.getString("sosStationName"),
                                sosTimestamp = document.getLong("sosTimestamp"),
                                cancellationReason = document.getString("cancellationReason"),
                                // Important: Mark as SYNCED so we don't upload it again
                                syncState = "SYNCED",
                                deviceId = document.getString("deviceId") ?: "restored_device",
                                lastModified = document.getLong("lastModified") ?: System.currentTimeMillis(),
                                isDeleted = false,
                                schemaVersion = document.getLong("schemaVersion")?.toInt() ?: 1
                            )
                            Log.d("Restore", "didnt Skip trip $id")
                            appDb.tripDao().insertTrip(trip)

                            // ✅ NEW: Restore checkpoints
                            val checkpointsData = document.get("checkpoints") as? ArrayList<*>
                            checkpointsData?.forEach { checkpointObj ->
                                try {
                                    val checkpointMap = checkpointObj as? Map<*, *>
                                    if (checkpointMap != null) {
                                        val checkpoint = com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint(
                                            tripId = id,
                                            stationId = checkpointMap["stationId"] as? String ?: "",
                                            stationName = checkpointMap["stationName"] as? String ?: "",
                                            stationOrder = (checkpointMap["stationOrder"] as? Long)?.toInt() ?: 0,
                                            arrivalTime = Date((checkpointMap["arrivalTime"] as? Long) ?: 0L),
                                            departureTime = (checkpointMap["departureTime"] as? Long)?.let { Date(it) },
                                            detectionMethod = com.metro.delhimetrotracker.data.local.database.entities.DetectionMethod.valueOf(
                                                checkpointMap["detectionMethod"] as? String ?: "MANUAL"
                                            ),
                                            confidence = (checkpointMap["confidence"] as? Double)?.toFloat() ?: 1.0f,
                                            smsSent = checkpointMap["smsSent"] as? Boolean ?: false,
                                            smsTimestamp = (checkpointMap["smsTimestamp"] as? Long)?.let { Date(it) },
                                            latitude = checkpointMap["latitude"] as? Double,
                                            longitude = checkpointMap["longitude"] as? Double,
                                            accuracy = (checkpointMap["accuracy"] as? Double)?.toFloat(),
                                            timestamp = checkpointMap["timestamp"] as? Long
                                        )
                                        appDb.stationCheckpointDao().insertCheckpoint(checkpoint)
                                    }
                                } catch (e: Exception) {
                                    Log.e("Restore", "Failed to parse checkpoint: ${e.message}")
                                }
                            }

                            restoreCount++
                        } catch (e: Exception) {
                            Log.e("Restore", "Failed to parse trip: ${e.message}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (restoreCount > 0) {
                            Toast.makeText(this@MainActivity, "Restored trips from Cloud!", Toast.LENGTH_SHORT).show()
                            // Refresh the UI if Dashboard is open?
                            // Since Dashboard uses Flow<List<Trip>>, it will update automatically!
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to restore: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun restoreScheduledTripsFromCloud() {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users")
            .document(user.uid)
            .collection("scheduled_trips")
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) return@addOnSuccessListener

                lifecycleScope.launch(Dispatchers.IO) {
                    val appDb = (application as MetroTrackerApplication).database
                    var restoreCount = 0

                    for (document in documents) {
                        try {
                            val isDeleted = document.getBoolean("isDeleted") ?: false
                            if (isDeleted) continue

                            val scheduledTrip = ScheduledTrip(
                                id = document.getLong("id") ?: 0L,
                                sourceStationId = document.getString("sourceStationId") ?: "",
                                sourceStationName = document.getString("sourceStationName") ?: "",
                                destinationStationId = document.getString("destinationStationId") ?: "",
                                destinationStationName = document.getString("destinationStationName") ?: "",
                                scheduledTimeHour = document.getLong("scheduledTimeHour")?.toInt() ?: 0,
                                scheduledTimeMinute = document.getLong("scheduledTimeMinute")?.toInt() ?: 0,
                                reminderMinutesBefore = document.getLong("reminderMinutesBefore")?.toInt() ?: 30,
                                isRecurring = document.getBoolean("isRecurring") ?: false,
                                recurringDays = document.getString("recurringDays"),
                                isActive = document.getBoolean("isActive") ?: true,
                                createdAt = document.getLong("createdAt") ?: System.currentTimeMillis(),
                                scheduledDate = document.getLong("scheduledDate"),
                                syncState = "SYNCED",
                                deviceId = document.getString("deviceId") ?: "restored_device",
                                lastModified = document.getLong("lastModified") ?: System.currentTimeMillis(),
                                isDeleted = false
                            )

                            val newId = appDb.scheduledTripDao().insert(scheduledTrip)

                            // Re-schedule the alarm for this trip
                            ScheduledTripAlarmManager.scheduleTrip(this@MainActivity, scheduledTrip.copy(id = newId))

                            restoreCount++
                        } catch (e: Exception) {
                            Log.e("Restore", "Failed to parse scheduled trip: ${e.message}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (restoreCount > 0) {
                            Toast.makeText(this@MainActivity, "Restored $restoreCount scheduled trips!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("Restore", "Failed to restore scheduled trips: ${e.message}")
            }
    }

    private fun handleGoogleSignIn() {
        // Just call the dialog function
        showSignInDialog()
    }
    private fun showSignInDialog() {
        // 1. Use BottomSheetDialog (It handles the slide & back button automatically)
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)

        // 2. Load your existing layout
        dialog.setContentView(R.layout.dialog_signin)

        // 3. UI References (Use TextView for the Cancel button!)
        val btnCancel = dialog.findViewById<TextView>(R.id.btnCancel)
        val btnAction = dialog.findViewById<MaterialButton>(R.id.btnAction)
        val tvTitle = dialog.findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSubtitle)

        val user = auth.currentUser

        // --- LOGIC (Same as before) ---
        if (user != null) {
            tvTitle?.text = "Signed in as ${user.displayName}"
            tvSubtitle?.text = user.email
            btnAction?.text = "Sign Out"
            btnAction?.setBackgroundColor(getColor(R.color.accent_red))

            btnAction?.setOnClickListener {
                performSignOut()
                dialog.dismiss()
            }
        } else {
            tvTitle?.text = "Sync Your Trips"
            tvSubtitle?.text = "Sign in to save your travel history."
            btnAction?.text = "Continue with Google"
            btnAction?.setBackgroundColor(getColor(R.color.accent_blue))

            btnAction?.setOnClickListener {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
                dialog.dismiss()
            }
        }

        // This now works perfectly with the Back Button animation logic
        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
    private fun checkActiveTrip() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Query DB for any trip with status 'IN_PROGRESS'
            val activeTrip = (application as MetroTrackerApplication).database.tripDao()
                .getTripsByStatus(TripStatus.IN_PROGRESS).firstOrNull()?.firstOrNull()

            if (activeTrip != null) {
                withContext(Dispatchers.Main) {
                    val intent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
                        putExtra("EXTRA_TRIP_ID", activeTrip.id)
                        // If your Trip entity stores Source/Dest IDs, pass them too:
                        putExtra("SOURCE_ID", activeTrip.sourceStationId)
                        putExtra("DEST_ID", activeTrip.destinationStationId)
                    }
                    startActivity(intent)
                    // Optional: finish() // If you want to close Main so back button exits app
                }
            }
        }
    }
    private fun showMockLocationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Security Alert")
            .setMessage("Fake GPS/Mock location was detected during your journey.\n\nYour emergency contact has been notified and the trip has been terminated for safety reasons.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
    private fun requestAllPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow Background Tracking")
                .setMessage("To receive SOS and Station alerts while the screen is off, the app needs to run in the background.\n\nPlease tap 'Allow' in the next popup.")
                .setPositiveButton("OK") { _, _ ->
                    try {
                        // This intent opens a specific "Allow / Deny" dialog for YOUR app
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.data = "package:$packageName".toUri()
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback to the general settings list if the direct dialog fails
                        val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("No Thanks", null)
                .show()
        }
    }
    private fun setupClickListeners() {
//        findViewById<View>(R.id.btnStartJourney).setOnClickListener {
//            showStationSelectionDialog()
//        }
//        findViewById<View>(R.id.btnAccount).setOnClickListener {
//            handleGoogleSignIn()
//        }
//        findViewById<MaterialCardView>(R.id.btnAppInfo).setOnClickListener {
//            openAppGuide()
//        }
//        findViewById<MaterialCardView>(R.id.btnViewDashboard)?.setOnClickListener {
//            openDashboard()
//        }
        findViewById<MaterialToolbar>(R.id.toolbar)?.apply {
            inflateMenu(R.menu.main_menu)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.action_settings -> {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
        }
    }
    private fun openAppGuide() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_left,
                android.R.anim.fade_out,
                android.R.anim.fade_in,
                R.anim.slide_out_left
            )
            .replace(android.R.id.content, AppGuideFragment())
            .addToBackStack(null)
            .commit()
    }
    private fun openDashboard() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            .replace(android.R.id.content, com.metro.delhimetrotracker.ui.dashboard.DashboardFragment())
            .addToBackStack(null)
            .commit()
    }
    // --- MAIN DIALOG FUNCTION ---
    fun showStationSelectionDialog(prefilledSource: String? = null, prefilledDest: String? = null) {
        val prefs = getSharedPreferences("MetroPrefs", MODE_PRIVATE)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_station_selector)
        dialog.window?.attributes?.windowAnimations = R.style.DialogSlideAnimation

        // --- UI References ---
        val sourceAtv = dialog.findViewById<AutoCompleteTextView>(R.id.sourceAutoComplete)
        val destAtv = dialog.findViewById<AutoCompleteTextView>(R.id.destinationAutoComplete)
        val btnSwap = dialog.findViewById<MaterialButton>(R.id.btnSwapStations)
        val routePreferenceGroup = dialog.findViewById<RadioGroup>(R.id.routePreferenceGroup)

        // SMS & Phone
//        val cbSms = dialog.findViewById<MaterialCheckBox>(R.id.cbEnableSms)
//        val layoutPhone = dialog.findViewById<LinearLayout>(R.id.layoutPhoneInput)
//        val phoneEt = dialog.findViewById<TextInputEditText>(R.id.etPhoneNumber)
//        val btnPickContact = dialog.findViewById<MaterialButton>(R.id.btnPickContact)

        // Schedule Logic
        val switchSchedule = dialog.findViewById<SwitchMaterial>(R.id.switchSchedule)
        val layoutScheduleOptions = dialog.findViewById<LinearLayout>(R.id.layoutScheduleOptions)
        val tvSelectTime = dialog.findViewById<TextView>(R.id.tvSelectTime)
        val radioGroupFreq = dialog.findViewById<RadioGroup>(R.id.radioGroupFrequency)
        val layoutDays = dialog.findViewById<LinearLayout>(R.id.layoutDays)

        // Bottom Actions
        val btnStart = dialog.findViewById<MaterialButton>(R.id.btnStart)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val toolbar = dialog.findViewById<MaterialToolbar>(R.id.toolbar)

        // --- 1. SETUP DEFAULTS ---
        prefilledSource?.let { sourceAtv.setText(it) }
        prefilledDest?.let { destAtv.setText(it) }
        //currentDialogPhoneField = phoneEt
        //phoneEt.setText(prefs.getString("last_phone", ""))

        // --- 2. LOAD STATIONS FOR AUTOCOMPLETE ---
        lifecycleScope.launch(Dispatchers.IO) {
            val stations = repository.getAllStations()
// Add .distinct() to remove duplicates
            val stationNames = stations.map { it.stationName }.distinct().sorted()
            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, stationNames)
                sourceAtv.setAdapter(adapter)
                destAtv.setAdapter(adapter)
            }

            // --- BUTTON LOGIC INSIDE SCOPE TO ACCESS STATIONS LIST ---
            btnStart.setOnClickListener {
                val sourceName = sourceAtv.text.toString()
                val destName = destAtv.text.toString()

                if (sourceName.isEmpty() || destName.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Select Both Stations!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (sourceName == destName) {
                    Toast.makeText(this@MainActivity, "Source and Destination cannot be same.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                if (switchSchedule.isChecked) {
                    // === SCHEDULE MODE ===
                    val timeText = tvSelectTime.text.toString()
                    if (timeText.contains("Select Time")) {
                        Toast.makeText(this@MainActivity, "Please select a time", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    val sStation = stations.find { it.stationName == sourceName }
                    val dStation = stations.find { it.stationName == destName }

                    if (sStation != null && dStation != null) {
                        // We will save using a helper function below, extracting data from UI
                        performScheduleSave(dialog, sStation.stationId, sStation.stationName, dStation.stationId, dStation.stationName)
                    }
                } else {
                    // === INSTANT MODE ===
                    // First, check if there's already an active trip
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = (application as MetroTrackerApplication).database
                        val activeTrip = db.tripDao().getActiveTrip()

                        if (activeTrip != null) {
                            // Active trip exists - show dialog instead of creating new trip
                            withContext(Dispatchers.Main) {
                                MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle("⚠️ Active Trip in Progress")
                                    .setMessage("You have an active trip from ${activeTrip.sourceStationName} to ${activeTrip.destinationStationName}.\n\nPlease complete or cancel your current trip before starting a new one.")
                                    .setPositiveButton("Go to Active Trip") { _, _ ->
                                        dialog.dismiss()
                                        val intent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
                                            putExtra("EXTRA_TRIP_ID", activeTrip.id)
                                            putExtra("SOURCE_ID", activeTrip.sourceStationId)
                                            putExtra("DEST_ID", activeTrip.destinationStationId)
                                        }
                                        startActivity(intent)
                                    }
                                    .setNegativeButton("Cancel", null)
                                    .show()
                            }
                            return@launch
                        }
                        val settings = db.userSettingsDao().getUserSettingsOnce()
                        val emergencyContact = settings?.emergencyContact ?: ""

                        // Check if emergency contact is set
                        if (emergencyContact.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Please set emergency contact in Settings first",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            return@launch
                        }
                        // No active trip - proceed with validation and trip creation
                        withContext(Dispatchers.Main) {
//                            var phone = ""
//                            if (cbSms.isChecked) {
//                                val rawPhone = phoneEt.text.toString().replace("\\s".toRegex(), "")
//                                if (rawPhone.length != 10 || !rawPhone.all { it.isDigit() }) {
//                                    Toast.makeText(this@MainActivity, "Enter valid 10-digit number!", Toast.LENGTH_SHORT).show()
//                                    return@withContext
//                                }
//                                phone = rawPhone
//                                prefs.edit { putString("last_phone", phone) }
//                            }

                            val selectedPreference = when (routePreferenceGroup.checkedRadioButtonId) {
                                R.id.rbLeastInterchanges -> RoutePlanner.RoutePreference.LEAST_INTERCHANGES
                                else -> RoutePlanner.RoutePreference.SHORTEST_PATH
                            }
                            dialog.dismiss()

                            // CALLING THE RESTORED FUNCTION
                            createNewTripAndStartService(sourceName, destName, emergencyContact, selectedPreference)
                        }
                    }
                }
            }
        }

        // --- 3. SWAP BUTTON ---
        btnSwap.setOnClickListener {
            val currentSource = sourceAtv.text.toString()
            val currentDest = destAtv.text.toString()
            sourceAtv.setText(currentDest, false)
            destAtv.setText(currentSource, false)
            btnSwap.animate().rotationBy(180f).setDuration(300).start()
        }

        // --- 4. SCHEDULE TOGGLE & TIME ---
        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            layoutScheduleOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            btnStart.text = if (isChecked) "SCHEDULE" else "START"
        }

        tvSelectTime.setOnClickListener {
            val cal = Calendar.getInstance()
            TimePickerDialog(this, { _, hourOfDay, minute ->
                // Save these to tag or global var if needed, here we update text
                tvSelectTime.tag = "$hourOfDay:$minute" // Storing time in tag for simple retrieval
                val amPm = if (hourOfDay >= 12) "PM" else "AM"
                val displayHour = if (hourOfDay % 12 == 0) 12 else hourOfDay % 12
                tvSelectTime.text = String.format("%02d:%02d %s", displayHour, minute, amPm)
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()
        }

        radioGroupFreq.setOnCheckedChangeListener { _, checkedId ->
            layoutDays.visibility = if (checkedId == R.id.radioRecurring) View.VISIBLE else View.GONE
        }

        // --- 5. OTHER LISTENERS ---
//        cbSms.setOnCheckedChangeListener { _, isChecked ->
//            layoutPhone.visibility = if (isChecked) View.VISIBLE else View.GONE
//        }
//        btnPickContact.setOnClickListener { contactPickerLauncher.launch(null) }
        toolbar.setNavigationOnClickListener { dialog.dismiss() }
        btnCancel.setOnClickListener { dialog.dismiss() }

        // --- 6. RECHARGE BUTTON ---
        val btnRecharge = dialog.findViewById<MaterialButton>(R.id.btnRechargeCard)
        val sarthiPackage = "com.sraoss.dmrc"
        if (isAppInstalled(sarthiPackage)) {
            btnRecharge.text = "Recharge via Sarthi App"
            btnRecharge.setIconResource(android.R.drawable.ic_menu_send)
        } else {
            btnRecharge.text = "Install DMRC Sarthi"
            btnRecharge.setIconResource(android.R.drawable.stat_sys_download)
        }
        btnRecharge.setOnClickListener {
            if (isAppInstalled(sarthiPackage)) {
                val intent = packageManager.getLaunchIntentForPackage(sarthiPackage)
                startActivity(intent)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$sarthiPackage".toUri()))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$sarthiPackage".toUri()))
                }
            }
        }

        dialog.show()
    }

    // --- RESTORED: TRIP CREATION LOGIC ---
    fun createNewTripAndStartService(
        sourceName: String,
        destName: String,
        phone: String,
        routePreference: RoutePlanner.RoutePreference = RoutePlanner.RoutePreference.SHORTEST_PATH
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = (application as MetroTrackerApplication).database

                val tripDao = db.tripDao()

                // Safety check - this should not happen as we check before calling this function
                // But keeping it as a failsafe
                val activeTrip = tripDao.getActiveTrip()

                if (activeTrip != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "⚠️ Active trip already exists", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val sourceStation = db.metroStationDao().searchStations(sourceName).first().firstOrNull()
                val destStation = db.metroStationDao().searchStations(destName).first().firstOrNull()

                if (sourceStation == null || destStation == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Station not found!", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Create Trip object and insert into database
                val newTrip = Trip(
                    sourceStationId = sourceStation.stationId,
                    sourceStationName = sourceStation.stationName,
                    destinationStationId = destStation.stationId,
                    destinationStationName = destStation.stationName,
                    metroLine = sourceStation.metroLine,
                    startTime = Date(),
                    visitedStations = listOf(sourceStation.stationId),
                    emergencyContact = phone,
                    status = TripStatus.IN_PROGRESS,
                    deviceId = getOrCreateDeviceId(),
                    syncState = "PENDING",
                    lastModified = System.currentTimeMillis()
                )
                val tripId = db.tripDao().insertTrip(newTrip)

                withContext(Dispatchers.Main) {
                    // 1. Start Foreground Service for journey tracking
                    val serviceIntent = Intent(this@MainActivity, JourneyTrackingService::class.java).apply {
                        action = JourneyTrackingService.ACTION_START_JOURNEY
                        putExtra(JourneyTrackingService.EXTRA_TRIP_ID, tripId)
                        putExtra("ROUTE_PREFERENCE", routePreference.name)
                    }
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)

                    // 2. Navigate to tracking screen
                    val trackingIntent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
                        putExtra("EXTRA_TRIP_ID", tripId)
                        putExtra("ROUTE_PREFERENCE", routePreference.name)
                    }
                    startActivity(trackingIntent)

                    Toast.makeText(this@MainActivity, "Trip started!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("MainActivity", "Failed to start trip", e)
                }
            }
        }
    }

    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit { putString("device_id", deviceId) }
        }
        return deviceId
    }

    // --- HELPER: SCHEDULE SAVING ---
    fun performScheduleSave(dialog: Dialog, sId: String, sName: String, dId: String, dName: String) {
        val tvSelectTime = dialog.findViewById<TextView>(R.id.tvSelectTime)
        val timeTag = tvSelectTime.tag as? String ?: return
        val timeParts = timeTag.split(":")
        val hour = timeParts[0].toInt()
        val min = timeParts[1].toInt()

        val radioGroupFreq = dialog.findViewById<RadioGroup>(R.id.radioGroupFrequency)

        val isRecurring = (radioGroupFreq.checkedRadioButtonId == R.id.radioRecurring)
        val daysList = mutableListOf<String>()
        if (isRecurring) {
            if (dialog.findViewById<CheckBox>(R.id.checkMon).isChecked) daysList.add("MON")
            if (dialog.findViewById<CheckBox>(R.id.checkTue).isChecked) daysList.add("TUE")
            if (dialog.findViewById<CheckBox>(R.id.checkWed).isChecked) daysList.add("WED")
            if (dialog.findViewById<CheckBox>(R.id.checkThu).isChecked) daysList.add("THU")
            if (dialog.findViewById<CheckBox>(R.id.checkFri).isChecked) daysList.add("FRI")
            if (dialog.findViewById<CheckBox>(R.id.checkSat).isChecked) daysList.add("SAT")
            if (dialog.findViewById<CheckBox>(R.id.checkSun).isChecked) daysList.add("SUN")
        }
        val reminderMin = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("default_reminder_minutes", 30)

        saveScheduledTrip(0, sId, sName, dId, dName, hour, min,reminderMin,isRecurring, daysList.joinToString(","))
        dialog.dismiss()
    }

    private fun saveScheduledTrip(
        id: Long, sId: String, sName: String, dId: String, dName: String,
        hour: Int, min: Int,reminder: Int, isRecurring: Boolean, recurringDays: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Calculate scheduledDate for one-time trips
            val scheduledDate = if (!isRecurring) {
                val calendar = Calendar.getInstance()
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, min)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)

                // If the time has already passed today, schedule for tomorrow
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                calendar.timeInMillis
            } else {
                null
            }

            val trip = ScheduledTrip(
                id = id,
                sourceStationId = sId,
                sourceStationName = sName,
                destinationStationId = dId,
                destinationStationName = dName,
                scheduledTimeHour = hour,
                scheduledTimeMinute = min,
                reminderMinutesBefore = reminder,
                isRecurring = isRecurring,
                recurringDays = recurringDays,
                isActive = true,
                scheduledDate = scheduledDate
            )

            val dao = (application as MetroTrackerApplication).database.scheduledTripDao()
            val newId = if (id == 0L) dao.insert(trip) else { dao.update(trip); id }

            ScheduledTripAlarmManager.scheduleTrip(this@MainActivity, trip.copy(id = newId))

            // Upload to cloud if user is signed in
            withContext(Dispatchers.Main) {
                if (auth.currentUser != null) {
                    uploadScheduledTripsToCloud()
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Trip Scheduled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val tripId = intent?.getLongExtra("START_TRIP_ID", -1L) ?: -1L
        if (tripId != -1L) {
            Log.d("MAIN", "Started from notification for trip $tripId")
            // Navigate to tracking page with the trip ID
            lifecycleScope.launch(Dispatchers.IO) {
                val db = (application as MetroTrackerApplication).database
                val trip = db.tripDao().getTripById(tripId)

                if (trip != null) {
                    withContext(Dispatchers.Main) {
                        val trackingIntent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
                            putExtra("EXTRA_TRIP_ID", trip.id)
                            putExtra("SOURCE_ID", trip.sourceStationId)
                            putExtra("DEST_ID", trip.destinationStationId)
                        }
                        startActivity(trackingIntent)
                    }
                }
            }
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun uploadScheduledTripsToCloud() {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        lifecycleScope.launch(Dispatchers.IO) {
            val appDb = (application as MetroTrackerApplication).database
            val pendingTrips = appDb.scheduledTripDao().getPendingScheduledTrips().first()

            for (trip in pendingTrips) {
                try {
                    val tripData = hashMapOf(
                        "id" to trip.id,
                        "sourceStationId" to trip.sourceStationId,
                        "sourceStationName" to trip.sourceStationName,
                        "destinationStationId" to trip.destinationStationId,
                        "destinationStationName" to trip.destinationStationName,
                        "scheduledTimeHour" to trip.scheduledTimeHour,
                        "scheduledTimeMinute" to trip.scheduledTimeMinute,
                        "reminderMinutesBefore" to trip.reminderMinutesBefore,
                        "isRecurring" to trip.isRecurring,
                        "recurringDays" to trip.recurringDays,
                        "isActive" to trip.isActive,
                        "createdAt" to trip.createdAt,
                        "scheduledDate" to trip.scheduledDate,
                        "deviceId" to trip.deviceId,
                        "lastModified" to trip.lastModified,
                        "isDeleted" to trip.isDeleted
                    )

                    db.collection("users")
                        .document(user.uid)
                        .collection("scheduled_trips")
                        .document(trip.id.toString())
                        .set(tripData)
                        .addOnSuccessListener {
                            lifecycleScope.launch(Dispatchers.IO) {
                                appDb.scheduledTripDao().updateSyncStatus(trip.id, "SYNCED")
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.e("Sync", "Failed to upload scheduled trip ${trip.id}: ${e.message}")
                        }
                } catch (e: Exception) {
                    Log.e("Sync", "Error preparing scheduled trip ${trip.id}: ${e.message}")
                }
            }
        }
    }
    fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        googleSignInLauncher.launch(signInIntent)
    }

    fun signOut() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database

            // 1. CLEAR LOCAL DATA
            db.tripDao().deleteAllTrips()
            db.scheduledTripDao().deleteAll()

            // Optional: clear prefs
            getSharedPreferences("MetroPrefs", MODE_PRIVATE).edit { clear() }

            withContext(Dispatchers.Main) {
                // 2. SIGN OUT FROM AUTH
                auth.signOut()
                googleSignInClient.signOut()

                Toast.makeText(this@MainActivity, "Signed out", Toast.LENGTH_SHORT).show()

                // 3. REFRESH ACCOUNT SCREEN
                val currentFragment =
                    supportFragmentManager.findFragmentById(R.id.fragmentContainer)

                if (currentFragment is AccountFragment) {
                    loadFragment(AccountFragment())
                }
            }
        }
    }
    fun showAccountBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        dialog.setContentView(R.layout.activity_login)
        dialog.show()
        dialog.setContentView(R.layout.activity_login)

        val tvTitle = dialog.findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvSubtitle)
        val btnAction = dialog.findViewById<MaterialButton>(R.id.btnAction)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val btnClose = dialog.findViewById<ImageView>(R.id.btnClose)

        val user = FirebaseAuth.getInstance().currentUser

        if (user != null) {
            // 🔹 SIGNED IN → LOGOUT CONFIRMATION
            tvTitle?.text = "Sign out?"
            tvSubtitle?.text = "Your trips will be removed from this device."
            btnAction?.text = "Sign Out"

            btnAction?.setOnClickListener {
                dialog.dismiss()
                signOut()
            }
        } else {
            // 🔹 SIGNED OUT → SIGN IN
            tvTitle?.text = "Sign in"
            tvSubtitle?.text = "Sync your trips and preferences"
            btnAction?.text = "Continue with Google"

            btnAction?.setOnClickListener {
                dialog.dismiss()
                signInWithGoogle()
            }
        }

        // ✅ Cancel button slides sheet down
        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        // ❌ Disable close (X) completely
        btnClose?.visibility = View.GONE

        dialog.show()
    }


}

// ===== HOME FRAGMENT (embedded in MainActivity.kt) =====
class HomeFragment : Fragment() {

    private var startJourneyBtn: MaterialButton? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)

        startJourneyBtn = view.findViewById(R.id.btnStartJourney)

        startJourneyBtn?.setOnClickListener {
            handleStartJourneyClick()
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        refreshStartJourneyButton()
    }

    private fun refreshStartJourneyButton() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (requireActivity().application as MetroTrackerApplication).database
            val activeTrip = db.tripDao().getActiveTrip()

            withContext(Dispatchers.Main) {
                startJourneyBtn?.text =
                    if (activeTrip != null) "Go to Active Trip"
                    else "Start Your Journey"
            }
        }
    }

    private fun handleStartJourneyClick() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (requireActivity().application as MetroTrackerApplication).database
            val activeTrip = db.tripDao().getActiveTrip()

            withContext(Dispatchers.Main) {
                if (activeTrip != null) {
                    val intent = Intent(requireContext(), TrackingActivity::class.java).apply {
                        putExtra("EXTRA_TRIP_ID", activeTrip.id)
                        putExtra("SOURCE_ID", activeTrip.sourceStationId)
                        putExtra("DEST_ID", activeTrip.destinationStationId)
                    }
                    startActivity(intent)
                } else {
                    (requireActivity() as MainActivity).openStationSelector()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        startJourneyBtn = null // 🚨 prevents memory leak
    }
}


// ===== ACCOUNT FRAGMENT (embedded in MainActivity.kt) =====
class AccountFragment : Fragment() {
    private lateinit var cardSignIn: MaterialCardView
    private lateinit var cardSettings: MaterialCardView
    private lateinit var cardAppInfo: MaterialCardView
    private lateinit var tvSignInStatus: TextView
    private lateinit var tvUserEmail: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_account, container, false)

        // Initialize views
        cardSignIn = view.findViewById(R.id.cardSignIn)
        cardSettings = view.findViewById(R.id.cardSettings)
        cardAppInfo = view.findViewById(R.id.cardAppInfo)
        tvSignInStatus = view.findViewById(R.id.tvSignInStatus)
        tvUserEmail = view.findViewById(R.id.tvUserEmail)

        // Update UI based on sign-in status
        updateSignInUI()

        // Set click listeners
        cardSignIn.setOnClickListener {
            val activity = requireActivity() as MainActivity
            if (isUserSignedIn()) {
                activity.showAccountBottomSheet()
            } else {
                activity.signInWithGoogle()
            }
        }




        cardSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        cardAppInfo.setOnClickListener {
            // Open App Guide Fragment
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AppGuideFragment())
                .addToBackStack(null)
                .commit()
        }

        return view
    }
    override fun onResume() {
        super.onResume()
        updateSignInUI()
    }


    private fun updateSignInUI() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            tvSignInStatus.text = "Signed in as"
            tvUserEmail.text = user.email ?: "No email"
        } else {
            tvSignInStatus.text = "Sign in with Google"
            tvUserEmail.text = "Backup your trips to the cloud"
        }
    }

    private fun isUserSignedIn(): Boolean {
        return FirebaseAuth.getInstance().currentUser != null
    }
}
class AppGuideFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_app_guide, container, false)
//        view.findViewById<Button>(R.id.btnCloseGuide).setOnClickListener {
//            parentFragmentManager.popBackStack()
//        }
        return view
    }
}