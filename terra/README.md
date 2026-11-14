# TERRA

A persistent, location-based discovery game that turns the street, park or backyard you are
standing in into a living field guide. Instead of fictional monsters, TERRA reveals the ecology
that is already there — and rewards patience, ecological literacy and real-world care.

Native Android · Kotlin · Jetpack Compose · offline-first · no account, no ads, no trackers.

## Build

```bash
cd terra
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # ecology logic + screen rendering tests
./gradlew testDebugUnitTest -Proborazzi.test.record=true   # also writes screenshots to app/build/shots/
```

Needs JDK 17+ and the Android SDK (platform 35). Min Android 8.0 (API 26). The GitHub workflow
`.github/workflows/terra.yml` builds the APK and attaches it to each run as an artifact.

## What is implemented

| Design pillar | In the app |
|---|---|
| **Bio-scans** | Photograph (or pick) an image → an on-device labeller places it as bird / plant / tree / insect → the guide ranks species by region, season and the habitat you stand in → you confirm, and add life stage, morph, heritage-tree flag and a note. |
| **Acoustic sweeps** | 10 s of listening → FFT-based measurement of pitch band, pulse rate and tonality → ranked candidates among local birds, frogs and insects, each with a description of its call to match by ear. Audio is analysed in memory and discarded. |
| **Phenotypes & life stages** | Per-species stages (egg → adult, tracks & signs, seed pods…) and morphs (breeding plumage, autumn foliage, leucistic, colour forms) recorded on the Codex entry and rewarded with badges. |
| **Symbiosis webs / trophic chains** | Every species has locked connection nodes — host plant, prey, pollination partner, shelter, habitat, seasonal presence. They open only when the partner is logged within 1.5 km, the species is logged inside the right habitat type, or it is seen in a second season. Ten sightings of the same hawk complete nothing; completing the web unlocks the deeper entry. |
| **Habitats & micro-biomes** | Real parks, woods, wetlands and water charted from OpenStreetMap around you, plus hand-marked micro-habitats. Each has a 0–100 health score that rises with native species richness and verified stewardship and falls with unaddressed invasives. |
| **Bounties & bio-blitzes** | Seasonal briefs whose only deadline is the real turn of the season (hemisphere-aware). |
| **Living atlas** | A sepia OpenStreetMap with your records, habitats and a toggle showing exactly what the public atlas would show. |
| **Stillness mode** | A 10-minute sit that runs with the screen locked (foreground service), listening in 3-second windows and noting candidate voices for you to confirm afterwards. |
| **Stewardship verification** | Before/after photo pairs; automatically checked for same place (≤ 60 m) and elapsed time (≥ 10 min). Three verified actions make you Steward of a habitat. |
| **Poaching protection** | Sensitive species (nesting raptors, orchids, roosts…) are snapped to a 0.2° grid in the public view and public export; exact coordinates appear only in the separate research-grade export. |
| **Phenological seasons** | Season, focus and bounties are derived from the date and your latitude. |
| **Legacy landmarks** | Adopt a tree or pond and build a dated timeline of photos, notes and girth measurements; a full archive (JSON + photos) can be saved and restored on another phone. |
| **Eyes-up UX** | Walk mode keeps location alive with the screen off and gives a haptic pulse — plus a chime panned toward the habitat — when you cross into one. No push notifications, streaks or timers. |

## What is deliberately *not* claimed

- **Species-level recognition.** The bundled vision model is a general image labeller, and the
  acoustic matcher is a transparent heuristic, not a trained classifier. Both narrow the field and
  the observer decides. `Vision` and `AcousticMatcher` are the two seams where a species-level model
  (e.g. a TFLite plant/bird model, or BirdNET) would slot in.
- **A server.** There is no backend yet, so community peer review, scientist-issued regional
  bounties and the shared public map are not live. The app ships sample briefs and produces the
  open-data files (`public` and `research` GeoJSON with Darwin Core fields) that such a service
  would ingest.
- **A global field guide.** The bundled guide has 91 widespread species with curated ecological
  links, covering every continent thinly. Anything else can be logged as "not in the guide".
  The guide's source of truth is `tools/build_catalog.py`.
- **Device testing.** The logic is unit-tested and every screen is render-tested, but camera,
  microphone, GPS, map tiles and the two foreground services have not yet been exercised on a
  physical phone.

Map tiles come from the public OpenStreetMap tile servers, which are fine for development but
must be replaced with a proper tile provider before any wide release.

## Layout

```
app/src/main/java/org/terra/app/
  model/     data classes (also the Room tables)
  domain/    pure logic: Codex, habitat health, bounties, badges, acoustics, fuzzing, export, OSM
  data/      Room database, repository, photo storage
  sensing/   location, microphone, image labels, haptics/chime, Stillness and Walk services
  ui/        Compose screens and the field-journal theme
```
