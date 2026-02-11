package com.metro.delhimetrotracker.data.local.database // Updated package

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.metro.delhimetrotracker.data.local.database.dao.*
import com.metro.delhimetrotracker.data.local.database.entities.*

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add new sync-related columns with default values
        db.execSQL(
            """
            ALTER TABLE trips 
            ADD COLUMN syncState TEXT NOT NULL DEFAULT 'PENDING'
        """,
        )

        db.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''
        """)

        db.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN lastModified INTEGER NOT NULL DEFAULT 0
        """)

        db.execSQL("""
            ALTER TABLE trips 
            ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0
        """)

        db.execSQL("""
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