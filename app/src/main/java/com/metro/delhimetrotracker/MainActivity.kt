package com.metro.delhimetrotracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.material.card.MaterialCardView
import android.os.Build
import kotlinx.coroutines.tasks.await
import android.widget.Button
import android.os.Bundle
import android.util.Log
import android.os.PowerManager
import android.provider.ContactsContract
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import com.metro.delhimetrotracker.data.local.database.entities.TripStatus
import com.metro.delhimetrotracker.data.repository.DatabaseInitializer
import com.metro.delhimetrotracker.service.JourneyTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import com.google.android.material.button.MaterialButton
import android.app.Dialog
import com.google.android.material.appbar.MaterialToolbar
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.RadioGroup
import com.metro.delhimetrotracker.data.repository.RoutePlanner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.common.api.ApiException
import com.google.firebase.firestore.FirebaseFirestore
import android.net.Uri
import com.metro.delhimetrotracker.data.repository.GtfsLoader
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.CheckBox
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import com.metro.delhimetrotracker.data.repository.MetroRepository
import com.metro.delhimetrotracker.receivers.ScheduledTripAlarmManager
import java.util.Calendar
import kotlin.collections.find
import android.app.TimePickerDialog
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.firstOrNull
import kotlin.collections.map

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var repository: MetroRepository
    private val auth = FirebaseAuth.getInstance()

    // Global reference for contact picker result
    private var currentDialogPhoneField: TextInputEditText? = null

    // Multiple permissions handling logic
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

    private val contactPickerLauncher = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri?.let { contactUri ->
            val cursor = contentResolver.query(contactUri, null, null, null, null)
            if (cursor?.moveToFirst() == true) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID))
                val hasPhone = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER))
                if (hasPhone == "1") {
                    val phones = contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = " + id, null, null
                    )
                    phones?.moveToFirst()
                    val rawNumber = phones?.getString(phones.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))

                    // ✅ Smart extraction: Handle +91 and regular numbers
                    val cleanedNumber = extractIndianMobileNumber(rawNumber)
                    currentDialogPhoneField?.setText(cleanedNumber)

                    phones?.close()
                }
            }
            cursor?.close()
        }
    }

    private fun extractIndianMobileNumber(rawNumber: String?): String {
        if (rawNumber.isNullOrEmpty()) return ""

        // Step 1: Remove all non-digits except +
        val cleaned = rawNumber.replace(Regex("[^0-9+]"), "")

        // Step 2: Apply extraction logic
        val result = when {
            // +91XXXXXXXXXX -> XXXXXXXXXX
            cleaned.startsWith("+91") -> cleaned.substring(3).take(10)

            // 91XXXXXXXXXX -> XXXXXXXXXX (12 digits starting with 91)
            cleaned.startsWith("91") && cleaned.length > 11 -> cleaned.substring(2).take(10)

            // 0XXXXXXXXXX -> XXXXXXXXXX (11 digits starting with 0)
            cleaned.startsWith("0") && cleaned.length == 11 -> cleaned.substring(1)

            // XXXXXXXXXX -> XXXXXXXXXX (exactly 10 digits)
            cleaned.length == 10 && !cleaned.startsWith("+") -> cleaned

            // Fallback: Just remove + and take up to 10 digits
            else -> cleaned.replace("+", "").take(10)
        }
        return result
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

    // ===== HELPER: SYNC SINGLE DELETED TRIP TO CLOUD =====
    private suspend fun syncDeletedTripToCloud(tripId: Long) {
        val user = auth.currentUser ?: return
        val db = FirebaseFirestore.getInstance()
        val appDb = (application as MetroTrackerApplication).database

        try {
            // Upload tombstone to cloud
            db.collection("users")
                .document(user.uid)
                .collection("trips")
                .document(tripId.toString())
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
    fun performManualSync(onComplete: () -> Unit) {
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
                            val localTrip = appDb.tripDao().getTripById(id)

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
                                isDeleted = cloudIsDeleted, // ← Keep the deletion state from cloud
                                schemaVersion = document.getLong("schemaVersion")?.toInt() ?: 1
                            )

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
            sharedPrefs.edit().putString("device_id", deviceId).apply()
        }

        return deviceId
    }

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
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Firebase generates this automatically
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }
    private fun updateSignInButton() {
        val btnAccount = findViewById<MaterialCardView>(R.id.btnAccount)
        btnAccount?.let {
            // TODO: Update UI based on currentUser != null
        }
    }
    private fun performSignOut() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Wipe Data (both trips AND scheduled trips)
            val db = (application as MetroTrackerApplication).database
            db.tripDao().deleteAllTrips()
            db.scheduledTripDao().deleteAll() // Delete scheduled trips too
            getSharedPreferences("MetroPrefs", MODE_PRIVATE).edit().clear().apply()

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
                            // 1. CHECK FOR DELETION FLAG
                            val isDeleted = document.getBoolean("isDeleted") ?: false

                            // 🛑 STOP: If it's deleted in the cloud, DO NOT restore it locally.
                            if (isDeleted) continue
                            // Manually map Firestore fields back to Trip Entity
                            // We do this manually to ensure Types (Long vs Int, Date vs Timestamp) match perfectly
                            val id = document.getLong("id") ?: 0L
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
                        putExtra("TRIP_ID", activeTrip.id)
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
                        intent.data = Uri.parse("package:$packageName")
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

    //    private fun setupRecyclerView() {
//        val rv = findViewById<RecyclerView>(R.id.rvScheduledTrips)
//
//        scheduledTripAdapter = ScheduledTripAdapter(
//            onStartClick = { trip ->
//                // Clicking the card starts the station selection with pre-filled data
//                showStationSelectionDialog(trip.sourceStationName, trip.destinationStationName)
//            },
//            onEdit = { trip ->
//                // Clicking Edit opens the same dialog (logic handles pre-filling)
//                showStationSelectionDialog(trip.sourceStationName, trip.destinationStationName)
//            },
//            onDelete = { trip ->
//                deleteTrip(trip)
//            }
//        )
//
//        rv.layoutManager = LinearLayoutManager(this)
//        rv.adapter = scheduledTripAdapter
//    }
    private fun setupClickListeners() {
        findViewById<View>(R.id.btnStartJourney).setOnClickListener {
            showStationSelectionDialog()
        }
        findViewById<View>(R.id.btnAccount).setOnClickListener {
            handleGoogleSignIn()
        }
        findViewById<MaterialCardView>(R.id.btnAppInfo).setOnClickListener {
            openAppGuide()
        }
        findViewById<MaterialCardView>(R.id.btnViewDashboard)?.setOnClickListener {
            openDashboard()
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
        val cbSms = dialog.findViewById<MaterialCheckBox>(R.id.cbEnableSms)
        val layoutPhone = dialog.findViewById<LinearLayout>(R.id.layoutPhoneInput)
        val phoneEt = dialog.findViewById<TextInputEditText>(R.id.etPhoneNumber)
        val btnPickContact = dialog.findViewById<MaterialButton>(R.id.btnPickContact)

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
        currentDialogPhoneField = phoneEt
        phoneEt.setText(prefs.getString("last_phone", ""))

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

                        // No active trip - proceed with validation and trip creation
                        withContext(Dispatchers.Main) {
                            var phone = ""
                            if (cbSms.isChecked) {
                                val rawPhone = phoneEt.text.toString().replace("\\s".toRegex(), "")
                                if (rawPhone.length != 10 || !rawPhone.all { it.isDigit() }) {
                                    Toast.makeText(this@MainActivity, "Enter valid 10-digit number!", Toast.LENGTH_SHORT).show()
                                    return@withContext
                                }
                                phone = rawPhone
                                prefs.edit().putString("last_phone", phone).apply()
                            }

                            val selectedPreference = when (routePreferenceGroup.checkedRadioButtonId) {
                                R.id.rbLeastInterchanges -> RoutePlanner.RoutePreference.LEAST_INTERCHANGES
                                else -> RoutePlanner.RoutePreference.SHORTEST_PATH
                            }
                            dialog.dismiss()

                            // CALLING THE RESTORED FUNCTION
                            createNewTripAndStartService(sourceName, destName, phone, selectedPreference)
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
        cbSms.setOnCheckedChangeListener { _, isChecked ->
            layoutPhone.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        btnPickContact.setOnClickListener { contactPickerLauncher.launch(null) }
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
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$sarthiPackage")))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$sarthiPackage")))
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
            prefs.edit().putString("device_id", deviceId).apply()
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

        val spinnerReminder = dialog.findViewById<Spinner>(R.id.spinnerReminder)
        val radioGroupFreq = dialog.findViewById<RadioGroup>(R.id.radioGroupFrequency)

        val reminderText = spinnerReminder.selectedItem?.toString() ?: "10"
        val reminderMin = reminderText.filter { it.isDigit() }.toIntOrNull() ?: 10

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

        saveScheduledTrip(0, sId, sName, dId, dName, hour, min, reminderMin, isRecurring, daysList.joinToString(","))
        dialog.dismiss()
    }

    private fun saveScheduledTrip(
        id: Long, sId: String, sName: String, dId: String, dName: String,
        hour: Int, min: Int, reminder: Int, isRecurring: Boolean, recurringDays: String
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
}


class AppGuideFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_app_guide, container, false)
        view.findViewById<Button>(R.id.btnCloseGuide).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        return view
    }
}