package com.metro.delhimetrotracker.ui.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.StationCheckpoint
import java.text.SimpleDateFormat
import java.util.*

class StationCheckpointAdapter(
    private val checkpoints: List<StationCheckpoint>
) : RecyclerView.Adapter<StationCheckpointAdapter.CheckpointViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CheckpointViewHolder {
        // Fix 1: Changed layout reference to your new file name
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_station, parent, false)
        return CheckpointViewHolder(view)
    }

    override fun onBindViewHolder(holder: CheckpointViewHolder, position: Int) {
        // Fix 2: Handle visibility of top/bottom lines for the timeline effect
        val isFirst = position == 0
        val isLast = position == itemCount - 1
        holder.bind(checkpoints[position], isFirst, isLast)
    }

    override fun getItemCount(): Int = checkpoints.size

    class CheckpointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Fix 3: Map to the correct IDs from your new XML
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val viewLineTop: View = itemView.findViewById(R.id.viewLineTop)
        private val viewLineBottom: View = itemView.findViewById(R.id.viewLineBottom)

        fun bind(checkpoint: StationCheckpoint, isFirst: Boolean, isLast: Boolean) {
            tvStationName.text = checkpoint.stationName

            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvTime.text = timeFormat.format(checkpoint.arrivalTime)

            // Timeline logic: Hide top line for the first item, bottom line for the last
            viewLineTop.visibility = if (isFirst) View.INVISIBLE else View.VISIBLE
            viewLineBottom.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

            // Optional: You can color the dot/lines based on the metro line
            // val lineColor = Color.parseColor("#0066CC")
            // itemView.findViewById<ImageView>(R.id.ivDot).setColorFilter(lineColor)
        }
    }
}