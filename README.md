# RJ — your car's own radio jockey

An Android app that gives your car a personality and runs a live, one-listener radio show on every drive.
It knows **you** (your name, the key stuff in your life, what it learned on earlier drives), **your day**
(calendar), **the drive** (time, distance, speed, slow traffic, the area you're passing through), and **the
world** (weather, headlines, live web search). It talks between songs, cues the music in your music app,
and you can talk back.

```
 ┌──────────── on the phone ─────────────┐        ┌──────────── cloud ───────────┐
 │ Calendar ─┐                           │        │                              │
 │ GPS/drive ├─► ShowContext ─► Planner ─┼─ cue ─►│ Claude (persona + memories   │
 │ Weather  ─┤   (live briefing)  (what  │        │  + web_search for news)      │
 │ Headlines─┤                    moment)│◄─ JSON ┤  → speech, next song,        │
 │ Now playing (Spotify/YT Music…) ┘     │        │    intro tag, listen?, facts │
 │                                       │        └──────────────────────────────┘
 │ Voice (TTS, ducks/pauses music) ◄─────┤
 │ Music app ◄─ play-from-search         │
 │ Mic ─► speech-to-text ─► REPLY cue    │
 └───────────────────────────────────────┘
```

## What a drive sounds like

- **Opener**: a greeting that fits the exact moment ("Monday, 8:10, drizzle, and your design review is at
  9:30, so let's get you there in a good mood"), then the first song.
- **Links** between songs: back-announce, quick asides, the next pick chosen for your taste, the time of
  day, the weather and the traffic. Sometimes a line over the song's intro with the music ducked, radio-style.
- **Rotating features**, planned so the show has rhythm rather than non-stop chatter:
  - news breaks (Claude searches the web live, mixing world, local, and your interests)
  - agenda nudges before calendar events, with a pep talk if it's a big one
  - weather alerts when rain is coming or conditions change
  - local colour about the town you're passing through
  - personal check-ins: it asks you something, listens, and remembers the answer for future drives
  - traffic-jam mode: calmer, funnier, feel-good songs
- **Podcast segments**: you and your co-host (default "Max", a dry-witted sceptic) debate a topical
  issue from today's news in two distinct voices, then ask which side you're on.
- **Quiz rounds**: three questions read aloud with A/B/C choices. Answer out loud and the phone judges
  instantly. Questions are themed on where you are, the news, music and your interests. With music on
  your phone you also get **name that tune**. Scores and personal bests are kept across drives.
- **Local trivia** whenever you roll into a new town, plus on request.
- **One-tap features** on the home screen: *Quiz me*, *Podcast*, *Local trivia*, *News*, *Joke*. Or just
  say it: "quiz me", "tell me about this place", "let's have a debate".
- **Drive recap** at sign-off (distance, time, songs, quiz score), and it remembers how many drives you've
  done together.
- **Talk back** any time: tap **Talk** (or *Talk* on the notification) and ask for a song, a mood, a
  score, or just chat.
- **Sign-off** when you end the drive.

## Offline mode

When there's no signal (or with **Brain → Always offline**), a built-in host takes over seamlessly. It
still knows the time, your calendar, the drive, the last weather it saw and your music library, and it
runs ~50 quiz questions, name-that-tune, place trivia for 30+ cities, facts, jokes and scripted
mini-podcasts with your co-host. Set **Music → Phone library** (or *Auto*) so songs play from files
on your phone with the built-in player.

## Voices

- **ElevenLabs (natural)**: paste an ElevenLabs API key and two voice IDs (host and co-host) in
  Settings → Voice. Lines are pre-rendered while the previous song plays, so they start instantly.
- **On-device**: the app prefers Google's TTS engine and picks its highest-quality voices, with a different
  voice for the co-host. Install "Speech Services by Google" and a high-quality voice pack for the best results.

## Project layout

| Module | What's in it |
|---|---|
| `core/` (pure Kotlin/JVM, unit-tested) | `ShowPlanner` (segment rotation), `ShowContext` (live briefing), `PromptBuilder` (persona + show rules), `ClaudeBrain` (Claude API via the official Java SDK, web search, prompt caching), `WeatherClient` (Open-Meteo, no key), `NewsClient` (Google News RSS, no key) |
| `app/` (Android, Jetpack Compose) | `ShowEngine` (the show loop, prefetching the next link while a song plays), `RjService` (foreground service), `DriveTracker` (GPS), `CalendarSource`, `Voice`/`Ears` (TTS + speech recognition), `MusicController` (Spotify / YouTube Music / Apple Music / Amazon Music via media sessions & play-from-search), Home and Settings screens |

## Getting it on your phone

1. **Get the APK.** Every push builds one in GitHub Actions: open *Actions → Android build → latest run →
   Artifacts → `rj-debug-apk`*. Or build it yourself in Android Studio (open the repo, run `app`), or run
   `./gradlew :app:assembleDebug`.
2. **Install** it (allow "install unknown apps" for your browser or file manager).
3. Open **Settings** in the app:
   - Paste a **Claude API key** from <https://console.anthropic.com> (it's stored only on the phone).
     Without one the app runs a basic offline show.
   - Name your car, pick a character (breakfast-show host, late-night smooth, cheeky sidekick, music-nerd
     curator, calm co-pilot) and add quirks if you like.
   - Tell it about you: name, city, interests, music taste, and **key stuff** (family, goals, what's
     happening at work), plus the language you want, e.g. "English with a sprinkle of Hindi".
   - Music: **Auto** (streaming app online, phone library offline), **Phone library** or **Streaming app**.
     For streaming, tap **Allow notification access**: the RJ needs it to press play in Spotify/YouTube
     Music after a search, see what's playing and know when a song ends. Without it, apps like Spotify
     only open their search results.
4. Mount the phone, connect to the car over Bluetooth, tap **Start drive**, and grant mic, location and
   calendar access when asked.

## Costs & privacy

- Each segment is one Claude request (web search only when the moment calls for it). The default model is
  `claude-opus-5-5` at low effort, and the system prompt and show history are prompt-cached between
  segments. You can switch to `claude-sonnet-5-5` or `claude-haiku-5-5` in Settings for a cheaper,
  faster RJ.
- Calendar, location and your profile stay on the phone. Only the live briefing (time, drive stats,
  locality, upcoming events, weather, headlines, now playing) goes to Claude with each segment.
- Server-side refusal fallback (`fallbacks: "default"`) is enabled, so the show keeps going if a request
  gets declined.

## Development

```bash
./gradlew :core:test                       # brain/planner/feeds tests (no Android SDK needed: add -PcoreOnly=true)
./gradlew :app:assembleDebug               # needs the Android SDK (ANDROID_HOME)
```

## Known limits / roadmap

- **Android Auto**: not wired up yet. Google restricts Auto apps to templated media/messaging UIs, so the
  next step is exposing the show as a `MediaBrowserService` "station".
- **Voice quality**: uses the phone's on-device TTS (pick a high-quality voice in Android's text-to-speech
  settings). A natural cloud voice is a drop-in replacement for `Voice.say()`.
- **Wake word**: talking is tap-to-talk (big button or notification action). A hands-free wake word needs an
  on-device hotword engine.
- **Streaming apps** decide for themselves how to handle "play this song". The RJ checks that playback
  actually started and retries through the app's media session, but if an app still refuses, *Auto*
  switches to your phone's library.
- Navigation ETA isn't read from Maps; type the destination on the home screen or put a location on the
  calendar event.
