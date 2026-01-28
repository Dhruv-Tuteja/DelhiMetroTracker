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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station_checkpoint, parent, false)
        return StationViewHolder(view)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(stations[position], position + 1)
    }

    override fun getItemCount(): Int = stations.size

    class StationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvStationTime: TextView = itemView.findViewById(R.id.tvStationTime)
        private val tvStationNumber: TextView = itemView.findViewById(R.id.tvStationNumber)
        private val viewLineIndicator: View = itemView.findViewById(R.id.viewLineIndicator)

        fun bind(station: StationWithTime, position: Int) {
            tvStationName.text = station.stationName

            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvStationTime.text = timeFormat.format(station.arrivalTime)

            tvStationNumber.text = position.toString()

            // Parse line color
            try {
                viewLineIndicator.setBackgroundColor(Color.parseColor(station.lineColor))
            } catch (e: Exception) {
                viewLineIndicator.setBackgroundColor(Color.parseColor("#0066CC"))
            }
        }
    }
}