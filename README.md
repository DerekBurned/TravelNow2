# TravelNow - Safety Reporting Application

An Android mobile application that enables travelers to report and view safety information about locations worldwide, helping users make informed decisions about their travel destinations.

## Features

### 🗺️ Interactive Map Interface
- Google Maps integration with full navigation controls
- Multiple map types: Normal, Satellite, Terrain, Hybrid
- Real-time GPS location tracking
- Smart place search with autocomplete (Google Places API)
- Custom zoom controls

### 📍 Safety Reporting System
- Submit reports by long-pressing any location on the map
- Four safety levels with color-coded indicators:
    - **🟢 Safe** - Area is secure
    - **🟡 Be Cautious** - Exercise care
    - **🟠 Unsafe** - Avoid if possible
    - **🔴 Dangerous** - High risk area
- Add detailed comments to each report
- Anonymous reporting via Firebase Authentication

### 👁️ Visual Safety Indicators
- 500-meter radius circles around reported locations
- Color-coded map markers matching safety levels
- Semi-transparent overlays for better visibility
- Tap markers to view report details

### 👥 Community Features
- Upvote/downvote reports to verify accuracy
- View nearby reports in list format
- Automatic filtering by distance (5km/20km/50km)
- Timestamp display (Today, Yesterday, X days ago)
- Sort by recency

### 🔍 Smart Location Queries
- Geohash-based spatial indexing
- Efficient nearby report searches
- Haversine formula for accurate distance calculation
- Dynamic report loading based on map zoom level

## Technical Stack

- **Language:** Kotlin
- **Architecture:** MVVM (Model-View-ViewModel)
- **Backend:** Firebase
    - Firestore Database
    - Anonymous Authentication
- **Maps & Location:**
    - Google Maps SDK for Android
    - Google Play Services Location API
    - Google Places SDK
- **UI Components:**
    - ViewBinding
    - RecyclerView
    - Bottom Sheets
    - Material Design Components

## Project Structure

```
com.example.travelnow/
├── Activities/
│   └── MainActivity.kt          # Entry point
├── MapsActivity.kt              # Main map interface
├── SafetyReportAdapter.kt       # RecyclerView adapter
├── MyApplication.kt             # Application class
├── ViewModel/
│   └── SafetyViewModel.kt       # ViewModel layer
├── repository/
│   └── SafetyRepository.kt      # Data layer
├── models/
│   ├── SafetyReport.kt          # Report data model
│   └── SafetyLevel.kt           # Safety level enum
└── utils/
    └── GeoUtils.kt              # Geohash & distance calculations
```

## Database Schema

**Collection:** `safety_reports`

```javascript
{
  id: String,              // Auto-generated document ID
  latitude: Double,        // Location coordinate
  longitude: Double,       // Location coordinate
  areaName: String,        // Location name/address
  safetyLevel: String,     // SAFE | BE_CAUTIOUS | UNSAFE | DANGEROUS
  comment: String,         // User's detailed report
  userId: String,          // Anonymous user ID
  userName: String,        // Default: "Anonymous User"
  upvotes: Number,         // Community verification
  downvotes: Number,       // Community verification
  geohash: String,         // For location-based queries
  timestamp: Timestamp     // Server-generated
}
```

## Firestore Security Rules

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /safety_reports/{reportId} {
      allow read: if request.auth != null;
      allow create: if request.auth != null 
                    && request.resource.data.userId == request.auth.uid;
      allow update: if request.auth != null;
      allow delete: if request.auth != null 
                    && resource.data.userId == request.auth.uid;
    }
  }
}
```

## Setup Instructions

### Prerequisites
- Android Studio Arctic Fox or newer
- Android SDK API 24+ (Android 7.0)
- Google Maps API Key
- Google Places API Key
- Firebase project

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/yourusername/travelnow.git
   cd travelnow
   ```

2. **Configure Firebase**
    - Create a Firebase project at [Firebase Console](https://console.firebase.google.com/)
    - Enable Anonymous Authentication
    - Create a Firestore Database
    - Download `google-services.json` and place it in `app/` directory

3. **Add API Keys**

   Create `local.properties` (if not exists) and add:
   ```properties
   MAPS_API_KEY=your_google_maps_api_key
   PLACES_API_KEY=your_google_places_api_key
   ```

4. **Build and Run**
   ```bash
   ./gradlew build
   ```
   Or run directly from Android Studio

## Permissions Required

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Usage

1. **View Safety Reports**
    - Open the app to see the map with your current location
    - Safety reports appear as colored circles and markers
    - Tap markers to view report details

2. **Submit a Report**
    - Long-press any location on the map
    - Select the safety level
    - Add a detailed comment
    - Submit the report

3. **Search Locations**
    - Use the search bar at the top
    - Type any location name
    - Select from autocomplete suggestions

4. **Vote on Reports**
    - Tap the reports list icon
    - View all nearby reports
    - Upvote accurate reports
    - Downvote inaccurate reports

## Use Cases

- **Travel Planning:** Check safety conditions before visiting destinations
- **Tourist Safety:** Share real-time experiences with the community
- **Solo Travelers:** Make informed decisions about areas to visit
- **Local Knowledge:** Build collective awareness about neighborhood safety
- **Emergency Alerts:** Warn travelers about dangerous situations

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Acknowledgments

- Google Maps Platform
- Firebase
- Android Open Source Project
- Material Design

## Contact

For questions or support, please open an issue on GitHub.

---

**Note:** This application relies on community-generated content. Always verify safety information through official sources and local authorities.