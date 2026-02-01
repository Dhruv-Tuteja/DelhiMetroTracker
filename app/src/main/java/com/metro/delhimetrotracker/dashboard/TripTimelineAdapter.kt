package com.metro.delhimetrotracker.ui.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint
import java.text.SimpleDateFormat
import java.util.*

class TripTimelineAdapter(
    private val checkpoints: List<StationCheckpoint>,
    private val lineColors: List<String>
) : RecyclerView.Adapter<TripTimelineAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_station, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(checkpoints[position], position)
    }

    override fun getItemCount() = checkpoints.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val ivDot: ImageView = itemView.findViewById(R.id.ivDot)
        private val viewLineTop: View = itemView.findViewById(R.id.viewLineTop)
        private val viewLineBottom: View = itemView.findViewById(R.id.viewLineBottom)

        fun bind(checkpoint: StationCheckpoint, position: Int) {
            tvStationName.text = checkpoint.stationName
            tvTime.text = timeFormat.format(checkpoint.arrivalTime)

            // Get line color
            val color = if (lineColors.isNotEmpty()) {
                parseColor(lineColors[position % lineColors.size])
            } else {
                Color.parseColor("#64B5F6")
            }

            // Set colors
            ivDot.setColorFilter(color)
            viewLineTop.setBackgroundColor(color)
            viewLineBottom.setBackgroundColor(color)

            // Hide top line for first item
            viewLineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE

            // Hide bottom line for last item
            viewLineBottom.visibility = if (position == checkpoints.size - 1) View.INVISIBLE else View.VISIBLE
        }

        private fun parseColor(colorString: String): Int {
            return try {
                Color.parseColor(colorString)
            } catch (e: Exception) {
                Color.parseColor("#64B5F6")
            }
        }
    }
}