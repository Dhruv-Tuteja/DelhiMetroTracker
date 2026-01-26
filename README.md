# 🚇 Delhi Metro Tracker

A real-time journey tracking app for Delhi Metro commuters with automated station detection, emergency contact alerts, and comprehensive travel analytics.

## 📱 Features

### Core Functionality
- 🚇 **Live Journey Tracking** - Automatic station detection using GPS and accelerometer sensors
- 📱 **Emergency SMS Alerts** - Real-time location updates sent to emergency contacts during journey
- 📊 **Travel Analytics** - Track your metro usage, carbon footprint savings, and station coverage
- 🗺️ **Smart Route Planning** - Shortest path calculation across all Delhi Metro lines
- 📍 **Manual Station Updates** - Override automatic detection when needed for accuracy
- 🔔 **Proximity Notifications** - Get alerted one stop before reaching your destination
- 📈 **Journey History** - Detailed trip logs with duration, route, and timestamps

### Dashboard Insights
- **Total Travel Time** - See how many hours you've spent on the metro
- **Carbon Savings** - Calculate CO₂ emissions saved vs car travel
- **Station Coverage** - Track unique stations visited across the network
- **Frequent Routes** - Quick-start your most traveled journeys
- **Travel Patterns** - Identify your peak travel times and days

## 🛠️ Built With

- **Kotlin** - Primary programming language
- **Room Database** - Local data persistence for trips and stations
- **Coroutines & Flow** - Asynchronous programming and reactive data streams
- **Google Location Services** - GPS-based station detection
- **Material Design 3** - Modern UI components and design system
- **Foreground Service** - Background journey tracking with notifications
- **SMS Manager** - Emergency contact notifications

## 📋 Requirements

- Android 7.0 (API 24) or higher
- Location permissions (Fine & Coarse)
- SMS permissions (for emergency alerts)
- Physical activity recognition (for accelerometer detection)
- Foreground service support

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 34
- Kotlin 1.9+

### Installation

1. Clone the repository
```bash
git clone https://github.com/Dhruv-Tuteja/delhi-metro-tracker.git
cd delhi-metro-tracker
```

2. Open the project in Android Studio

3. Sync Gradle and build the project

4. Run on an emulator or physical device
```bash
./gradlew installDebug
```

## 🏗️ Architecture

The app follows **MVVM (Model-View-ViewModel)** architecture:
```
app/
├── data/
│   ├── local/database/        # Room DB, DAOs, Entities (Trip, MetroStation)
│   ├── model/                 # UI data models
│   └── repository/            # Data operations & route planning
│
├── service/
│   └── JourneyTrackingService # Foreground service for GPS & SMS tracking
│
├── ui/
│   ├── dashboard/             # Analytics, history, and stats
│   ├── theme/                 # Material Design 3 theming
│   ├── MainActivity           # Station selection
│   └── TrackingActivity       # Live journey view
│
└── utils/
    ├── sensors/               # GPS + Accelerometer station detection
    ├── sms/                   # Emergency SMS alerts
    └── MetroNavigator         # Shortest path algorithm (Dijkstra)
```



## 🔑 Key Components

### JourneyTrackingService
- Runs as a foreground service with persistent notification
- Monitors GPS location every 10 seconds
- Uses accelerometer to detect train stops
- Sends SMS alerts at each station
- Vibrates before destination station

### MetroNavigator
- Implements Dijkstra's algorithm for shortest path
- Handles line transfers and route optimization
- Calculates estimated journey duration

### StationDetector
- Combines GPS proximity (200m threshold) with accelerometer patterns
- Reduces false positives during boarding
- Supports manual station override

## 📊 Database Schema

### Trip Entity
- Source/Destination stations
- Start/End timestamps
- Visited stations list
- Emergency contact info
- Journey status

### MetroStation Entity
- Station ID, name, coordinates
- Metro line information
- Connected stations

## 👨‍💻 Author

**Dhruv Tuteja**
- GitHub: [@Dhruv-Tuteja](https://github.com/Dhruv-Tuteja)
- Email: dhruvcodes2024@gmail.com
- LinkedIn: [linkedin.com/in/DhruvTuteja](https://www.linkedin.com/in/dhruv-tuteja-695b40282/)


**Note:** This app is not affiliated with or endorsed by Delhi Metro Rail Corporation (DMRC). It's an independent project built for commuter convenience.