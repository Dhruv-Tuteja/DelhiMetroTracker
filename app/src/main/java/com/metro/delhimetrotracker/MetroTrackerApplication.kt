package com.metro.delhimetrotracker // Updated to match your project namespace

import android.app.Application
import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase // Updated import
import com.metro.delhimetrotracker.data.repository.DatabaseInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class MetroTrackerApplication : Application() {

    // The database is initialized once and accessible globally via the app context
    lateinit var database: AppDatabase
        private set

    private val applicationScope = CoroutineScope(SupervisorJob())

    companion object {
        private const val TAG = "MetroTrackerApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application starting...")

        // Initialize the single Room database instance
        database = AppDatabase.getDatabase(this)

        // Initialize the database with station data
        applicationScope.launch {
            DatabaseInitializer.initializeStations(this@MetroTrackerApplication, database)
            database.userSettingsDao().initializeDefaultSettings()
        }


        Log.d(TAG, "Application initialized successfully")
    }
    fun shouldAutoSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

        val autoSync = prefs.getBoolean("auto_sync", true)
        if (!autoSync) return false

        val wifiOnly = prefs.getBoolean("wifi_only_sync", false)
        if (!wifiOnly) return true

        // wifiOnly = true → check network
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false

        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

}