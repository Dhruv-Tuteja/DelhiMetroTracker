package com.metro.delhimetrotracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import com.google.android.material.color.MaterialColors
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.MetroStation
import androidx.core.graphics.toColorInt

class StationAdapter : RecyclerView.Adapter<StationAdapter.ViewHolder>() {

    private var lastPosition = -1
    private var stations = listOf<MetroStation>()
    private var visitedIds = listOf<String>()
    private var baseTimeForCalculation: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submitData(newStations: List<MetroStation>, visited: List<String>) {
        this.stations = newStations
        this.visitedIds = visited
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateBaseTime(time: String?) {
        this.baseTimeForCalculation = time
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_station_node, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val station = stations[position]
        holder.tvName.text = station.stationName

        val visitedIdsSet = visitedIds.toSet()
        val isVisited = visitedIdsSet.contains(station.stationId)
        val isUpcoming = !isVisited && (position == 0 || visitedIdsSet.contains(stations[position - 1].stationId))

        // Get theme colors
        val colorPrimary = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorPrimary)
        val colorOnSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurface)
        val colorOnSurfaceVariant = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorSurface = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurface)
        val colorSurfaceVariant = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSurfaceVariant)
        val colorOutlineVariant = MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorOutlineVariant)

        // Parse line color
        val lineColorInt = try {
            station.lineColor.toColorInt()
        } catch (_: Exception) {
            colorPrimary
        }

        // LINE VISIBILITY
        holder.lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
        holder.lineBottom.visibility = if (position == stations.size - 1) View.INVISIBLE else View.VISIBLE

        // CALCULATE BRIGHTER COLOR FOR VISITED LINES (much more visible in dark mode)
        val visitedLineColor = if (isDarkTheme(holder.itemView.context)) {
            // In dark mode: use a brighter gray that's clearly visible
            "#6B7BA8".toColorInt() // Light gray-blue
        } else {
            // In light mode: use medium gray
            "#9E9E9E".toColorInt() // Medium gray
        }

        // LINE COLORS - Much better visibility
        if (position > 0) {
            val prevVisited = visitedIdsSet.contains(stations[position - 1].stationId)
            holder.lineTop.setBackgroundColor(
                if (prevVisited) {
                    visitedLineColor
                } else {
                    lineColorInt
                }
            )
        }

        holder.lineBottom.setBackgroundColor(
            if (isVisited) {
                visitedLineColor
            } else {
                lineColorInt
            }
        )

        // NODE DOT COLOR - Better visibility
        val visitedNodeColor = if (isDarkTheme(holder.itemView.context)) {
            "#8A94B8".toColorInt() // Lighter gray for dark mode
        } else {
            "#757575".toColorInt() // Medium gray for light mode
        }

        holder.nodeDot.background = createCircleDrawable(
            if (isVisited) visitedNodeColor else lineColorInt
        )

        // CARD STYLING
        when {
            isUpcoming -> {
                // UPCOMING STATION - HIGHLIGHTED
                holder.tvName.setTextColor(colorOnSurface)

                holder.nodeGlow.visibility = View.VISIBLE
                holder.nodeGlow.background = createGlowDrawable(lineColorInt)

                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "UPCOMING"
                holder.tvStatus.setTextColor(lineColorInt)
                holder.tvStatus.background = createBadgeDrawable(adjustAlpha(lineColorInt, 0.15f))

                holder.stationCard.setCardBackgroundColor(colorSurfaceVariant)
                holder.stationCard.strokeColor = lineColorInt
                holder.stationCard.strokeWidth = 6

                // Show expected time for upcoming station
                if (baseTimeForCalculation != null) {
                    holder.tvExpectedTime.text = addMinutesToTime(baseTimeForCalculation, 2)
                    holder.tvExpectedTime.visibility = View.VISIBLE
                    holder.tvExpectedTime.setTextColor(colorPrimary)
                } else {
                    holder.tvExpectedTime.visibility = View.GONE
                }
            }

            isVisited -> {
                // VISITED STATION - CLEARLY VISIBLE WITH BORDER
                holder.tvName.setTextColor(colorOnSurfaceVariant)

                holder.nodeGlow.visibility = View.INVISIBLE

                holder.tvStatus.visibility = View.VISIBLE
                holder.tvStatus.text = "VISITED"

                // Status badge color
                val statusTextColor = if (isDarkTheme(holder.itemView.context)) {
                    "#8A94B8".toColorInt() // Lighter in dark mode
                } else {
                    "#757575".toColorInt() // Darker in light mode
                }
                holder.tvStatus.setTextColor(statusTextColor)
                holder.tvStatus.background = createBadgeDrawable(adjustAlpha(statusTextColor, 0.15f))

                // Use contrasting background for visited cards
                val visitedCardBg = if (isDarkTheme(holder.itemView.context)) {
                    // In dark mode: slightly lighter than surface
                    "#1E2337".toColorInt() // Lighter dark blue
                } else {
                    // In light mode: white/surface color
                    colorSurface
                }
                holder.stationCard.setCardBackgroundColor(visitedCardBg)

                // Visible border for visited cards
                val visitedBorderColor = if (isDarkTheme(holder.itemView.context)) {
                    "#3A4155".toColorInt() // Visible border in dark mode
                } else {
                    "#E0E0E0".toColorInt() // Light gray border in light mode
                }
                holder.stationCard.strokeColor = visitedBorderColor
                holder.stationCard.strokeWidth = 2

                holder.tvExpectedTime.visibility = View.GONE
            }

            else -> {
                // FUTURE STATIONS - NORMAL APPEARANCE
                holder.tvName.setTextColor(colorOnSurfaceVariant)

                holder.nodeGlow.visibility = View.INVISIBLE
                holder.tvStatus.visibility = View.GONE

                holder.stationCard.setCardBackgroundColor(colorSurface)
                holder.stationCard.strokeColor = colorOutlineVariant
                holder.stationCard.strokeWidth = 2

                holder.tvExpectedTime.visibility = View.GONE
            }
        }

        setAnimation(holder.itemView, position)
    }

    // Helper to detect dark theme
    private fun isDarkTheme(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    @SuppressLint("DefaultLocale")
    private fun addMinutesToTime(timeStr: String?, minutesToAdd: Int): String {
        if (timeStr.isNullOrEmpty()) return "--:--"
        return try {
            val cleanTime = timeStr.trim()
            val parts = cleanTime.split(":")

            var hours = parts[0].toInt()
            var minutes = parts[1].toInt()

            minutes += minutesToAdd

            while (minutes >= 60) {
                hours = (hours + 1) % 24
                minutes -= 60
            }

            val amPm = if (hours >= 12) "PM" else "AM"
            var displayHour = hours % 12
            if (displayHour == 0) displayHour = 12

            String.format("%d:%02d %s", displayHour, minutes, amPm)
        } catch (_: Exception) {
            "--:--"
        }
    }

    override fun getItemCount() = stations.size

    private fun createCircleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun createGlowDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(adjustAlpha(color, 0.25f))
    }

    private fun createBadgeDrawable(bgColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 12f
        setColor(bgColor)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (255 * factor).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun setAnimation(viewToAnimate: View, position: Int) {
        if (position > lastPosition) {
            val animation = AnimationUtils.loadAnimation(
                viewToAnimate.context,
                R.anim.item_animation_fall_down
            )
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