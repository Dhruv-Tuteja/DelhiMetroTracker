package com.metro.delhimetrotracker.data.models

data class MetroDataJson(
    val lines: List<LineJson>
)

data class LineJson(
    val name: String,
    val color: String,
    val stations: List<StationJson>
)

data class StationJson(
    val id: String,
    val name: String,
    val nameHindi: String?,
    val latitude: Double,
    val longitude: Double,
    val sequence: Int,
    val interchange: Boolean,
    val interchangeLines: List<String>? = null
)