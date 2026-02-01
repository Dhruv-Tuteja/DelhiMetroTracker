package com.metro.delhimetrotracker.ui.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.R
import java.text.SimpleDateFormat
import java.util.*

data class StationWithTime(
    val stationName: String,
    val arrivalTime: Date,
    val lineColor: String
)

class StationWithTimeAdapter(
    private val stations: List<StationWithTime>
) : RecyclerView.Adapter<StationWithTimeAdapter.StationViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        // FIX 1: Change to your new layout name
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_station, parent, false)
        return StationViewHolder(view)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        // FIX 2: Pass position flags to handle the timeline vertical lines
        val isFirst = position == 0
        val isLast = position == itemCount - 1
        holder.bind(stations[position], isFirst, isLast)
    }

    override fun getItemCount(): Int = stations.size

    class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // FIX 3: Map to the IDs present in item_timeline_station.xml
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val viewLineTop: View = itemView.findViewById(R.id.viewLineTop)
        private val viewLineBottom: View = itemView.findViewById(R.id.viewLineBottom)
        private val ivDot: View = itemView.findViewById(R.id.ivDot)

        fun bind(station: StationWithTime, isFirst: Boolean, isLast: Boolean) {
            tvStationName.text = station.stationName

            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvTime.text = timeFormat.format(station.arrivalTime)

            // Timeline UI: Logic to hide line segments at start and end
            viewLineTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
            viewLineBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

            // Set the color of the vertical lines and dot based on the Metro Line color
            try {
                val color = Color.parseColor(station.lineColor)
                viewLineTop.setBackgroundColor(color)
                viewLineBottom.setBackgroundColor(color)
                // If ivDot is an ImageView, use setColorFilter. If it's a View, use setBackgroundColor.
                ivDot.setBackgroundColor(color)
            } catch (e: Exception) {
                val defaultColor = Color.parseColor("#0066CC")
                viewLineTop.setBackgroundColor(defaultColor)
                viewLineBottom.setBackgroundColor(defaultColor)
                ivDot.setBackgroundColor(defaultColor)
            }
        }
    }
}