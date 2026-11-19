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

## Senses: what makes it personal

| Source | What the RJ does with it | Setup |
|---|---|---|
| Phone calendar (incl. Outlook app sync) | Agenda nudges, pep talks, birthdays | Calendar permission |
| **Outlook / Microsoft 365** (Graph) | Meetings with organisers, important unread mail, *emails the organiser if you're running late* | Azure client ID + sign in (below) |
| **WhatsApp, SMS, Telegram, Signal, Teams, Slack, Outlook & Gmail apps** | Roundups between songs, VIPs straight away, "read it", **reply by voice** (always confirmed) | Notification access |
| **Samsung Health / Galaxy Watch** (Health Connect) | Gentler after a short night, cheers a workout, calms things down when your heart rate is up in traffic, break reminders on tired or late drives | Settings → Connect health |
| **Car**: Tata iRA (or other connected-car app) alerts | Mentions door/tyre/charge/service alerts | Notification access |
| **Car**: Bluetooth OBD-II (ELM327) adapter | Fuel or EV charge %, check-engine light, coolant, 12V battery | Pair adapter, pick it in Settings |
| **Car Bluetooth** | "Connected to your car: tap to go on air" when you get in | Learned automatically on first drive |
| **Traffic** (OpenStreetMap open data: Nominatim + OSRM) | ETA, delay vs an empty road, *"you'll be 8 min late for Standup"* | Destination field, or the next meeting's address |
| Traffic, live (TomTom free tier, optional) | Live jams, road speed vs normal, accidents and closures ahead | TomTom key |
| News: local, national, world + your interests | News breaks and podcast topics | None |
| Weather here and at the destination | Weather moments, rain warnings | None |
| Your trips | "This usually takes you 25 minutes" | Learned automatically |

Message text is only shared with the AI if you switch that on; otherwise the AI sees who wrote and which
app. Reading messages aloud happens on the phone. Nothing is ever sent without a spoken "yes".

### Outlook (Microsoft 365) setup, about 2 minutes

1. Go to <https://portal.azure.com> → **App registrations** → **New registration**. Name it "RJ", choose
   *Accounts in any organizational directory and personal Microsoft accounts*, and register.
2. **Authentication** → *Advanced settings* → **Allow public client flows: Yes** → Save.
3. **API permissions** → Add → Microsoft Graph → *Delegated*: `Calendars.Read`, `Mail.Read`, `Mail.Send`,
   `User.Read`, `offline_access`. Work accounts may need an admin to grant consent.
4. Copy the **Application (client) ID** into RJ → Settings → Connections, tap **Sign in to Outlook**,
   and enter the code shown at microsoft.com/devicelogin.

## Offline mode

When there's no signal (or with **Brain → Always offline**), a built-in host takes over seamlessly. It
still knows the time, your calendar, the drive, the last weather it saw and your music library, and it
runs ~50 quiz questions, name-that-tune, place trivia for 30+ cities, facts, jokes and scripted
mini-podcasts with your co-host. Set **Music → Phone library** (or *Auto*) so songs play from files
on your phone with the built-in player.

## Sounds like radio

- When the RJ talks, the music fades down underneath instead of stopping. The next song starts first,
  then the host talks over its intro.
- The RJ only names a song after the music app confirms what actually started. If the app picked a
  different track, it introduces the real one. Spotify gets several precise search phrasings before
  the app gives up.

## Voices

- **ElevenLabs (natural)**: paste an ElevenLabs API key in Settings → Voice. Defaults are a deep British
  broadcaster ("Daniel") hosting, with "Alice" (British) as co-host. Any voice IDs work. Lines are
  pre-rendered while the previous song plays, so they start instantly.
- **Accent** (default British) and **Deep voice** apply to both engines; the RJ's wording follows the accent.
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
