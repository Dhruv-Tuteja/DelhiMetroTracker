package com.metro.delhimetrotracker.data.repository

import com.metro.delhimetrotracker.data.local.database.AppDatabase
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import kotlinx.coroutines.flow.first
import java.util.*
@Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
class RoutePlanner(private val database: AppDatabase) {
    enum class RoutePreference {
        SHORTEST_PATH,
        LEAST_INTERCHANGES
    }
    private data class State(
        val stationId: String,
        val line: String
    )

    private data class Node(
        val state: State,
        val distance: Int
    ) : Comparable<Node> {
        override fun compareTo(other: Node) = distance - other.distance
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
    suspend fun findRoute(
        sourceId: String,
        destId: String,
        preference: RoutePreference
    ): CompleteRoute? {

        val allStations = database.metroStationDao().getAllStationsFlow().first()
        val graph = buildGraph(allStations, preference)

        val sourceStates = allStations
            .filter { it.stationId == sourceId }
            .map { State(it.stationId, it.metroLine) }

        val destStationNames = allStations
            .filter { it.stationId == destId }
            .map { it.stationName }
            .toSet()

        val dist = mutableMapOf<State, Int>().withDefault { Int.MAX_VALUE }
        val prev = mutableMapOf<State, State?>()
        val pq = PriorityQueue<Node>()

        sourceStates.forEach {
            dist[it] = 0
            pq.add(Node(it, 0))
        }

        var finalState: State? = null

        while (pq.isNotEmpty()) {
            val (state, d) = pq.poll()

            if (d > dist.getValue(state)) continue

            val stationName = allStations.first { it.stationId == state.stationId }.stationName
            if (stationName in destStationNames) {
                finalState = state
                break
            }

            graph[state]?.forEach { (next, w) ->
                val nd = d + w
                if (nd < dist.getValue(next)) {
                    dist[next] = nd
                    prev[next] = state
                    pq.add(Node(next, nd))
                }
            }
        }

        return finalState?.let {
            reconstructPath(prev, it, sourceId, allStations)
        }
    }


    private fun buildGraph(
        allStations: List<MetroStation>,
        preference: RoutePreference
    ): Map<State, List<Pair<State, Int>>> {

        val graph = mutableMapOf<State, MutableList<Pair<State, Int>>>()

        val stationMoveCost = 1
        val interchangeCost = when (preference) {
            RoutePreference.SHORTEST_PATH -> 6
            RoutePreference.LEAST_INTERCHANGES -> 100
        }

        // 1️⃣ Same-line adjacency
        allStations
            .groupBy { it.metroLine }
            .forEach { (_, lineStations) ->
                val sorted = lineStations.sortedBy { it.sequenceNumber }

                for (i in 0 until sorted.size - 1) {
                    val u = sorted[i]
                    val v = sorted[i + 1]

                    val uState = State(u.stationId, u.metroLine)
                    val vState = State(v.stationId, v.metroLine)

                    graph.getOrPut(uState) { mutableListOf() }
                        .add(vState to stationMoveCost)

                    graph.getOrPut(vState) { mutableListOf() }
                        .add(uState to stationMoveCost)
                }
            }

        // 2️⃣ Interchange edges (line switches ONLY)
        allStations
            .groupBy { it.stationName }
            .values
            .forEach { sameNameStations ->
                if (sameNameStations.size > 1) {
                    for (a in sameNameStations) {
                        for (b in sameNameStations) {
                            if (a.metroLine != b.metroLine) {
                                val from = State(a.stationId, a.metroLine)
                                val to = State(b.stationId, b.metroLine)

                                graph.getOrPut(from) { mutableListOf() }
                                    .add(to to interchangeCost)
                            }
                        }
                    }
                }
            }

        return graph
    }


    private fun reconstructPath(
        prev: Map<State, State?>,
        end: State,
        sourceId: String,
        allStations: List<MetroStation>
    ): CompleteRoute? {

        val path = mutableListOf<State>()
        var curr: State? = end

        while (curr != null) {
            path.add(0, curr)
            if (curr.stationId == sourceId) break
            curr = prev[curr]
        }

        if (path.first().stationId != sourceId) return null

        val stations = path.mapNotNull { state ->
            allStations.find {
                it.stationId == state.stationId && it.metroLine == state.line
            }
        }

        val deduped = stations.filterIndexed { i, s ->
            i == 0 || s.stationName != stations[i - 1].stationName
        }

        val segments = mutableListOf<RouteSegment>()
        var currentLine = deduped.first().metroLine
        var currentColor = deduped.first().lineColor
        val buffer = mutableListOf<MetroStation>()

        deduped.forEach { station ->
            if (station.metroLine != currentLine) {
                segments.add(RouteSegment(currentLine, buffer.toList(), currentColor))
                buffer.clear()
                currentLine = station.metroLine
                currentColor = station.lineColor
            }
            buffer.add(station)
        }

        if (buffer.isNotEmpty()) {
            segments.add(RouteSegment(currentLine, buffer.toList(), currentColor))
        }

        return CompleteRoute(
            segments = segments,
            totalStations = deduped.size,
            estimatedDuration = deduped.size * 2 + (segments.size - 1) * 5
        )
    }

}
