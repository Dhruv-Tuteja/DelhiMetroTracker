package com.metro.delhimetrotracker.receivers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import java.util.Calendar

object ScheduledTripAlarmManager {

    fun scheduleTrip(context: Context, scheduledTrip: ScheduledTrip) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val triggerTime = calculateTriggerTime(scheduledTrip)

        val intent = Intent(context, ScheduledTripReceiver::class.java).apply {
            putExtra("SCHEDULED_TRIP_ID", scheduledTrip.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduledTrip.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    fun cancelTrip(context: Context, scheduledTripId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, ScheduledTripReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduledTripId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
    }
    fun  cancelTrip(context: Context, scheduledTrip: ScheduledTrip) {
        cancelTrip(context, scheduledTrip.id)
    }

    fun scheduleNextOccurrence(context: Context, scheduledTrip: ScheduledTrip) {
        if (!scheduledTrip.isRecurring) return
        scheduleTrip(context, scheduledTrip)
    }

    private fun calculateTriggerTime(scheduledTrip: ScheduledTrip): Long {
        val calendar = Calendar.getInstance()

        if (scheduledTrip.isRecurring) {
            val recurringDays = scheduledTrip.recurringDays?.split(",") ?: emptyList()
            val daysOfWeek = recurringDays.map { dayStringToCalendarDay(it) }

            calendar.set(Calendar.HOUR_OF_DAY, scheduledTrip.scheduledTimeHour)
            calendar.set(Calendar.MINUTE, scheduledTrip.scheduledTimeMinute - scheduledTrip.reminderMinutesBefore)
            calendar.set(Calendar.SECOND, 0)

            if (calendar.timeInMillis <= System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

            while (!daysOfWeek.contains(calendar.get(Calendar.DAY_OF_WEEK))) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

        } else {
            calendar.timeInMillis = scheduledTrip.scheduledDate ?: System.currentTimeMillis()
            calendar.set(Calendar.HOUR_OF_DAY, scheduledTrip.scheduledTimeHour)
            calendar.set(Calendar.MINUTE, scheduledTrip.scheduledTimeMinute - scheduledTrip.reminderMinutesBefore)
            calendar.set(Calendar.SECOND, 0)
        }

        return calendar.timeInMillis
    }

    private fun dayStringToCalendarDay(day: String): Int {
        return when (day) {
            "SUN" -> Calendar.SUNDAY
            "MON" -> Calendar.MONDAY
            "TUE" -> Calendar.TUESDAY
            "WED" -> Calendar.WEDNESDAY
            "THU" -> Calendar.THURSDAY
            "FRI" -> Calendar.FRIDAY
            "SAT" -> Calendar.SATURDAY
            else -> Calendar.MONDAY
        }
    }
}