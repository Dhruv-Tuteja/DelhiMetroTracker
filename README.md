# 🚇 Delhi Metro Tracker

<div align="center">

![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)
![Language](https://img.shields.io/badge/Language-Kotlin-blue.svg)
![Architecture](https://img.shields.io/badge/Architecture-MVVM-orange.svg)

*Your companion for safe and smart Delhi Metro travel*

[Features](#-features) • [Screenshots](#-screenshots) • [Tech Stack](#-tech-stack) • [Architecture](#-architecture) • [Getting Started](#-getting-started) • [Contributing](#-contributing)

</div>

---

## 📖 Overview

Delhi Metro Tracker is a comprehensive Android application designed to enhance the metro commuting experience with real-time journey tracking, intelligent safety features, and seamless cloud synchronization. Built with modern Android development practices, the app combines GPS-based station detection, emergency SOS alerts, scheduled trip reminders, and detailed travel analytics—all while working offline and syncing across devices.

## ✨ Features

### 🚉 **Smart Journey Tracking**
- **Automatic Station Detection** - GPS + accelerometer fusion for accurate real-time positioning
- **Manual Station Override** - Update your location when underground GPS fails
- **Live Journey Timeline** - Visual progress bar showing completed and upcoming stations
- **Real-Time Speed Monitoring** - Track train speed during your journey
- **Proximity Alerts** - Get notified one station before your destination

### 🆘 **Advanced Safety System**
- **Triple SOS Trigger**
    - Volume Down Button: Rapid 5-press activation (works in pocket)
    - Notification Button: Quick access from tracking notification
    - In-App Button: Emergency button on tracking screen

- **Instant Emergency Response**
    - Automatic SMS with exact location to emergency contact
    - Emergency call placed immediately
    - SOS alert permanently logged with timestamp
    - Cloud backup for safety records

### 📱 **Automated SMS Updates**
- Journey start notification to emergency contact
- Location updates every station
- Arrival confirmation message
- Includes current station and estimated arrival time
- Works in background with unrestricted permissions

### 📅 **Scheduled Trip Reminders**
- **One-Time Trips** - Set reminders for specific dates
- **Recurring Trips** - Daily or custom day-of-week schedules
- **Smart Notifications**
    - Shows next available train timing from GTFS data
    - Tap notification to start journey instantly
    - Customizable reminder times (10 min, 20 min, 30 min, 1 hour)
- **Auto-Cleanup** - One-time trips deleted after notification
- **Cloud Sync** - Access schedules across devices

### ☁️ **Cloud Synchronization**
- **Firebase Integration** - Secure Google Sign-In authentication
- **Real-Time Sync**
    - Trip history across all devices
    - Scheduled trips and reminders
    - SOS alerts and safety logs
    - Dashboard statistics
- **Offline-First Architecture**
    - Works without internet
    - Automatic sync when online
    - Conflict resolution with timestamp-based merging
- **Soft Delete System** - Deleted trips archived, not lost forever

### 📊 **Comprehensive Analytics Dashboard**
- **Travel Statistics**
    - Total trips completed
    - Hours spent in transit
    - Carbon emissions saved (vs. car travel)
    - Unique stations explored

- **Smart Insights**
    - Most frequent routes with quick-start
    - Travel pattern analysis
    - Return journey suggestions
    - Station coverage map

- **Interactive History**
    - Swipe to delete (with undo)
    - Double-tap to restart journey
    - Long-press for detailed timeline
    - Share trip details with friends

### 🚆 **Real-Time Train Information**
- **GTFS Integration** - Live train schedules from Delhi Metro
- **Next Train Prediction** - See upcoming trains at any station
- **Direction-Aware Routing** - Correct platform and direction guidance
- **Schedule Accuracy** - Uses official DMRC timetable data

### 🎨 **User Experience**
- **Material Design 3** - Modern, clean interface
- **Dark Theme** - Eye-friendly for night travel
- **Gesture Controls**
    - Swipe down: Sync with cloud
    - Swipe left/right: Delete items
    - Double-tap: Quick restart
    - Long-press: Detailed view
- **Adaptive Icons** - Beautiful app icon across launchers

## 🏗️ Architecture

Built following **Clean Architecture** principles with **MVVM** pattern:

```
app/
├── data/
│   ├── local/database/
│   │   ├── entities/          # Room entities
│   │   │   ├── Trip           # Journey records with SOS flags
│   │   │   ├── MetroStation   # Station details with coordinates
│   │   │   ├── StopTime       # GTFS train schedule data
│   │   │   ├── ScheduledTrip  # Future trip reminders
│   │   │   └── UserSettings   # App preferences
│   │   │
│   │   ├── dao/               # Database access objects
│   │   │   ├── TripDao        # CRUD + sync operations
│   │   │   ├── StopTimeDao    # GTFS queries
│   │   │   └── ScheduledTripDao
│   │   │
│   │   └── AppDatabase        # Room database setup
│   │
│   ├── model/                 # UI data models
│   │   ├── TripCardData       # Dashboard trip cards
│   │   ├── DashboardStats     # Analytics data
│   │   └── DashboardUiState   # Screen states
│   │
│   └── repository/
│       ├── DashboardRepository    # Analytics aggregation
│       ├── MetroRepository        # Station & route logic
│       ├── RoutePlanner           # Dijkstra pathfinding
│       └── GtfsLoader             # GTFS data parsing
│
├── service/
│   └── JourneyTrackingService     # Foreground service
│       ├── GPS tracking (10s intervals)
│       ├── SMS automation (every 3rd station)
│       ├── SOS monitoring (volume button)
│       └── Proximity notifications
│
├── ui/
│   ├── dashboard/
│   │   ├── DashboardFragment      # Main stats screen
│   │   ├── DashboardViewModel     # State management
│   │   ├── TripHistoryAdapter     # RecyclerView adapter
│   │   └── ScheduledTripAdapter   # Scheduled trips list
│   │
│   ├── MainActivity               # Station selection & navigation
│   ├── TrackingActivity           # Live journey screen
│   └── theme/                     # Material Design 3 theming
│
├── receivers/
│   ├── ScheduledTripReceiver      # Alarm-based notifications
│   ├── ScheduledTripAlarmManager  # AlarmManager wrapper
│   └── BootReceiver               # Reschedule alarms after reboot
│
├── worker/
│   └── SyncWorker                 # Background cloud sync
│
└── utils/
    ├── sensors/                   # GPS + Accelerometer fusion
    ├── sms/                       # SMS automation
    └── MetroNavigator             # Shortest path algorithm
```

## 🛠️ Tech Stack

### **Core**
- **Kotlin** - Modern, concise, and safe programming language
- **Coroutines** - Asynchronous programming with structured concurrency
- **Flow** - Reactive data streams for real-time updates

### **Architecture Components**
- **ViewModel** - Lifecycle-aware UI state management
- **LiveData/Flow** - Observable data holders
- **Room** - SQLite abstraction for local persistence
- **WorkManager** - Reliable background sync scheduling

### **Dependency Injection**
- Manual DI with Repository pattern (lightweight approach)

### **UI/UX**
- **Material Design 3** - Latest design system
- **ViewPager2** - Smooth tab navigation
- **RecyclerView** - Efficient list rendering
- **SwipeRefreshLayout** - Pull-to-refresh functionality
- **Material Cards** - Elevated content containers

### **Firebase**
- **Authentication** - Google Sign-In
- **Firestore** - Cloud database for sync
- **Realtime Sync** - Automatic data synchronization

### **Location & Sensors**
- **Google Location Services** - Fused Location Provider
- **Sensor Manager** - Accelerometer for train stop detection

### **Background Processing**
- **Foreground Service** - Persistent journey tracking
- **AlarmManager** - Scheduled trip notifications
- **BroadcastReceiver** - System event handling

### **Permissions**
- Location (Fine & Coarse)
- SMS (Send & Receive)
- Phone (Emergency calls)
- Notifications
- Foreground Service
- Schedule Exact Alarms (Android 12+)

## 📊 Database Schema

### **Trip**
```kotlin
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceStationId: String,
    val destinationStationId: String,
    val startTime: Date,
    val endTime: Date?,
    val durationMinutes: Int?,
    val visitedStations: List<String>,
    val status: TripStatus,
    val emergencyContact: String,
    val smsCount: Int,
    
    // SOS fields
    val hadSosAlert: Boolean = false,
    val sosStationName: String? = null,
    val sosTimestamp: Long? = null,
    
    // Sync fields
    val syncState: String = "PENDING",
    val isDeleted: Boolean = false,
    val lastModified: Long = System.currentTimeMillis()
)
```

### **ScheduledTrip**
```kotlin
@Entity(tableName = "scheduled_trips")
data class ScheduledTrip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceStationId: String,
    val destinationStationId: String,
    val scheduledTimeHour: Int,
    val scheduledTimeMinute: Int,
    val reminderMinutesBefore: Int,
    val isRecurring: Boolean,
    val recurringDays: String?,  // "MON,TUE,WED"
    val scheduledDate: Long?,    // For one-time trips
    val isActive: Boolean = true,
    val isDeleted: Boolean = false,
    val syncState: String = "PENDING"
)
```

### **StopTime (GTFS)**
```kotlin
@Entity(tableName = "stop_times")
data class StopTime(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trip_id: String,           // Train identifier
    val stop_id: String,           // Station GTFS ID
    val arrival_time: String,      // "HH:MM:SS"
    val arrival_minutes: Int,      // Minutes since midnight
    val stop_sequence: Int         // Order in route
)
```

## 🚀 Getting Started

### Prerequisites
- **Android Studio** - Hedgehog (2023.1.1) or later
- **Android SDK** - API 34 (Android 14)
- **Kotlin** - 1.9.0+
- **Gradle** - 8.2+
- **Firebase Project** - For cloud sync (optional for local-only use)

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/Dhruv-Tuteja/delhi-metro-tracker.git
cd delhi-metro-tracker
```

2. **Open in Android Studio**
- File → Open → Select project directory
- Wait for Gradle sync to complete

3. **Firebase Setup (Optional)**
```bash
# Download google-services.json from Firebase Console
# Place in app/ directory
# Update google-services.json with your project details
```

4. **Build and Run**
```bash
# Debug build
./gradlew installDebug

# Release build
./gradlew assembleRelease
```

### Configuration

**Required Permissions** (in AndroidManifest.xml):
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.SEND_SMS" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

**Critical: Unrestricted Background Access**

For SOS to work when the phone is locked:
1. Settings → Apps → Delhi Metro Tracker
2. Battery → Unrestricted
3. Permissions → SMS, Phone, Location → Allow all the time

## 📱 Usage Guide

### Starting a Journey
1. Sign in with Google (optional, enables cloud sync)
2. Tap "Plan Journey"
3. Select source and destination stations
4. Add emergency contact (10-digit number)
5. Tap "Start Journey"
6. Keep app running in background (notification will appear)

### Triggering SOS
- **Volume Method**: Rapidly press Volume Down 5 times
- **Notification**: Tap SOS button in tracking notification
- **In-App**: Press red SOS button on screen

### Scheduling Trips
1. Menu → Schedule Trip
2. Select stations and time
3. Choose one-time or recurring
4. Set reminder (15/30/60 minutes before)
5. Tap notification when reminder fires to start instantly

### Dashboard Gestures
- **Swipe down**: Sync with cloud
- **Swipe trip left/right**: Delete (undo available)
- **Double-tap trip**: Restart same journey
- **Long-press trip**: View detailed timeline & share

## 🧪 Testing

Run unit tests:
```bash
./gradlew test
```

Run instrumented tests:
```bash
./gradlew connectedAndroidTest
```

## 📦 Project Structure Highlights

### Key Files
- `MainActivity.kt` - Entry point, station selection, navigation
- `JourneyTrackingService.kt` - Background GPS tracking & SMS automation
- `DashboardFragment.kt` - Analytics, history, and stats UI
- `ScheduledTripReceiver.kt` - Alarm-based notification handler
- `SyncWorker.kt` - Cloud synchronization worker
- `AppDatabase.kt` - Room database configuration
- `MetroNavigator.kt` - Dijkstra shortest path implementation

### GTFS Data
- `stop_times.txt` - Train schedules (loaded on first launch)
- Parsed and stored in Room database
- Queried for real-time "next train" predictions

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

### Code Style
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use meaningful variable and function names
- Add comments for complex logic
- Write unit tests for new features

## 🐛 Known Issues

- GPS accuracy may vary in underground sections
- SMS may not send if carrier blocks automated messages
- Scheduled alarms may not fire if battery saver is aggressive

**Workarounds:**
- Use manual station update for GPS issues
- Enable unrestricted background for SMS
- Add app to battery optimization whitelist

## 👨‍💻 Author

**Dhruv Tuteja**
- 🌐 GitHub: [@Dhruv-Tuteja](https://github.com/Dhruv-Tuteja)
- 📧 Email: dhruvcodes2024@gmail.com
- 💼 LinkedIn: [Dhruv Tuteja](https://www.linkedin.com/in/dhruv-tuteja-695b40282/)

## 🙏 Acknowledgments

- **Delhi Metro Rail Corporation (DMRC)** - For station data and GTFS feeds
- **Material Design Team** - For comprehensive design guidelines
- **Android Open Source Project** - For excellent documentation
- **Firebase** - For reliable cloud infrastructure

## ⚠️ Disclaimer

This application is an independent project and is **not affiliated with or endorsed by Delhi Metro Rail Corporation (DMRC)**. It is built for educational purposes and commuter convenience. Use at your own discretion.

---

<div align="center">

**Made with ❤️ for Delhi Metro Commuters**

⭐ Star this repo if you find it useful!

</div>
