package com.metro.delhimetrotracker.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.UserSettings
import com.metro.delhimetrotracker.utils.TripHistoryExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import androidx.core.content.FileProvider
import java.io.FileOutputStream
import java.io.File
import android.content.res.ColorStateList

class SettingsActivity : AppCompatActivity() {

    private lateinit var currentSettings: UserSettings
    private var currentDialogPhoneField: TextInputEditText? = null

    // Contact picker launcher
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
                    val cleanedNumber = extractIndianMobileNumber(rawNumber)
                    if (!cleanedNumber.isNullOrEmpty() && cleanedNumber.length == 10) {
                        saveEmergencyContact(cleanedNumber)
                    } else {
                        Toast.makeText(
                            this,
                            "Invalid phone number selected",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    phones?.close()
                }
            }
            cursor?.close()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Set up toolbar
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbarSettings).apply {
            setNavigationOnClickListener { finish() }
        }

        // Load current settings
        loadSettings()
    }

    private fun loadSettings() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            val settings = db.userSettingsDao().getUserSettings().firstOrNull() ?: UserSettings()
            currentSettings = settings

            withContext(Dispatchers.Main) {
                setupUIWithSettings(settings)
            }
        }
    }

    private fun setupUIWithSettings(settings: UserSettings) {
        // ===== APPEARANCE SECTION =====
        val switchDarkMode = findViewById<SwitchMaterial>(R.id.switchDarkMode)
        switchDarkMode.isChecked = settings.darkMode
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            updateDarkMode(isChecked)
        }

        // ===== EMERGENCY CONTACT SECTION =====
        val cardEmergencyContact = findViewById<MaterialCardView>(R.id.cardEmergencyContact)
        cardEmergencyContact.setOnClickListener {
            contactPickerLauncher.launch(null)
        }

        updateEmergencyContactDisplay(settings.emergencyContact)

        // ===== SMS SETTINGS =====
        val switchSmsEnabled = findViewById<SwitchMaterial>(R.id.switchSmsEnabled)
        switchSmsEnabled.isChecked = settings.smsEnabled
        switchSmsEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (settings.emergencyContact.isNullOrEmpty()) {
                switchSmsEnabled.isChecked = false
                Toast.makeText(this, "Please set emergency contact first", Toast.LENGTH_SHORT).show()
            } else {
                updateSettings(settings.copy(smsEnabled = isChecked))
            }
        }

        // ===== SOS SETTINGS =====
        val switchSosEnabled = findViewById<SwitchMaterial>(R.id.switchSosEnabled)
        val cardSosAdvanced = findViewById<MaterialCardView>(R.id.cardSosAdvanced)

        switchSosEnabled.isChecked = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("sos_enabled", false)

        switchSosEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (settings.emergencyContact.isNullOrEmpty()) {
                switchSosEnabled.isChecked = false
                Toast.makeText(this, "Please set emergency contact first", Toast.LENGTH_SHORT).show()
            } else {
                getSharedPreferences("settings", MODE_PRIVATE).edit {
                    putBoolean("sos_enabled", isChecked)
                }
                cardSosAdvanced.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
        cardSosAdvanced.visibility = if (switchSosEnabled.isChecked) android.view.View.VISIBLE else android.view.View.GONE

        val switchAutoSos = findViewById<SwitchMaterial>(R.id.switchAutoSos)
        switchAutoSos.isChecked = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("auto_sos_enabled", false)
        switchAutoSos.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("settings", MODE_PRIVATE).edit {
                putBoolean("auto_sos_enabled", isChecked)
            }
        }

        // ===== SCHEDULED TRIPS REMINDER =====
        val cardReminderTime = findViewById<MaterialCardView>(R.id.cardReminderTime)
        cardReminderTime.setOnClickListener {
            showReminderTimeDialog()
        }
        updateReminderTimeDisplay()

        // ===== METRO MAP =====
        val cardMetroMap = findViewById<MaterialCardView>(R.id.cardMetroMap)
        cardMetroMap.setOnClickListener {
            openMetroMap()
        }

        // ===== SYNC SETTINGS =====
        val switchAutoSync = findViewById<SwitchMaterial>(R.id.switchAutoSync)
        switchAutoSync.isChecked = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("auto_sync", true)
        switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("settings", MODE_PRIVATE).edit {
                putBoolean("auto_sync", isChecked)
            }
        }

        // ===== AUTO DELETE OLD TRIPS =====
        val cardAutoDelete = findViewById<MaterialCardView>(R.id.cardAutoDelete)
        cardAutoDelete.setOnClickListener {
            showAutoDeleteDialog()
        }
        updateAutoDeleteDisplay()

        // ===== EXPORT TRIP HISTORY =====
        val cardExportHistory = findViewById<MaterialCardView>(R.id.cardExportHistory)
        cardExportHistory.setOnClickListener {
            showExportDialog()
        }

        // ===== CLEAR ALL DATA =====
        val cardClearData = findViewById<MaterialCardView>(R.id.cardClearData)
        cardClearData.setOnClickListener {
            showClearDataConfirmation()
        }
    }
    private fun updateDarkMode(isDarkMode: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            db.userSettingsDao().updateDarkMode(isDarkMode)

            withContext(Dispatchers.Main) {
                // Apply theme change
                AppCompatDelegate.setDefaultNightMode(
                    if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )

                // Store in SharedPreferences for immediate access
                getSharedPreferences("settings", MODE_PRIVATE).edit {
                    putBoolean("dark_mode", isDarkMode)
                }
            }
        }
    }

    private fun saveEmergencyContact(phone: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            db.userSettingsDao().updateEmergencyContact(phone)

            currentSettings = currentSettings.copy(
                emergencyContact = phone
            )

            withContext(Dispatchers.Main) {
                updateEmergencyContactDisplay(phone)
                Toast.makeText(this@SettingsActivity, "Emergency contact saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateEmergencyContactDisplay(phone: String?) {
        val tvEmergencyContactValue = findViewById<android.widget.TextView>(R.id.tvEmergencyContactValue)
        if (phone.isNullOrEmpty()) {
            tvEmergencyContactValue.text = "Not set"
            tvEmergencyContactValue.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        } else {
            tvEmergencyContactValue.text = phone
            tvEmergencyContactValue.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        }
    }

    private fun showReminderTimeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_reminder_time, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroupReminder)

        val currentReminder = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("default_reminder_minutes", 30)

        when (currentReminder) {
            10 -> radioGroup.check(R.id.radio10Min)
            20 -> radioGroup.check(R.id.radio20Min)
            30 -> radioGroup.check(R.id.radio30Min)
            60 -> radioGroup.check(R.id.radio1Hour)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Default Reminder Time")
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                val selectedMinutes = when (radioGroup.checkedRadioButtonId) {
                    R.id.radio10Min -> 10
                    R.id.radio20Min -> 20
                    R.id.radio30Min -> 30
                    R.id.radio1Hour -> 60
                    else -> 30
                }

                getSharedPreferences("settings", MODE_PRIVATE).edit {
                    putInt("default_reminder_minutes", selectedMinutes)
                }
                updateReminderTimeDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateReminderTimeDisplay() {
        val tvReminderValue = findViewById<android.widget.TextView>(R.id.tvReminderValue)
        val minutes = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("default_reminder_minutes", 30)

        tvReminderValue.text = when (minutes) {
            10 -> "10 minutes"
            20 -> "20 minutes"
            30 -> "30 minutes"
            60 -> "1 hour"
            else -> "30 minutes"
        }
    }

    private fun showAutoDeleteDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_auto_delete, null)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroupAutoDelete)

        val currentDays = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("auto_delete_days", 0)

        when (currentDays) {
            0 -> radioGroup.check(R.id.radioNever)
            30 -> radioGroup.check(R.id.radio30Days)
            60 -> radioGroup.check(R.id.radio60Days)
            90 -> radioGroup.check(R.id.radio90Days)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Auto-Delete Old Trips")
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                val selectedDays = when (radioGroup.checkedRadioButtonId) {
                    R.id.radioNever -> 0
                    R.id.radio30Days -> 30
                    R.id.radio60Days -> 60
                    R.id.radio90Days -> 90
                    else -> 0
                }

                getSharedPreferences("settings", MODE_PRIVATE).edit {
                    putInt("auto_delete_days", selectedDays)
                }

                if (selectedDays > 0) {
                    performAutoDelete(selectedDays)
                }

                updateAutoDeleteDisplay()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateAutoDeleteDisplay() {
        val tvAutoDeleteValue = findViewById<android.widget.TextView>(R.id.tvAutoDeleteValue)
        val days = getSharedPreferences("settings", MODE_PRIVATE)
            .getInt("auto_delete_days", 0)

        tvAutoDeleteValue.text = when (days) {
            0 -> "Never"
            30 -> "After 30 days"
            60 -> "After 60 days"
            90 -> "After 90 days"
            else -> "Never"
        }
    }

    private fun performAutoDelete(days: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)

            val deletedCount = db.tripDao().deleteTripsOlderThan(cutoffTime)

            withContext(Dispatchers.Main) {
                if (deletedCount > 0) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Deleted $deletedCount old trips",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun openMetroMap() {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = File(getExternalFilesDir(null), "DelhiMetroMap.pdf")

            if (!file.exists()) {
                copyPdfFromAssets(file)
            }

            withContext(Dispatchers.Main) {
                openPdfFile(file)
            }
        }
    }
    private fun copyPdfFromAssets(destination: File) {
        assets.open("DelhiMetroMap.pdf").use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
    }
    private fun openPdfFile(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(intent, "Open Metro Map"))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "No PDF viewer installed",
                Toast.LENGTH_LONG
            ).show()
        }
    }




    private fun showExportDialog() {
        val formats = arrayOf("CSV", "JSON")
        MaterialAlertDialogBuilder(this)
            .setTitle("Export Trip History")
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> exportHistory("csv")
                    1 -> exportHistory("json")
                }
            }
            .show()
    }

    private fun exportHistory(format: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = (application as MetroTrackerApplication).database
                val trips = db.tripDao().getAllTripsS()

                val exporter = TripHistoryExporter(this@SettingsActivity)
                val uri = when (format) {
                    "csv" -> exporter.exportToCSV(trips)
                    "json" -> exporter.exportToJSON(trips)
                    else -> null
                }

                withContext(Dispatchers.Main) {
                    if (uri != null) {
                        shareFile(uri, format)
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "Export failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("Export", "Error exporting history", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Export error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun shareFile(uri: Uri, format: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (format == "csv") "text/csv" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Export Trip History"))
    }

    private fun showClearDataConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear All Data")
            .setMessage("This will permanently delete all your trip history and scheduled trips. This action cannot be undone.\n\nAre you sure?")
            .setPositiveButton("Delete All") { _, _ ->
                clearAllData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            db.tripDao().deleteAllTrips()
            db.scheduledTripDao().deleteAllScheduledTrips()

            withContext(Dispatchers.Main) {
                Toast.makeText(this@SettingsActivity, "All data cleared", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateSettings(newSettings: UserSettings) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = (application as MetroTrackerApplication).database
            db.userSettingsDao().insert(newSettings)
            currentSettings = newSettings
        }
    }

    private fun extractIndianMobileNumber(rawNumber: String?): String {
        if (rawNumber.isNullOrEmpty()) return ""
        val cleaned = rawNumber.replace(Regex("[^0-9+]"), "")

        return when {
            cleaned.startsWith("+91") -> cleaned.substring(3).take(10)
            cleaned.startsWith("91") && cleaned.length > 11 -> cleaned.substring(2).take(10)
            cleaned.startsWith("0") && cleaned.length == 11 -> cleaned.substring(1)
            cleaned.length == 10 && !cleaned.startsWith("+") -> cleaned
            else -> cleaned.replace("+", "").take(10)
        }
    }
}