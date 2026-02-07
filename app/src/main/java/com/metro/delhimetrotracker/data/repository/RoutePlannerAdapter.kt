package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation

/**
 * Adapter to make the existing RoutePlanner compatible with the new interface
 * Uses your existing Dijkstra-based RoutePlanner under the hood
 */
class RoutePlannerAdapter(
    private val database: AppDatabase
) : RoutePlannerInterface {

    // Use your existing RoutePlanner
    private val routePlanner = RoutePlanner(database)

    override suspend fun findPath(
        fromStationId: String,
        toStationId: String
    ): List<MetroStation> {
        val completeRoute = routePlanner.findRoute(
            sourceId = fromStationId,
            destId = toStationId,
            preference = RoutePlanner.RoutePreference.SHORTEST_PATH
        )

        // Flatten all segments into a single list
        return completeRoute?.segments?.flatMap { it.stations } ?: emptyList()
    }

    override suspend fun findPathWithLinePreference(
        fromStationId: String,
        toStationId: String,
        preferredLines: List<String>
    ): List<MetroStation> {
        // Use LEAST_INTERCHANGES if specific lines are preferred
        val completeRoute = routePlanner.findRoute(
            sourceId = fromStationId,
            destId = toStationId,
            preference = RoutePlanner.RoutePreference.LEAST_INTERCHANGES
        )

        return completeRoute?.segments?.flatMap { it.stations } ?: emptyList()
    }

    override fun isStationOnPath(stationId: String, path: List<MetroStation>): Boolean {
        return path.any { it.stationId == stationId }
    }

    override fun getStationIndexInPath(stationId: String, path: List<MetroStation>): Int? {
        val index = path.indexOfFirst { it.stationId == stationId }
        return if (index >= 0) index else null
    }
}