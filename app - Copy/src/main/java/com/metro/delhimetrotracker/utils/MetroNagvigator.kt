package com.metro.delhimetrotracker.utils

import com.metro.delhimetrotracker.data.local.database.dao.MetroStationDao
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import java.util.*

object MetroNavigator {

    suspend fun findShortestPath(
        dao: MetroStationDao,
        startId: String,
        endId: String
    ): List<MetroStation> {
        val queue: Queue<String> = LinkedList()
        val visited = mutableSetOf<String>()
        val parentMap = mutableMapOf<String, String?>()

        queue.add(startId)
        visited.add(startId)
        parentMap[startId] = null

        while (queue.isNotEmpty()) {
            val currentId = queue.poll() ?: continue
            if (currentId == endId) break

            val currentStation = dao.getStationById(currentId) ?: continue

            // Get neighbors: Next/Previous in sequence AND Interchange options
            val neighbors = dao.getAdjacentStations(
                line = currentStation.metroLine,
                seq = currentStation.sequenceNumber,
                name = currentStation.stationName,
                currentId = currentId
            )

            for (neighbor in neighbors) {
                if (neighbor.stationId !in visited) {
                    visited.add(neighbor.stationId)
                    parentMap[neighbor.stationId] = currentId
                    queue.add(neighbor.stationId)
                }
            }
        }

        val rawPath = reconstructPath(dao, parentMap, endId)

        // Filter to remove consecutive duplicates of the same station name (Interchanges)
        val distinctPath = mutableListOf<MetroStation>()
        rawPath.forEach { station ->
            if (distinctPath.isEmpty() || distinctPath.last().stationName != station.stationName) {
                distinctPath.add(station)
            }
        }
        return distinctPath
    }

    private suspend fun reconstructPath(
        dao: MetroStationDao,
        parentMap: Map<String, String?>,
        endId: String
    ): List<MetroStation> {
        val path = mutableListOf<MetroStation>()
        var curr: String? = endId

        if (!parentMap.containsKey(endId)) return emptyList() // No path found

        while (curr != null) {
            dao.getStationById(curr)?.let { path.add(it) }
            curr = parentMap[curr]
        }
        return path.reversed()
    }
}