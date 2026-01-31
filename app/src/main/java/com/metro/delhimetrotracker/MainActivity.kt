package com.metro.delhimetrotracker.ui


import android.view.animation.AccelerateDecelerateInterpolator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.animation.AnimationUtils
import com.google.android.material.card.MaterialCardView
import com.metro.delhimetrotracker.utils.applyTransition
import android.os.Build
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
import android.view.animation.Animation
import com.metro.delhimetrotracker.data.repository.RoutePlanner
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import android.view.Window
import com.google.android.gms.common.api.ApiException
import com.google.firebase.firestore.FirebaseFirestore
import android.net.Uri
import com.metro.delhimetrotracker.data.repository.GtfsLoader
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
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

    // Contact picker for SMS alert feature
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

        // Optional: Log for debugging
        // Log.d("PhoneExtraction", "Raw: $rawNumber -> Cleaned: $cleaned -> Result: $result")

        return result
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupGoogleSignIn()
        updateSignInButton()
        if (intent.getBooleanExtra("MOCK_LOCATION_DETECTED", false)) {
            showMockLocationDialog()
        }
        val appDb = DatabaseInitializer.getDatabase(this)

        // 1. Initialize Database (Load stations from Assets)
        lifecycleScope.launch {
            val db = (application as MetroTrackerApplication).database
            DatabaseInitializer.initializeStations(this@MainActivity, db)

            // Optional: Clean up any stale trips on first app launch (for debugging)
            // You can comment this out in production
            cleanupStaleTrips()

            val loader = GtfsLoader(applicationContext, appDb)
            loader.loadStopTimesIfNeeded()

            Toast.makeText(this@MainActivity, "Metro data ready!", Toast.LENGTH_SHORT).show()
        }

        // 2. Start button click listener - Check for active trip first
        findViewById<Button>(R.id.btnStartJourney).setOnClickListener {
            checkForActiveTripBeforeStarting()
        }

// 2. Dashboard Button (Safe call ?. preserved)
        findViewById<MaterialCardView>(R.id.btnViewDashboard)?.setOnClickListener {
            openDashboard()
        }

// 3. App Info Button
        findViewById<MaterialCardView>(R.id.btnAppInfo).setOnClickListener {
            openAppGuide()
        }

// 4. Account/Sign-In Button (Safe call ?. preserved)
        findViewById<MaterialCardView>(R.id.btnAccount)?.setOnClickListener {
            handleGoogleSignIn()
        }
        requestAllPermissions()
        checkBatteryOptimization() // Check and show battery optimization dialog
        if (auth.currentUser != null) {
            restoreTripsFromCloud()
        }
    }

    private fun setupGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id)) // Firebase generates this automatically
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)
    }
    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    Toast.makeText(this, "✅ Signed in as ${user?.email}", Toast.LENGTH_SHORT).show()
                    updateSignInButton()

                    // 🛑 CRITICAL FIX: Wipe "Guest" trips before loading "User" trips
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = (application as MetroTrackerApplication).database

                        // Delete local "Guest" trips so they don't mix with the new account
                        db.tripDao().deleteAllTrips()

                        // Now download the real user's data
                        withContext(Dispatchers.Main) {
                            restoreTripsFromCloud()
                        }
                    }
                } else {
                    Toast.makeText(this, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
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
        val btnAction = dialog.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAction)
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

    // Keep your logout logic separate for cleanliness
    private fun performSignOut() {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Wipe Data
            val db = (application as MetroTrackerApplication).database
            db.tripDao().deleteAllTrips()
            getSharedPreferences("MetroPrefs", Context.MODE_PRIVATE).edit().clear().apply()

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
    private fun updateSignInButton() {
        val btnAccount = findViewById<MaterialCardView>(R.id.btnAccount)
        val currentUser = auth.currentUser

        // Update button text/icon based on sign-in state
        // You'll need to add this button to your layout
        btnAccount?.let {
            // TODO: Update UI based on currentUser != null
        }
    }
    /**
     * Clean up any stale trips that might be stuck in IN_PROGRESS status
     * This is useful for debugging and handling app crashes
     */
    private suspend fun cleanupStaleTrips() {
        withContext(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            val prefs = getSharedPreferences("MetroPrefs", Context.MODE_PRIVATE)
            val isFirstLaunch = prefs.getBoolean("is_first_launch", true)

            // On first launch, clean up any trips that might exist from previous installs
            if (isFirstLaunch) {
                val allTrips = db.tripDao().getRecentTripsDebug()
                allTrips.forEach { trip ->
                    if (trip.status == TripStatus.IN_PROGRESS) {
                        // Mark old IN_PROGRESS trips as CANCELLED
                        val updatedTrip = trip.copy(
                            status = TripStatus.CANCELLED,
                            endTime = Date()
                        )
                        db.tripDao().updateTrip(updatedTrip)
                    }
                }
                prefs.edit().putBoolean("is_first_launch", false).apply()
            }
        }
    }

    /**
     * Check if there's an active trip before allowing a new one to be created
     */
    private fun checkForActiveTripBeforeStarting() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database

            // Get the active trip
            val activeTrip = db.tripDao().getActiveTripIfExists()

            withContext(Dispatchers.Main) {
                // Additional validation: check if the trip is truly active and has valid data
                if (activeTrip != null &&
                    activeTrip.status == TripStatus.IN_PROGRESS &&
                    activeTrip.sourceStationName.isNotEmpty() &&
                    activeTrip.destinationStationName.isNotEmpty()) {

                    // Show dialog informing user about existing active trip
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("⚠️ Active Trip in Progress")
                        .setMessage("You already have an ongoing trip from ${activeTrip.sourceStationName} to ${activeTrip.destinationStationName}.\n\nPlease complete or cancel your current trip before starting a new one.")
                        .setPositiveButton("Go to Current Trip") { dialog, _ ->
                            // Navigate to tracking activity with the active trip
                            val trackingIntent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
                                putExtra("EXTRA_TRIP_ID", activeTrip.id)
                            }
                            startActivity(trackingIntent)
                            applyTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                            dialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .setCancelable(true)
                        .show()
                } else {
                    // No valid active trip, proceed to station selection
                    showStationSelectionDialog()
                }
            }
        }
    }

    private fun openAppGuide() {
        supportFragmentManager.beginTransaction()
            // 1. Enter: Guide slides in from Left
            // 2. Exit: Main screen slightly fades out (darkens)
            // 3. Pop Enter: Main screen fades back in
            // 4. Pop Exit: Guide slides back out to Left
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
            // 1. Enter Animation (Opening Dashboard)
            // 2. Exit Animation (Main Screen leaving)
            // 3. Pop Enter Animation (Main Screen coming back when you press Back)
            // 4. Pop Exit Animation (Dashboard leaving when you press Back)
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
    override fun onNewIntent(intent: Intent) {  // ← No question mark!
        super.onNewIntent(intent)
        setIntent(intent)

        val quickSource = intent.getStringExtra("QUICK_START_SOURCE")
        val quickDest = intent.getStringExtra("QUICK_START_DEST")

        if (intent.getBooleanExtra("MOCK_LOCATION_DETECTED", false)) {
            showMockLocationDialog()
        }

        if (quickSource != null && quickDest != null) {
            createNewTripAndStartService(quickSource, quickDest, "")
        }
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
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            // Check if the package exists on the system
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    fun showStationSelectionDialog(prefilledSource: String? = null, prefilledDest: String? = null) {
        val prefs = getSharedPreferences("MetroPrefs", Context.MODE_PRIVATE)

        // Create full-screen dialog
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_station_selector)

        dialog.window?.attributes?.windowAnimations = R.style.DialogSlideAnimation

        // UI references
        val sourceAtv = dialog.findViewById<AutoCompleteTextView>(R.id.sourceAutoComplete)
        val destAtv = dialog.findViewById<AutoCompleteTextView>(R.id.destinationAutoComplete)
        val phoneEt = dialog.findViewById<TextInputEditText>(R.id.etPhoneNumber)
        val cbSms = dialog.findViewById<MaterialCheckBox>(R.id.cbEnableSms)
        val layoutPhone = dialog.findViewById<LinearLayout>(R.id.layoutPhoneInput)
        val btnPickContact = dialog.findViewById<MaterialButton>(R.id.btnPickContact)
        val btnStart = dialog.findViewById<MaterialButton>(R.id.btnStart)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnCancel)
        val toolbar = dialog.findViewById<MaterialToolbar>(R.id.toolbar)

        prefilledSource?.let { sourceAtv.setText(it) }
        prefilledDest?.let { destAtv.setText(it) }

        // Inside showStationSelectionDialog
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
                // App is installed, open it
                val intent = packageManager.getLaunchIntentForPackage(sarthiPackage)
                startActivity(intent)
            } else {
                // App is missing, open the Play Store page
                val playStoreIntent = Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=$sarthiPackage"))
                try {
                    startActivity(playStoreIntent)
                } catch (e: Exception) {
                    // Fallback for browsers if Play Store app isn't working
                    startActivity(Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$sarthiPackage")))
                }
            }
        }

        val btnSwap = dialog.findViewById<MaterialButton>(R.id.btnSwapStations)

        btnSwap.setOnClickListener {
            val currentSource = sourceAtv.text.toString()
            val currentDest = destAtv.text.toString()

            // Swap the text
            // We use setText(text, filter) to prevent the dropdown from opening immediately
            sourceAtv.setText(currentDest, false)
            destAtv.setText(currentSource, false)

            // Optional: Add a small rotation animation to show feedback
            btnSwap.animate().rotationBy(180f).setDuration(300).start()
        }
        // Get RadioGroup reference
        val routePreferenceGroup = dialog.findViewById<RadioGroup>(R.id.routePreferenceGroup)

        currentDialogPhoneField = phoneEt
        phoneEt.setText(prefs.getString("last_phone", ""))

        // Toolbar close button
        toolbar.setNavigationOnClickListener {
            dialog.dismiss()
        }

        // Contact picker button logic
        btnPickContact.setOnClickListener { contactPickerLauncher.launch(null) }

        // SMS checkbox logic (Toggle visibility)
        cbSms.setOnCheckedChangeListener { _, isChecked ->
            layoutPhone.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Cancel button
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        // Database se station list fetch karke dropdown set karo
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            val stationNames = db.metroStationDao().getAllStations()
                .first().map { it.stationName }.distinct().sorted()

            withContext(Dispatchers.Main) {
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, stationNames)
                sourceAtv.setAdapter(adapter)
                destAtv.setAdapter(adapter)

                // Start button click
                btnStart.setOnClickListener {
                    val source = sourceAtv.text.toString()
                    val dest = destAtv.text.toString()

                    // Check karo dono stations pick hue ya nahi
                    if (source.isEmpty() || dest.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Select Both The Stations!", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // Same stations error
                    if (source == dest) {
                        Toast.makeText(this@MainActivity, "Source aur destination cannot be same.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }

                    // SMS logic and validation
                    var phone = ""
                    if (cbSms.isChecked) {
                        val rawPhone = phoneEt.text.toString().replace("\\s".toRegex(), "")
                        if (rawPhone.length != 10 || !rawPhone.all { it.isDigit() }) {
                            Toast.makeText(this@MainActivity, "Enter a 10-digit valid number!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        phone = rawPhone
                        prefs.edit().putString("last_phone", phone).apply()
                    }

                    dialog.dismiss()
                    // Get selected route preference
                    val selectedPreference = when (routePreferenceGroup.checkedRadioButtonId) {
                        R.id.rbLeastInterchanges -> RoutePlanner.RoutePreference.LEAST_INTERCHANGES
                        else -> RoutePlanner.RoutePreference.SHORTEST_PATH
                    }
                    createNewTripAndStartService(source, dest, phone, selectedPreference)
                }

                dialog.show()
            }
        }
    }

    private fun createNewTripAndStartService(
        sourceName: String,
        destName: String,
        phone: String,
        routePreference: RoutePlanner.RoutePreference = RoutePlanner.RoutePreference.SHORTEST_PATH
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = (application as MetroTrackerApplication).database
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

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
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
    private fun getOrCreateDeviceId(): String {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        var deviceId = prefs.getString("device_id", null)

        if (deviceId == null) {
            deviceId = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", deviceId).apply()
        }
        return deviceId
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
                                visitedStations = (document.get("visitedStations") as? List<String>) ?: emptyList(),
                                status = TripStatus.valueOf(document.getString("status") ?: "COMPLETED"),
                                emergencyContact = document.getString("emergencyContact") ?: "",

                                // Important: Mark as SYNCED so we don't upload it again
                                syncState = "SYNCED",
                                deviceId = document.getString("deviceId") ?: "restored_device",
                                lastModified = document.getLong("lastModified") ?: System.currentTimeMillis(),
                                isDeleted = false
                            )

                            appDb.tripDao().insertTrip(trip)
                            restoreCount++
                        } catch (e: Exception) {
                            Log.e("Restore", "Failed to parse trip: ${e.message}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (restoreCount > 0) {
                            Toast.makeText(this@MainActivity, "Restored $restoreCount trips from Cloud!", Toast.LENGTH_SHORT).show()
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
    fun performManualSync(onComplete: () -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete()
            return
        }

        // 1. TRIGGER PUSH (Upload pending trips)
        val workManager = androidx.work.WorkManager.getInstance(applicationContext)
        val syncRequest = androidx.work.OneTimeWorkRequest.Builder(com.metro.delhimetrotracker.worker.SyncWorker::class.java)
            .build()
        workManager.enqueue(syncRequest)

        // 2. TRIGGER PULL (Download new/restored trips)
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()

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

                            // 🛑 CRITICAL FIX: Check Local State First!
                            val localTrip = appDb.tripDao().getTripById(id)

                            // If we have a local version that is waiting to sync (PENDING),
                            // TRUST LOCAL DATA. Do not overwrite it with old cloud data.
                            if (localTrip != null && localTrip.syncState == "PENDING") {
                                android.util.Log.d("Sync", "Skipping Cloud update for Trip $id (Local changes pending)")
                                continue
                            }

                            // If Cloud says deleted, we respect that (unless we have local pending changes handled above)
                            val isDeletedCloud = document.getBoolean("isDeleted") ?: false
                            if (isDeletedCloud) {
                                // Optional: Ensure local is also deleted if cloud says so
                                if (localTrip != null && !localTrip.isDeleted) {
                                    appDb.tripDao().deleteById(id)
                                }
                                continue
                            }

                            val trip = com.metro.delhimetrotracker.data.local.database.entities.Trip(
                                id = id,
                                sourceStationId = document.getString("sourceStationId") ?: "",
                                sourceStationName = document.getString("sourceStationName") ?: "",
                                destinationStationId = document.getString("destinationStationId") ?: "",
                                destinationStationName = document.getString("destinationStationName") ?: "",
                                metroLine = document.getString("metroLine") ?: "",
                                startTime = java.util.Date(document.getLong("startTime") ?: 0L),
                                endTime = document.getLong("endTime")?.let { java.util.Date(it) },
                                durationMinutes = document.getLong("durationMinutes")?.toInt(),
                                visitedStations = (document.get("visitedStations") as? List<String>) ?: emptyList(),
                                status = com.metro.delhimetrotracker.data.local.database.entities.TripStatus.valueOf(document.getString("status") ?: "COMPLETED"),
                                emergencyContact = document.getString("emergencyContact") ?: "",
                                syncState = "SYNCED", // Mark as synced since it came from cloud
                                deviceId = document.getString("deviceId") ?: "cloud",
                                lastModified = document.getLong("lastModified") ?: System.currentTimeMillis(),
                                isDeleted = false,
                                hadSosAlert = document.getBoolean("hadSosAlert") ?: false,
                                sosStationName = document.getString("sosStationName"),
                                sosTimestamp = document.getLong("sosTimestamp")
                            )

                            appDb.tripDao().insertTrip(trip)
                            changesCount++
                        } catch (e: Exception) {
                            android.util.Log.e("Sync", "Error parsing trip: ${e.message}")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (changesCount > 0) {
                            Toast.makeText(this@MainActivity, "Sync Complete: Updated $changesCount trips", Toast.LENGTH_SHORT).show()
                        }
                        onComplete()
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Sync Failed", Toast.LENGTH_SHORT).show()
                onComplete()
            }
    }
}
class AppGuideFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_app_guide, container, false)

        // Just pop the stack. The FragmentManager will handle the animation.
        view.findViewById<Button>(R.id.btnCloseGuide).setBounceClickListener {
            parentFragmentManager.popBackStack()
        }
        return view
    }
}

fun View.setBounceClickListener(onClicked: () -> Unit) {
    this.setOnClickListener { view ->
        // 1. Animate Scale Down
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(100)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                // 2. Animate Scale Back Up
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        // 3. Perform the actual action (Open page, etc.)
                        onClicked()
                    }
                    .start()
            }
            .start()
    }
}