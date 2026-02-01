package com.metro.delhimetrotracker.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.metro.delhimetrotracker.MetroTrackerApplication
import com.metro.delhimetrotracker.R
import com.metro.delhimetrotracker.data.local.database.entities.ScheduledTrip
import com.metro.delhimetrotracker.data.repository.MetroRepository
import com.metro.delhimetrotracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduledTripReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val scheduledTripId = intent.getLongExtra("SCHEDULED_TRIP_ID", -1L)
        if (scheduledTripId == -1L) return

        // goAsync() is required for long-running tasks in BroadcastReceivers
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as MetroTrackerApplication
                val db = app.database
                val dao = db.scheduledTripDao()
                val scheduledTrip = dao.getScheduledTripById(scheduledTripId)

                // 1. Check if trip exists and is active
                if (scheduledTrip == null || !scheduledTrip.isActive) {
                    pendingResult.finish()
                    return@launch
                }

                // 2. Initialize repository & Fetch Next Train (KEEPING YOUR LOGIC)
                val repository = MetroRepository(db)
                val cal = Calendar.getInstance()
                val nowMinutes = (cal.get(Calendar.HOUR_OF_DAY) * 60) + cal.get(Calendar.MINUTE)

                val nextTrain = repository.getNextTrainForStation(
                    scheduledTrip.sourceStationId,
                    scheduledTrip.destinationStationId,
                    nowMinutes
                )

                val trainInfo = if (nextTrain != null) {
                    "Next train at ${formatTime(nextTrain.arrival_time)}"
                } else {
                    "Check the app for the next available train."
                }

                // 3. Show Notification
                showNotification(context, scheduledTrip, trainInfo)

                // 4. AUTO-DELETE LOGIC (NEW)
                // If it's a one-time trip, delete it after notifying
                if (!scheduledTrip.isRecurring) {
                    val deletedTrip = scheduledTrip.copy(
                        isDeleted = true,
                        isActive = false,
                        syncState = "PENDING",
                        lastModified = System.currentTimeMillis()
                    )
                    dao.update(deletedTrip)
                }

            } catch (e: Exception) {
                Log.e("ScheduledTripReceiver", "Error processing alarm", e)
            } finally {
                pendingResult.finish() // Must call finish to tell the OS the receiver is done
            }
        }
    }

    private fun showNotification(context: Context, scheduledTrip: ScheduledTrip, trainInfo: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "scheduled_trips_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Trip Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for your scheduled metro journeys"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 5. UPDATE INTENT (NEW)
        // Passes specific flags so MainActivity knows to open the dialog
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // These extras match the logic we added to MainActivity
            putExtra("OPEN_SCHEDULE_DIALOG", true)
            putExtra("PREFILL_SOURCE", scheduledTrip.sourceStationName)
            putExtra("PREFILL_DEST", scheduledTrip.destinationStationName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            scheduledTrip.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification) // Ensure this icon exists
            .setContentTitle("🚇 Metro Trip Reminder")
            .setContentText("${scheduledTrip.sourceStationName} → ${scheduledTrip.destinationStationName}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Time to leave for your journey!\n$trainInfo"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .build()

        notificationManager.notify(scheduledTrip.id.toInt(), notification)
    }

    private fun formatTime(timeStr: String): String {
        return try {
            val cleanTime = timeStr.trim()
            val parts = cleanTime.split(":")
            var hours = parts[0].toInt()
            val minutes = parts[1].toInt()

            val amPm = if (hours >= 12) "PM" else "AM"
            var displayHour = hours % 12
            if (displayHour == 0) displayHour = 12

            String.format("%d:%02d %s", displayHour, minutes, amPm)
        } catch (e: Exception) {
            timeStr
        }
    }
}