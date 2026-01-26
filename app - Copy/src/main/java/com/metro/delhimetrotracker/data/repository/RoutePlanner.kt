package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import com.metro.delhimetrotracker.data.local.database.entities.Trip
import com.metro.delhimetrotracker.data.local.database.entities.TripStatus
import kotlinx.coroutines.flow.first
import java.util.*

class RoutePlanner(private val database: AppDatabase) {

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
    suspend fun findRoute(sourceId: String, destId: String): CompleteRoute? {
        val allStations = database.metroStationDao().getAllStations().first()
        val graph = buildGraph(allStations)

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
    private fun buildGraph(allStations: List<MetroStation>): Map<String, List<Pair<String, Int>>> {
        val adjacencyList = mutableMapOf<String, MutableList<Pair<String, Int>>>()

        // 1. Link stations on the same line
        allStations.groupBy { it.metroLine }.forEach { (_, lineStations) ->
            val sorted = lineStations.sortedBy { it.sequenceNumber }
            for (i in 0 until sorted.size - 1) {
                val u = sorted[i].stationId
                val v = sorted[i + 1].stationId
                // Weight of 1 represents travel between stations
                adjacencyList.getOrPut(u) { mutableListOf() }.add(v to 1)
                adjacencyList.getOrPut(v) { mutableListOf() }.add(u to 1)
            }
        }

        // 2. Link physical interchange points (different IDs, same Name)
        allStations.filter { it.isInterchange }.forEach { station ->
            allStations.filter { it.stationName == station.stationName && it.stationId != station.stationId }
                .forEach { other ->
                    // Weight of 5 represents an "interchange penalty" (walking time)
                    adjacencyList.getOrPut(station.stationId) { mutableListOf() }.add(other.stationId to 5)
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
        while (curr != null) {
            pathIds.add(0, curr)
            curr = previous[curr]
            if (curr == sourceId) {
                pathIds.add(0, sourceId)
                break
            }
        }

        if (pathIds.isEmpty() || pathIds.first() != sourceId) return null

        val pathStations = pathIds.mapNotNull { id -> allStations.find { it.stationId == id } }
        val segments = mutableListOf<RouteSegment>()

        // Group stations into segments whenever the line changes
        if (pathStations.isNotEmpty()) {
            var currentLineStations = mutableListOf<MetroStation>()
            var currentLine = pathStations.first().metroLine
            var currentColor = pathStations.first().lineColor

            pathStations.forEach { station ->
                if (station.metroLine != currentLine) {
                    segments.add(RouteSegment(currentLine, currentLineStations, currentColor))
                    currentLineStations = mutableListOf()
                    currentLine = station.metroLine
                    currentColor = station.lineColor
                }
                currentLineStations.add(station)
            }
            segments.add(RouteSegment(currentLine, currentLineStations, currentColor))
        }

        return CompleteRoute(
            segments = segments,
            totalStations = pathStations.size,
            estimatedDuration = (pathStations.size * 2) + (segments.size * 5)
        )
    }
}