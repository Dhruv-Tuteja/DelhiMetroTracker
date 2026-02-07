package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.entities.MetroStation

/**
 * Interface for route planning operations
 * This is the interface that RouteRecoveryManager and other components expect
 */
interface RoutePlannerInterface {
    /**
     * Find path between two stations
     * @return List of stations forming the path, or empty if no path exists
     */
    suspend fun findPath(
        fromStationId: String,
        toStationId: String
    ): List<MetroStation>

    /**
     * Find path with specific line preference
     */
    suspend fun findPathWithLinePreference(
        fromStationId: String,
        toStationId: String,
        preferredLines: List<String>
    ): List<MetroStation>

    /**
     * Check if station is on the given path
     */
    fun isStationOnPath(
        stationId: String,
        path: List<MetroStation>
    ): Boolean

    /**
     * Get index of station in path
     */
    fun getStationIndexInPath(
        stationId: String,
        path: List<MetroStation>
    ): Int?
}