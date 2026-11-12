# SkyLens

Android app that overlays the night sky on your camera view. Point the phone at the sky to see:

- **Stars**: about 5,000 stars down to magnitude 6, coloured by spectral type, with proper names.
- **Constellations**: lines and names for all 88 constellations.
- **Deep sky**: all Messier objects plus bright NGC/IC galaxies, nebulae and clusters.
- **Sun, Moon and planets**: Mercury to Pluto. The Moon is drawn with its current phase, and objects below the horizon are shown dimmed.
- **Satellites**: live positions from CelesTrak orbit data, propagated on the phone with SGP4. Included: ISS/Tiangong, the brightest satellites, science satellites, and optionally Starlink, weather satellites and recent launches. Satellites in sunlight are bright green and those in Earth's shadow are grey. Tap one to see its path for the next 10 minutes.
- **Aircraft**: live ADS-B traffic within about 185 km (adsb.lol, with airplanes.live as a fallback), dead-reckoned between updates. Shows callsign, type, altitude, speed and the next 3 minutes of flight path.

## Controls

| Action | How |
|---|---|
| Details about an object | Tap it (tap the card to dismiss) |
| Zoom | Pinch (zooms the camera too). Double-tap to reset |
| Find something | 🔍 Find, then search and follow the yellow arrow |
| Fix compass error | Tap a bright object, centre it in the circle, then tap 🎯 Align |
| Sky only (no camera) | Camera chip |
| Red night-vision mode | Night chip |
| Satellite groups, refresh, about | ⋮ More |

## Building

GitHub Actions (`.github/workflows/android.yml`) builds a debug APK on every push. Download it from the **SkyLens-debug-apk** artifact of the workflow run.

To build locally you need the Android SDK (API 34) and JDK 17:

```
./gradlew testDebugUnitTest assembleDebug
```

The astronomy code is in `app/src/main/java/com/rj/skylens/astro`. Unit tests compare it against PyEphem and the reference SGP4 implementation.

## Data sources

HYG star database v4.1 (CC BY-SA), Stellarium modern constellation lines (GPL), OpenNGC (CC BY-SA), CelesTrak GP element sets, and adsb.lol / airplanes.live ADS-B feeds (ODbL). `tools/gen_assets.py` regenerates the bundled catalogues.
