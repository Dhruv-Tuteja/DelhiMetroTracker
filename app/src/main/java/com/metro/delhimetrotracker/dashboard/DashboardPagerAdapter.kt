package com.metro.delhimetrotracker.dashboard

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class DashboardPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> TripHistoryPageFragment()
            1 -> ScheduledTripsPageFragment()
            else -> TripHistoryPageFragment()
        }
    }
}