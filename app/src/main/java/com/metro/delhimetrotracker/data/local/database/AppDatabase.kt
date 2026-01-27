package com.metro.delhimetrotracker.data.local.database // Updated package

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.metro.delhimetrotracker.data.local.database.converters.Converters
import com.metro.delhimetrotracker.data.local.database.dao.*
import com.metro.delhimetrotracker.data.local.database.entities.*

@Database(
    entities = [
        Trip::class,
        StationCheckpoint::class,
        MetroStation::class,
        UserSettings::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun stationCheckpointDao(): StationCheckpointDao
    abstract fun metroStationDao(): MetroStationDao
    abstract fun userSettingsDao(): UserSettingsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null
        private const val DATABASE_NAME = "delhi_metro_tracker.db"

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration(true) // Fix for deprecation warning
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}