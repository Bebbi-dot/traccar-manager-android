# [Traccar Manager for Android](https://www.traccar.org/manager)

**This repository contains the old version of the app.**

Development has moved to a new repository, where you can find the latest version and ongoing updates:

👉 [https://github.com/traccar/traccar-manager](https://github.com/traccar/traccar-manager)

No further development will take place here. Please use the new repository for issues, pull requests, and the latest code.

---

## 🔴 Compass Feature

The original Traccar Manager has been extended with a **live target compass**. This feature enables navigation to Traccar devices directly from the Manager app.

### How It Works

```
Position A (Own Device)    →    Position B (Target Device)
       ↓                            ↓
LatestPositions (Traccar Server)  LatestPositions (Traccar Server)
(both fetched from the same store at the same interval)
       ↓                            ↓
   myLat/myLon                   targetLat/targetLon
       ↓                            ↓
         bearing = Haversine(A_lat, A_lon, B_lat, B_lon)
         Needle rotation = bearing - azimuth (phone heading)
```

No additional GPS polling. Both positions come from the Traccar Server's `LatestPositions` store, respecting the client's configured update interval. The compass only reads data — it never requests GPS itself.

### Usage

1. **Open the app and log in** — the compass only appears after successful login
2. **Select "📍 Own Device"** — click the button in the compass overlay, choose your device from the list (this sets Position A)
3. **Click a target device in the list** — the device table on the left selects the target (this sets Position B)
4. **🧭 Compass button** — toggle overlay visibility
5. **Rose** — rotates with the magnetometer (TYPE_ROTATION_VECTOR with fallback to TYPE_MAGNETIC_FIELD + TYPE_ACCELEROMETER)
6. **Needle** — points from A to B, corrected for phone orientation
7. **Distance** — straight line distance in meters/kilometers

### Technical Details

| Aspect | Implementation |
|--------|----------------|
| Sensor | `Sensor.TYPE_ROTATION_VECTOR` → Fallback: `TYPE_MAGNETIC_FIELD` + `TYPE_ACCELEROMETER` with `getRotationMatrix()` |
| Own position | From `LatestPositions` (Traccar Server) — no GPS polling |
| Target selection | Poll on `Ext.ComponentQuery.query('devicesView')[0].getSelectionModel()` (500ms interval) |
| Bearing | Haversine formula in JS (`bearingDistance()`) |
| Needle rotation | `ctx.rotate((bearing - azimuth) * PI / 180)` |
| UI | JS injection in WebView after `onPageFinished` → user login → `injectCompassJS()` |
| Persistence | localStorage for selected "Own Device" |
| JS injection timing | Mini-starter polls `Traccar.app.getUser()`, full compass code injected only after login |

### Modified Files

- `app/src/main/java/org/traccar/manager/MainActivity.kt` — CompassInterface + sensor fallback + GPS
- `app/src/main/java/org/traccar/manager/MainFragment.kt` — JS injection, `injectCompassJS()`, `AppInterface.compass_ready`
- `app/build.gradle` — Removed Firebase/Google-Services (dummy json for build)

### Build

```bash
export ANDROID_HOME=/path/to/sdk
./gradlew assembleRegularDebug
adb install -r app/build/outputs/apk/regular/debug/app-regular-debug.apk
```

### Logging

- Kotlin logs: `adb logcat -s TrackerCompass`
- JS logs: `adb logcat | grep "compass:"`

### Known Limitations

- No OpenLayers click handler on the map (Traccar's own handler consumes the event)
- No map centering on own position
- Needle points straight line, not turn-by-turn
