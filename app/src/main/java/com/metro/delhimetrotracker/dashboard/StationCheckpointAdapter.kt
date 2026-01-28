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
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_station_checkpoint, parent, false)
        return CheckpointViewHolder(view)
    }

    override fun onBindViewHolder(holder: CheckpointViewHolder, position: Int) {
        holder.bind(checkpoints[position], position + 1)
    }

    override fun getItemCount(): Int = checkpoints.size

    class CheckpointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStationName: TextView = itemView.findViewById(R.id.tvStationName)
        private val tvStationTime: TextView = itemView.findViewById(R.id.tvStationTime)
        private val tvStationNumber: TextView = itemView.findViewById(R.id.tvStationNumber)
        private val viewLineIndicator: View = itemView.findViewById(R.id.viewLineIndicator)

        fun bind(checkpoint: StationCheckpoint, position: Int) {
            tvStationName.text = checkpoint.stationName

            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            tvStationTime.text = timeFormat.format(checkpoint.arrivalTime)

            tvStationNumber.text = position.toString()

            // Set line color (you can enhance this based on metro line)
            viewLineIndicator.setBackgroundColor(Color.parseColor("#0066CC"))
        }
    }
}