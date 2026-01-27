package com.metro.delhimetrotracker.ui

import com.metro.delhimetrotracker.ui.dashboard.DashboardFragment
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.material.card.MaterialCardView
import android.os.Build
import android.os.Bundle
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
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.RadioGroup
import com.metro.delhimetrotracker.data.repository.RoutePlanner

class MainActivity : AppCompatActivity() {

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
            Toast.makeText(this, "⚠️ Tracking will not work correctly without permissions.", Toast.LENGTH_LONG).show()
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize Database (Load stations from Assets)
        lifecycleScope.launch {
            val db = (application as MetroTrackerApplication).database
            DatabaseInitializer.initializeStations(this@MainActivity, db)
            Toast.makeText(this@MainActivity, "Metro data ready!", Toast.LENGTH_SHORT).show()
        }

        // 2. Start button click listener
        findViewById<Button>(R.id.btnStartJourney).setOnClickListener {
            showStationSelectionDialog()
        }
        findViewById<MaterialCardView>(R.id.btnViewDashboard)?.setOnClickListener {
            openDashboard()
        }
        findViewById<MaterialCardView>(R.id.btnAppInfo).setOnClickListener {
            openAppGuide()
        }
        val btnInfo = findViewById<MaterialCardView>(R.id.btnAppInfo)

        requestAllPermissions()
        checkBatteryOptimization() // Check and show battery optimization dialog

    }
    private fun openAppGuide() {
        val guideFragment = AppGuideFragment()
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, guideFragment)
            .addToBackStack(null)
            .commit()
    }
    private fun openDashboard() {
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, com.metro.delhimetrotracker.ui.dashboard.DashboardFragment())
            .addToBackStack(null)
            .commit()
    }
    override fun onNewIntent(intent: Intent) {  // ← No question mark!
        super.onNewIntent(intent)
        setIntent(intent)

        val quickSource = intent.getStringExtra("QUICK_START_SOURCE")
        val quickDest = intent.getStringExtra("QUICK_START_DEST")

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
            val db = (application as MetroTrackerApplication).database
            val sourceStation = db.metroStationDao().searchStations(sourceName).first().firstOrNull()
            val destStation = db.metroStationDao().searchStations(destName).first().firstOrNull()

            if (sourceStation != null && destStation != null) {
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
                    status = TripStatus.IN_PROGRESS
                )
                val tripId = db.tripDao().insertTrip(newTrip)

                withContext(Dispatchers.Main) {
                    // 1. Start Foreground Service for journey tracking
                    val serviceIntent = Intent(this@MainActivity, JourneyTrackingService::class.java).apply {
                        action = JourneyTrackingService.ACTION_START_JOURNEY
                        putExtra(JourneyTrackingService.EXTRA_TRIP_ID, tripId)
                        putExtra("ROUTE_PREFERENCE", routePreference.name) // Pass preference to service
                    }
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)

                    // 2. Navigate to tracking screen
                    val trackingIntent = Intent(this@MainActivity, TrackingActivity::class.java).apply {
                        putExtra("EXTRA_TRIP_ID", tripId)
                        putExtra("ROUTE_PREFERENCE", routePreference.name) // Pass to tracking activity
                    }
                    startActivity(trackingIntent)
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Background Tracking Issue")
                .setMessage("To ensure you receive alerts even when your screen is off, please set battery usage to 'Unrestricted'.")
                .setPositiveButton("Settings") { _, _ ->
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton("Later", null)
                .show()
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