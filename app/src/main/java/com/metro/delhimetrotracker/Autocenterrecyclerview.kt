package com.metro.delhimetrotracker

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import android.util.DisplayMetrics

/**
 * Custom RecyclerView that automatically springs back to center on the "upcoming" station
 * when user stops scrolling, similar to Google Maps recenter behavior.
 */
class AutoCenterRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {

    private var upcomingStationPosition: Int = -1

    private val anchorOffsetDp = 70

    private var userDragged = false
    private var settleRunnable: Runnable? = null
    private val settleDelay = 250L

    init {
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    SCROLL_STATE_DRAGGING -> {
                        userDragged = true
                        cancelSettle()
                    }

                    SCROLL_STATE_IDLE -> {
                        if (userDragged) {
                            scheduleReturnToAnchor()
                            userDragged = false
                        }
                    }
                }
            }
        })
    }

    fun setUpcomingStationPosition(position: Int) {
        upcomingStationPosition = position
    }

    private fun scheduleReturnToAnchor() {
        cancelSettle()
        settleRunnable = Runnable {
            stabilizeUpcomingStation()
        }.also {
            postDelayed(it, settleDelay)
        }
    }

    private fun cancelSettle() {
        settleRunnable?.let {
            removeCallbacks(it)
            settleRunnable = null
        }
    }

    fun stabilizeUpcomingStation() {
        val lm = layoutManager as? LinearLayoutManager ?: return
        val pos = upcomingStationPosition
        if (pos < 0) return

        val view = lm.findViewByPosition(pos)
        val anchorPx = dpToPx(anchorOffsetDp)

        // If not visible OR visible but out of anchor → smooth scroll
        if (view == null ||
            view.top < anchorPx ||
            view.bottom > anchorPx + view.height
        ) {
            smoothScrollToAnchor(pos)
        }
    }


    private fun smoothScrollToAnchor(position: Int) {
        val lm = layoutManager as? LinearLayoutManager ?: return

        val scroller = object : LinearSmoothScroller(context) {
            override fun calculateSpeedPerPixel(dm: DisplayMetrics): Float {
                return 140f / dm.densityDpi // 🔥 smooth & calm
            }

            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int
            ): Int {
                val viewCenter = (viewStart + viewEnd) / 2
                return dpToPx(anchorOffsetDp) - viewCenter
            }
        }

        scroller.targetPosition = position
        lm.startSmoothScroll(scroller)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
