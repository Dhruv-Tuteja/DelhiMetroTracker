package com.metro.delhimetrotracker.data.local.database.converters

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date
import com.metro.delhimetrotracker.data.local.database.entities.RouteDivergence

class Converters {
    private val gson = Gson()

    // Date converters
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? = date?.time

    // List<String> converters
    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return try {
            val listType = object : TypeToken<ArrayList<String>>() {}.type
            gson.fromJson(value, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    @TypeConverter
    fun toStringList(list: List<String>?): String {
        return gson.toJson(list ?: emptyList<String>())
    }

    @TypeConverter
    fun fromRouteDivergenceList(value: List<RouteDivergence>?): String? {
        return value?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun toRouteDivergenceList(value: String?): List<RouteDivergence>? {
        return value?.let {
            val type = object : TypeToken<List<RouteDivergence>>() {}.type
            gson.fromJson(it, type)
        }
    }
}