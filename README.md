# Safety App (Nirvoy)

**Personal Safety Android Application**

A comprehensive safety application designed to help users in emergency situations by providing multiple emergency alert mechanisms, live location tracking, and quick communication with emergency contacts.

---

## 📱 Features

### Emergency Alert Systems
- **SOS Button** - Instant emergency alert with live location tracking
- **Shake Detection** - Triple shake to trigger emergency (works in background)
- **Power Button Trigger** - Triple press power button to activate emergency countdown
- **Voice Recognition** - AI-powered voice detection for emergency keywords

### Location Services
- **Live Location Tracking** - Real-time GPS tracking with Firebase integration
- **Share Location** - Send live tracking link via SMS or WhatsApp
- **Location History** - Track movement history during emergency sessions
- **Safe Zone Alerts** - Geofencing for predefined safe zones

### Communication
- **SMS Alerts** - Send emergency messages to saved contacts
- **WhatsApp Integration** - Share emergency alerts via WhatsApp
- **Emergency Contacts** - Manage multiple emergency contacts
- **Custom Message Templates** - Personalize emergency messages

### Safety Tools
- **Video Recording** - 60-second background video recording during emergency
- **Audio Recording** - Voice recording capability
- **Emergency Mode** - ICE (In Case of Emergency) information display
- **Countdown Timer** - Configurable countdown before sending alerts

---

## 🛠️ Technologies Used

### Android
- **Language**: Java
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: Activity-based with Service components

### Firebase Services
- **Firebase Authentication** - User authentication with Facebook login
- **Firebase Realtime Database** - Store user data, contacts, and live locations
- **Firebase Cloud Messaging** - Push notifications

### Google Services
- **Google Play Services Location** - GPS and location tracking
- **Fused Location Provider** - High-accuracy location updates

### Third-Party Libraries
- **Facebook SDK** - Social login integration
- **TensorFlow Lite** - AI/ML for voice detection
- **Material Design Components** - Modern UI components

---

## 📐 Architecture

### Key Components

#### Activities
- `MainActivity` - Home screen with navigation and SOS button
- `LiveLocation` - Live location sharing interface
- `PopupCountdownActivity` - Emergency countdown dialog
- `SettingsActivity` - App configuration
- `ProfileActivity` - User profile management
- `SaveSMSActivity` - Emergency contacts management
- `AIVoiceActivity` - Voice detection settings
- `SafeZoneActivity` - Safe zone management
- `InCaseEmergencyActivity` - ICE information

#### Services
- `LocationTrackingService` - Background GPS tracking with foreground notification
- `ShakeDetectionService` - Background shake detection
- `VideoRecordingService` - Background video recording

#### Helpers
- `LiveLocationManager` - Reusable component for live tracking
  - `generateTrackingUrl()` - Create tracking URLs
  - `startTrackingService()` - Start background tracking
  - `stopTracking()` - End tracking session

- `EmergencyMessageHelper` - Handle emergency communications
  - `sendCustomMessage()` - Send SMS/WhatsApp to contacts
  - `loadContacts()` - Fetch emergency contacts from Firebase

#### Receivers
- `PowerButtonReceiver` - Detect triple power button press
- `ShakeDetector` - Accelerometer-based shake detection

---

## 🚀 Setup Instructions

### Prerequisites
1. Android Studio Arctic Fox or later
2. JDK 11 or higher
3. Android device/emulator with API 24+

### Firebase Setup
1. Create a new Firebase project at [console.firebase.google.com](https://console.firebase.google.com)
2. Add Android app to Firebase project
3. Download `google-services.json` and place in `app/` directory
4. Enable Firebase Authentication (Facebook provider)
5. Enable Firebase Realtime Database
6. Set up database rules:

```json
{
  "rules": {
    "Users": {
      "$uid": {
        ".read": "$uid === auth.uid",
        ".write": "$uid === auth.uid"
      }
    },
    "LiveLocations": {
      "$shareId": {
        ".read": true,
        ".write": "auth != null"
      }
    }
  }
}
```

### Facebook Login Setup
1. Create Facebook App at [developers.facebook.com](https://developers.facebook.com)
2. Add Facebook App ID to `strings.xml`:
```xml
<string name="facebook_app_id">YOUR_APP_ID</string>
<string name="fb_login_protocol_scheme">fbYOUR_APP_ID</string>
```
3. Add key hash to Facebook app settings

### Build & Run
```bash
# Clone the repository
cd Safety-App

# Open in Android Studio or build via command line
gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔐 Permissions Required

### Critical Permissions
- `ACCESS_FINE_LOCATION` - GPS location tracking
- `ACCESS_BACKGROUND_LOCATION` - Background location updates (Android 10+)
- `SEND_SMS` - Send emergency SMS messages
- `RECORD_AUDIO` - Voice detection and recording
- `CAMERA` - Video recording during emergencies

### Additional Permissions
- `VIBRATE` - Haptic feedback
- `INTERNET` - Firebase and network communication
- `FOREGROUND_SERVICE` - Background tracking service
- `POST_NOTIFICATIONS` - Emergency notifications (Android 13+)
- `READ_CONTACTS` - Select emergency contacts
- `WAKE_LOCK` - Keep device awake during emergency

---

## 📊 Live Tracking System

### How It Works
1. **URL Generation**: Creates unique tracking URL per session
   - Format: `https://safetyapp-2042f.web.app/track?id={shareId}`
   - ShareId: `{userId}_{timestamp}`

2. **Real-Time Updates**: Location updates every 2 seconds
   - Stored in Firebase at `/LiveLocations/{shareId}/currentLocation`
   - History maintained at `/LiveLocations/{shareId}/locationHistory`

3. **Accuracy Filtering**: Only updates with accuracy < 50 meters
   - Ensures reliable tracking
   - Reduces noise from GPS drift

4. **Auto Cleanup**: Sessions older than 24 hours are automatically deleted

### Usage in Code
```java
// Initialize manager
LiveLocationManager locationManager = new LiveLocationManager(context);

// Start tracking
LiveLocationManager.TrackingInfo trackingInfo = locationManager.startTracking();
String url = trackingInfo.getTrackingUrl();
String shareId = trackingInfo.getShareId();

// Send URL to contacts
String message = "Emergency! Track me: " + url;
emergencyHelper.sendCustomMessage("sms", message);

// Stop tracking
locationManager.stopTracking(shareId);
```

---

## 🎯 Emergency Flow

### SOS Button Press
```
User presses SOS → Select method (SMS/WhatsApp) → Fetch emergency message
→ Start live tracking → Generate tracking URL → Send to emergency contacts
→ Show notification → Continue tracking in background
```

### Shake Detection
```
Shake detected (3x) → Launch PopupCountdownActivity → Show countdown
→ User can cancel OR countdown finishes → Fetch emergency message
→ Start live tracking → Send alerts → Continue tracking
```

### Power Button Triple Press
```
Power button pressed 3x → Launch PopupCountdownActivity → Request permissions if needed
→ Show countdown → User can cancel OR countdown finishes → Send emergency alert
→ Start live tracking
```

---

## 📁 Project Structure

```
Safety-App/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/example/safetyapp/
│   │   │   │   ├── MainActivity.java
│   │   │   │   ├── LiveLocation.java
│   │   │   │   ├── PopupCountdownActivity.java
│   │   │   │   ├── LiveLocationManager.java
│   │   │   │   ├── LocationTrackingService.java
│   │   │   │   ├── helper/
│   │   │   │   │   └── EmergencyMessageHelper.java
│   │   │   │   ├── service/
│   │   │   │   │   ├── ShakeDetectionService.java
│   │   │   │   │   └── VideoRecordingService.java
│   │   │   │   └── [Other Activities...]
│   │   │   ├── res/
│   │   │   │   ├── layout/
│   │   │   │   ├── drawable/
│   │   │   │   ├── values/
│   │   │   │   └── anim/
│   │   │   └── AndroidManifest.xml
│   ├── build.gradle
│   └── google-services.json
├── gradle/
├── build.gradle
├── settings.gradle
└── README.md
```

---

## 🐛 Troubleshooting

### SMS Not Sending
- Ensure `SEND_SMS` permission is granted
- Check that emergency contacts are properly configured
- Verify device has SMS capability
- Check LogCat for error messages with tag `EmergencyHelper`

### Location Not Updating
- Verify location permissions granted (including background location)
- Check GPS is enabled on device
- Ensure Firebase Realtime Database rules allow writes
- Monitor LogCat with tag `LocationTracking`

### Shake Detection Not Working
- Verify device has accelerometer sensor
- Check `ShakeDetectionService` is running (notification should appear)
- Adjust shake sensitivity in app settings
- Ensure app has necessary background permissions

---

## 📈 Future Enhancements

- [ ] Add panic button widget for home screen
- [ ] Implement two-way communication with emergency contacts
- [ ] Add medical information storage (blood type, allergies, etc.)
- [ ] Integration with local emergency services
- [ ] Multi-language support
- [ ] Offline emergency mode
- [ ] Battery optimization improvements

---

## 📄 Privacy Policy

Safety App (Nirvoy) is committed to protecting your privacy:

- **Location Data**: Collected only during emergency situations
- **Contacts**: Stored locally and in your Firebase account
- **Messages**: Content not stored by the app
- **Camera/Microphone**: Access only when explicitly triggered
- **Facebook Login**: Only accesses public profile name and email

For complete privacy details, contact: shraboni.diit@gmail.com

---

## 👨‍💻 Development

### Contributors
- Project developed for personal safety and emergency response

### Contact
📧 Email: shraboni.diit@gmail.com

---

## 📜 License

This project is for educational and personal use. All rights reserved.

---

## 🙏 Acknowledgments

- Firebase for real-time database and authentication
- Google Play Services for location tracking
- Facebook SDK for social authentication
- TensorFlow for AI/ML capabilities
- Material Design for UI components

---

**Stay Safe with Nirvoy!** 🛡️
