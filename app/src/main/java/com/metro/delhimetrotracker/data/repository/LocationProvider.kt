package com.metro.delhimetrotracker.data.repository

import android.location.Location

interface LocationProvider {
    suspend fun getCurrentLocation(): Location?
    fun isLocationEnabled(): Boolean
}