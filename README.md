# TowerMap Android Studio project

A minimal Kotlin Android prototype that:
- reads the registered GSM, UMTS or LTE cell when Android makes it available;
- queries OpenCelliD for that cell's estimated coordinates;
- displays the result on an OpenStreetMap map using Leaflet;
- falls back to the last known GPS/network location if tower lookup fails.

## Open in Android Studio
1. Extract this ZIP.
2. In Android Studio, choose **Open** and select the extracted `TowerMapAndroidStudio` folder.
3. Install a compatible Android SDK and JDK 17 if prompted.
4. Let Gradle sync.
5. In `app/src/main/java/com/example/towermap/MainActivity.kt`, replace
   `PASTE_YOUR_OPENCELLID_KEY_HERE` with your OpenCelliD API key for personal testing.
6. Run on a real Android phone and grant permissions.

## Important limitations
- The prototype supports GSM, UMTS and LTE. It does not yet implement 5G NR.
- Android/carrier/phone combinations may not expose the registered cell details.
- Fallback uses the last known GPS/network fix, not a fresh fix.
- Cell tower coordinates are estimates and can be inaccurate or unavailable.
- Do not publish an APK with a private API key embedded. Put production API calls behind a backend.
- Follow OpenCelliD terms/licensing and OpenStreetMap tile usage policy and attribution requirements.
- Public OpenStreetMap tiles are not an unlimited free production tile-hosting service.

## Build
Use Android Studio's **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
