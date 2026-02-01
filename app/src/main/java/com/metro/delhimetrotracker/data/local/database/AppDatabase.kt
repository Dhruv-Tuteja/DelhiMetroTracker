package com.metro.delhimetrotracker.data.local.database // Updated package

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.metro.delhimetrotracker.data.local.database.converters.Converters
import com.metro.delhimetrotracker.data.local.database.dao.*
import com.metro.delhimetrotracker.data.local.database.entities.*
// Add this to your AppDatabase.kt or wherever you define your Room database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Migration from version 1 to version 2 (adding sync fields)
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add new sync-related columns with default values
        database.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN syncState TEXT NOT NULL DEFAULT 'PENDING'
        """)

        database.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''
        """)

        database.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN lastModified INTEGER NOT NULL DEFAULT 0
        """)

        database.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0
        """)

        database.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN schemaVersion INTEGER NOT NULL DEFAULT 1
        """)
    }
}

// Update your database builder to include the migration:
@Database(
    entities = [Trip::class, StationCheckpoint::class, MetroStation::class, UserSettings::class,StopTime::class, ScheduledTrip::class ],
    version = 3, // Increment version
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun stationCheckpointDao(): StationCheckpointDao
    abstract fun metroStationDao(): MetroStationDao
    abstract fun userSettingsDao(): UserSettingsDao
    abstract fun stopTimeDao(): StopTimeDao
    abstract fun scheduledTripDao(): ScheduledTripDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "metro_tracker_database"
                )
                    .addMigrations(MIGRATION_1_2) // Add migration here
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}