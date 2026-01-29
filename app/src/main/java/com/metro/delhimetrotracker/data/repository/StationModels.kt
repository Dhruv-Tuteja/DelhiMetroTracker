package com.metro.delhimetrotracker.data.models

import com.google.gson.annotations.SerializedName

data class MetroDataJson(
    val lines: List<LineJson>
)

data class LineJson(
    val name: String,
    val color: String,
    val stations: List<StationJson>
)

data class StationJson(
    // NOTE: The JSON file does NOT contain an 'id'.
    // If you parse automatically, this field might fail unless you make it nullable or generate it later.
    val id: String? = null,

    val name: String,
    val nameHindi: String?,
    val latitude: Double,
    val longitude: Double,
    val sequence: Int,
    val interchange: Boolean,
    val interchangeLines: List<String>? = null,

    // --- ADDED THIS FIELD ---
    // This matches the new key we added to your JSON file
    @SerializedName("gtfs_stop_id")
    val gtfsStopId: String? = null
)