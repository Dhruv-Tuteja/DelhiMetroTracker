package com.metro.delhimetrotracker // Updated to match your project namespace

import android.app.Application
import android.util.Log
import com.metro.delhimetrotracker.data.local.database.AppDatabase // Updated import
import com.metro.delhimetrotracker.data.repository.DatabaseInitializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
}