package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import com.metro.delhimetrotracker.data.local.database.entities.TripStatus
import kotlinx.coroutines.flow.first
import java.util.*


class RoutePlanner(private val database: AppDatabase) {

    enum class RoutePreference {
        SHORTEST_PATH,
        LEAST_INTERCHANGES
    }

    // Models for internal routing
    data class Node(val stationId: String, val distance: Int) : Comparable<Node> {
        override fun compareTo(other: Node): Int = this.distance.compareTo(other.distance)
    }

    data class RouteSegment(
        val line: String,
        val stations: List<MetroStation>,
        val lineColor: String
    )

    data class CompleteRoute(
        val segments: List<RouteSegment>,
        val totalStations: Int,
        val estimatedDuration: Int
    )

    /**
     * Core Dijkstra Implementation
     */
    suspend fun findRoute(
        sourceId: String,
        destId: String,
        preference: RoutePreference = RoutePreference.SHORTEST_PATH
    ): CompleteRoute? {
        val allStations = database.metroStationDao().getAllStationsFlow().first()
        val graph = buildGraph(allStations, preference)

        val distances = mutableMapOf<String, Int>().withDefault { Int.MAX_VALUE }
        val previous = mutableMapOf<String, String?>()
        val priorityQueue = PriorityQueue<Node>()

        distances[sourceId] = 0
        priorityQueue.add(Node(sourceId, 0))

        while (priorityQueue.isNotEmpty()) {
            val (currentId, currentDist) = priorityQueue.poll()!!

            if (currentId == destId) break
            if (currentDist > (distances[currentId] ?: Int.MAX_VALUE)) continue

            graph[currentId]?.forEach { (neighborId, weight) ->
                val newDist = currentDist + weight
                if (newDist < (distances[neighborId] ?: Int.MAX_VALUE)) {
                    distances[neighborId] = newDist
                    previous[neighborId] = currentId
                    priorityQueue.add(Node(neighborId, newDist))
                }
            }
        }

        return reconstructPath(previous, sourceId, destId, allStations)
    }

    /**
     * Builds an adjacency list with transfer penalties
     */
    private fun buildGraph(
        allStations: List<MetroStation>,
        preference: RoutePreference
    ): Map<String, List<Pair<String, Int>>> {
        val adjacencyList = mutableMapOf<String, MutableList<Pair<String, Int>>>()

        // Weights based on preference
        val stationWeight = 1
        val interchangeWeight = when (preference) {
            RoutePreference.SHORTEST_PATH -> 5      // Small penalty for interchanges
            RoutePreference.LEAST_INTERCHANGES -> 50 // Heavy penalty to avoid interchanges
        }

        // 1. Link stations on the same line
        allStations.groupBy { it.metroLine }.forEach { (_, lineStations) ->
            val sorted = lineStations.sortedBy { it.sequenceNumber }
            for (i in 0 until sorted.size - 1) {
                val u = sorted[i].stationId
                val v = sorted[i + 1].stationId
                adjacencyList.getOrPut(u) { mutableListOf() }.add(v to stationWeight)
                adjacencyList.getOrPut(v) { mutableListOf() }.add(u to stationWeight)
            }
        }

        // 2. Link interchange points with preference-based weight
        allStations.filter { it.isInterchange }.forEach { station ->
            allStations.filter { it.stationName == station.stationName && it.stationId != station.stationId }
                .forEach { other ->
                    adjacencyList.getOrPut(station.stationId) { mutableListOf() }
                        .add(other.stationId to interchangeWeight)
                }
        }
        return adjacencyList
    }

    private fun reconstructPath(
        previous: Map<String, String?>,
        sourceId: String,
        destId: String,
        allStations: List<MetroStation>
    ): CompleteRoute? {
        val pathIds = mutableListOf<String>()
        var curr: String? = destId

        // Build path backwards from destination to source
        while (curr != null) {
            pathIds.add(0, curr)
            if (curr == sourceId) break  // Stop when we reach source (it's already added)
            curr = previous[curr]
        }

        // Verify we have a valid path
        if (pathIds.isEmpty() || pathIds.first() != sourceId) return null

        // Get actual station objects
        val pathStations = pathIds.mapNotNull { id -> allStations.find { it.stationId == id } }
        // Remove consecutive duplicate station names (for interchanges)
        val deduplicatedStations = pathStations.filterIndexed { index, station ->
            index == 0 || station.stationName != pathStations[index - 1].stationName
        }
        val segments = mutableListOf<RouteSegment>()

        // Group stations into segments by line
        if (deduplicatedStations.isNotEmpty()) {
            var currentLineStations = mutableListOf<MetroStation>()
            var currentLine = deduplicatedStations.first().metroLine
            var currentColor = deduplicatedStations.first().lineColor

            deduplicatedStations.forEach { station ->
                if (station.metroLine != currentLine) {
                    // Save current segment before starting new one
                    if (currentLineStations.isNotEmpty()) {
                        segments.add(RouteSegment(currentLine, currentLineStations.toList(), currentColor))
                    }
                    // Start new segment (station goes to NEW segment only)
                    currentLineStations = mutableListOf(station)
                    currentLine = station.metroLine
                    currentColor = station.lineColor
                } else {
                    currentLineStations.add(station)
                }
            }

            // Add final segment
            if (currentLineStations.isNotEmpty()) {
                segments.add(RouteSegment(currentLine, currentLineStations.toList(), currentColor))
            }
        }

        return CompleteRoute(
            segments = segments,
            totalStations = deduplicatedStations.size,
            estimatedDuration = (deduplicatedStations.size * 2) + (segments.size * 5)
        )
    }
}