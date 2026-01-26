package com.metro.delhimetrotracker.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation

class StationAdapter : RecyclerView.Adapter<StationAdapter.ViewHolder>() {

    private var stations = listOf<MetroStation>()
    private var visitedIds = listOf<String>()

    fun submitData(newStations: List<MetroStation>, visited: List<String>) {
        this.stations = newStations
        this.visitedIds = visited
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_station_node, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        holder.tvName.text = station.stationName

        val visitedIdsSet = visitedIds.toSet()
        val isVisited = visitedIdsSet.contains(station.stationId)
        val isCurrent = !isVisited && (position == 0 || visitedIdsSet.contains(stations[position - 1].stationId))

        val lineColorInt = try {
            Color.parseColor(station.lineColor)
        } catch (e: Exception) {
            Color.parseColor("#FFEB3B") // Yellow fallback
        }

        // Timeline visibility
        holder.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.lineBottom.visibility = if (position == stations.size - 1) View.INVISIBLE else View.VISIBLE

        // ✅ FIXED: Consistent line logic
        // Top line: Gray if coming FROM a visited station, colored if coming from unvisited
        if (position > 0) {
            val prevVisited = visitedIdsSet.contains(stations[position - 1].stationId)
            holder.lineTop.setBackgroundColor(if (prevVisited) Color.GRAY else lineColorInt)
        }

        // Bottom line: Gray if current is visited, colored if current is unvisited
        holder.lineBottom.setBackgroundColor(if (isVisited) Color.GRAY else lineColorInt)

        when {
            isCurrent -> {
                holder.tvName.setTextColor(Color.WHITE)
                holder.tvName.textSize = 18f
                holder.tvName.setTypeface(null, android.graphics.Typeface.BOLD)

                holder.nodeGlow.visibility = View.VISIBLE
                holder.nodeDot.background = NodeDrawableFactory.createSolidCircle(lineColorInt)
                holder.nodeGlow.background = NodeDrawableFactory.createGlowCircle(lineColorInt)

                holder.stationCard.setCardBackgroundColor(Color.parseColor("#1A2645"))
                holder.stationCard.strokeColor = lineColorInt
                holder.stationCard.strokeWidth = 4

                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "UPCOMING"
                holder.tvStatus.setTextColor(lineColorInt)
            }
            isVisited -> {
                holder.tvName.setTextColor(Color.parseColor("#8A94B8"))
                holder.tvName.textSize = 16f
                holder.tvName.setTypeface(null, android.graphics.Typeface.NORMAL)

                holder.nodeDot.background = NodeDrawableFactory.createSolidCircle(Color.GRAY)
                holder.nodeGlow.visibility = View.INVISIBLE

                holder.stationCard.setCardBackgroundColor(Color.parseColor("#0F1429"))
                holder.stationCard.strokeColor = Color.parseColor("#2A3555")
                holder.stationCard.strokeWidth = 1

                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "VISITED"
                holder.tvStatus.setTextColor(Color.parseColor("#6B7BA8"))
            }
            else -> {
                holder.tvName.setTextColor(Color.parseColor("#6B7BA8"))
                holder.tvName.textSize = 16f
                holder.tvName.setTypeface(null, android.graphics.Typeface.NORMAL)

                holder.nodeGlow.visibility = View.INVISIBLE
                holder.nodeDot.background = NodeDrawableFactory.createSolidCircle(lineColorInt)

                holder.stationCard.setCardBackgroundColor(Color.parseColor("#0F1429"))
                holder.stationCard.strokeColor = Color.parseColor("#2A3555")
                holder.stationCard.strokeWidth = 1

                holder.tvStatus.visibility = View.GONE
            }
        }
    }

    override fun getItemCount() = stations.size

    /**
     * Helper function to adjust color alpha
     */
    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(255 * factor)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvStationName)
        val tvStatus: TextView = view.findViewById(R.id.tvStationStatus)
        val nodeDot: View = view.findViewById(R.id.nodeDot)
        val nodeGlow: View = view.findViewById(R.id.nodeGlow)
        val lineTop: View = view.findViewById(R.id.lineTop)
        val lineBottom: View = view.findViewById(R.id.lineBottom)
        val stationCard: MaterialCardView = view.findViewById(R.id.stationCard)
    }
}