package com.metro.delhimetrotracker.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation

class StationAdapter : RecyclerView.Adapter<StationAdapter.ViewHolder>() {

    private var lastPosition = -1
    private var stations = listOf<MetroStation>()
    private var visitedIds = listOf<String>()

    // Store the base time from the activity
    private var baseTimeForCalculation: String? = null

    fun submitData(newStations: List<MetroStation>, visited: List<String>) {
        this.stations = newStations
        this.visitedIds = visited
        notifyDataSetChanged()
    }

    // Call this from TrackingActivity to pass the train time
    fun updateBaseTime(time: String?) {
        this.baseTimeForCalculation = time
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

        // Identifies the current target station
        val isUpcoming = !isVisited && (position == 0 || visitedIdsSet.contains(stations[position - 1].stationId))

        // 1. GET COLOR
        val lineColorInt = try {
            Color.parseColor(station.lineColor)
        } catch (e: Exception) {
            Color.parseColor("#FFEB3B")
        }

        // 2. APPLY LINE COLORS (Fixes the missing colors)
        holder.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.lineBottom.visibility = if (position == stations.size - 1) View.INVISIBLE else View.VISIBLE

        // Top Line Logic
        if (position > 0) {
            val prevVisited = visitedIdsSet.contains(stations[position - 1].stationId)
            holder.lineTop.setBackgroundColor(if (prevVisited) Color.GRAY else lineColorInt)
        }
        // Bottom Line Logic
        holder.lineBottom.setBackgroundColor(if (isVisited) Color.GRAY else lineColorInt)

        // 3. APPLY NODE COLOR
        holder.nodeDot.background = createCircleDrawable(if (isVisited) Color.GRAY else lineColorInt)

        // 4. STYLE THE CARD
        when {
            isUpcoming -> {
                holder.tvName.setTextColor(Color.WHITE)
                holder.nodeGlow.visibility = View.VISIBLE
                holder.nodeGlow.background = createGlowDrawable(lineColorInt)

                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "UPCOMING"
                holder.tvStatus.setTextColor(lineColorInt)
                holder.tvStatus.background = createBadgeDrawable(adjustAlpha(lineColorInt, 0.2f))

                holder.stationCard.setCardBackgroundColor(Color.parseColor("#1A2645"))
                holder.stationCard.strokeColor = lineColorInt
                holder.stationCard.strokeWidth = 4

                // --- TIME LOGIC: Show +2 mins for the Upcoming Station ---
                if (baseTimeForCalculation != null) {
                    holder.tvExpectedTime.text = addMinutesToTime(baseTimeForCalculation, 2)
                    holder.tvExpectedTime.visibility = View.VISIBLE
                    holder.tvExpectedTime.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    holder.tvExpectedTime.visibility = View.GONE
                }
            }
            isVisited -> {
                holder.tvName.setTextColor(Color.parseColor("#8A94B8"))
                holder.nodeGlow.visibility = View.INVISIBLE
                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "VISITED"
                holder.tvStatus.setTextColor(Color.GRAY)
                holder.tvStatus.background = createBadgeDrawable(Color.parseColor("#1A2645"))
                holder.stationCard.setCardBackgroundColor(Color.parseColor("#0F1429"))
                holder.stationCard.strokeColor = Color.parseColor("#2A3555")
                holder.stationCard.strokeWidth = 2
                holder.tvExpectedTime.visibility = View.GONE
            }
            else -> {
                // Future Stations
                holder.tvName.setTextColor(Color.parseColor("#8A94B8"))
                holder.nodeGlow.visibility = View.INVISIBLE
                holder.tvStatus.visibility = View.GONE
                holder.stationCard.setCardBackgroundColor(Color.parseColor("#0F1429"))
                holder.stationCard.strokeColor = Color.parseColor("#2A3555")
                holder.stationCard.strokeWidth = 2
                holder.tvExpectedTime.visibility = View.GONE
            }
        }
        setAnimation(holder.itemView, position)
    }

    private fun addMinutesToTime(timeStr: String?, minutesToAdd: Int): String {
        if (timeStr.isNullOrEmpty()) return "--:--"
        return try {
            val cleanTime = timeStr.trim()
            val parts = cleanTime.split(":")

            // We only care about the first two parts (Hours and Minutes)
            var hours = parts[0].toInt()
            var minutes = parts[1].toInt()

            // Apply the addition
            minutes += minutesToAdd

            // Proper Rollover Logic
            while (minutes >= 60) {
                hours = (hours + 1) % 24
                minutes -= 60
            }

            // AM/PM Conversion
            val amPm = if (hours >= 12) "PM" else "AM"
            var displayHour = hours % 12
            if (displayHour == 0) displayHour = 12

            // Format strictly: "9:57 PM"
            String.format("%d:%02d %s", displayHour, minutes, amPm)
        } catch (e: Exception) {
            "--:--"
        }
    }

    override fun getItemCount() = stations.size

    private fun createCircleDrawable(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun createGlowDrawable(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(adjustAlpha(color, 0.3f)) }
    private fun createBadgeDrawable(bgColor: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = 12f; setColor(bgColor) }
    private fun adjustAlpha(color: Int, factor: Float) = Color.argb((255 * factor).toInt(), Color.red(color), Color.green(color), Color.blue(color))
    private fun setAnimation(viewToAnimate: View, position: Int) {
        if (position > lastPosition) {
            val animation = android.view.animation.AnimationUtils.loadAnimation(viewToAnimate.context, R.anim.item_animation_fall_down)
            viewToAnimate.startAnimation(animation)
            lastPosition = position
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvStationName)
        val tvStatus: TextView = view.findViewById(R.id.tvStationStatus)
        val tvExpectedTime: TextView = view.findViewById(R.id.tvExpectedTime)
        val nodeDot: View = view.findViewById(R.id.nodeDot)
        val nodeGlow: View = view.findViewById(R.id.nodeGlow)
        val lineTop: View = view.findViewById(R.id.lineTop)
        val lineBottom: View = view.findViewById(R.id.lineBottom)
        val stationCard: MaterialCardView = view.findViewById(R.id.stationCard)
    }
}