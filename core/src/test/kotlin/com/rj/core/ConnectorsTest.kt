package com.rj.core

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObdTest {
    @Test fun decodesStandardPids() {
        var v = VehicleStatus()
        v = Obd.apply(v, Obd.FUEL_LEVEL, "SEARCHING...\r41 2F 26 \r\r>")
        v = Obd.apply(v, Obd.COOLANT_TEMP, "41 05 7B")
        v = Obd.apply(v, Obd.MONITOR_STATUS, "4101830764 00")
        v = Obd.apply(v, Obd.MODULE_VOLTAGE, "41 42 31 A6")
        assertEquals(14, v.fuelPercent)        // 0x26 = 38 -> 14.9%
        assertEquals(83, v.coolantC)           // 0x7B = 123 - 40
        assertEquals(true, v.checkEngine)      // bit 7 of 0x83
        assertEquals(12.71, v.batteryVolts!!, 0.01)
        assertTrue(v.lowEnergy)
        assertEquals(v, Obd.apply(v, Obd.AMBIENT_TEMP, "NO DATA"))
    }
}

class TrafficTest {
    @Test fun paceBlendsRouteWithObservedSpeed() {
        assertEquals(20, TrafficClient.paceEstimate(10.0, 20, 30.0, 2))  // too early: trust the route
        // 10 km at 15 km/h = 40 min; after 20 min driving, weight 0.7 -> 0.3*20 + 0.7*40 = 34
        assertEquals(34, TrafficClient.paceEstimate(10.0, 20, 15.0, 20))
        assertEquals(20, TrafficClient.paceEstimate(10.0, 20, 60.0, 20)) // never faster than an empty road
    }

    @Test fun describesFlow() {
        assertNull(TrafficClient.describeFlow(48, 50, false))
        assertEquals("heavy: 18 km/h vs 45 normally", TrafficClient.describeFlow(18, 45, false))
        assertEquals("road closed ahead", TrafficClient.describeFlow(0, 50, true))
    }

    @Test fun parsesTomTomIncidents() {
        val json = ObjectMapper().readTree("""{"incidents":[
            {"properties":{"magnitudeOfDelay":1,"events":[{"description":"Roadworks"}],"from":"A Rd","to":"B Rd","delay":60}},
            {"properties":{"magnitudeOfDelay":4,"events":[{"description":"Accident"}],"from":"Ring Rd","to":"","delay":540}}]}""")
        assertEquals(listOf("Accident (Ring Rd), +9 min", "Roadworks (A Rd to B Rd), +1 min"), TrafficClient.parseIncidents(json))
    }

    @Test fun buildsTripFromOpenData() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"code":"Ok","routes":[{"distance":12400,"duration":1200}]}"""))
        val t = TrafficClient(osrmBase = server.url("/").toString().trimEnd('/'))
            .trafficInfo(GeoPoint(18.5, 73.8), GeoPoint(18.6, 73.9), "Office", avgSpeedKph = 18.0, minutesDriven = 15)!!
        assertEquals(12.4, t.remainingKm, 0.01)
        assertEquals(20, t.freeFlowMinutes)
        assertTrue(t.etaMinutes > 20 && t.delayMinutes > 0, "$t")
        assertTrue(server.takeRequest().path!!.startsWith("/route/v1/driving/73.80000,18.50000;73.90000,18.60000"))
        server.shutdown()
    }
}

class GraphTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = ZonedDateTime.of(2026, 11, 19, 8, 0, 0, 0, zone)

    @Test fun parsesEventsAndSkipsCancelled() {
        val json = ObjectMapper().readTree("""{"value":[
          {"subject":"Design review","start":{"dateTime":"2026-11-19T09:30:00.0000000","timeZone":"UTC"},
           "end":{"dateTime":"2026-11-19T10:00:00.0000000","timeZone":"UTC"},"location":{"displayName":"Room 4"},
           "organizer":{"emailAddress":{"name":"Asha Rao","address":"asha@example.com"}},"isAllDay":false,"isCancelled":false},
          {"subject":"Cancelled thing","start":{"dateTime":"2026-11-19T11:00:00"},"end":{"dateTime":"2026-11-19T12:00:00"},"isCancelled":true}]}""")
        val events = MicrosoftGraph.parseEvents(json, now)
        assertEquals(1, events.size)
        assertEquals("Asha Rao", events[0].organizer)
        assertEquals("asha@example.com", events[0].organizerEmail)
        assertEquals(9, events[0].start.hour)
        assertEquals("outlook", events[0].source)
    }

    @Test fun parsesFocusedUnreadMail() {
        val json = ObjectMapper().readTree("""{"value":[
          {"id":"1","subject":"Q3 deck","from":{"emailAddress":{"name":"Boss","address":"b@x.com"}},
           "receivedDateTime":"2026-11-19T07:50:00Z","importance":"high","bodyPreview":"Can you  check\r\nslide 4?","inferenceClassification":"focused"},
          {"id":"2","subject":"50% off!","from":{"emailAddress":{"name":"Shop"}},"receivedDateTime":"2026-11-19T07:40:00Z","inferenceClassification":"other"}]}""")
        val mail = MicrosoftGraph.parseMail(json)
        assertEquals(1, mail.size)
        assertEquals("Q3 deck — Can you check slide 4?", mail[0].text)
        assertTrue(mail[0].vip)
    }

    @Test fun deviceCodeLoginFlow() {
        val server = MockWebServer().apply { start() }
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(MockResponse().setBody("""{"user_code":"ABCD-1234","device_code":"dev","verification_uri":"https://microsoft.com/devicelogin","interval":5,"expires_in":900}"""))
        server.enqueue(MockResponse().setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""))
        val g = MicrosoftGraph("client-id", loginBase = base, graphBase = base)
        val code = g.startDeviceLogin()
        assertEquals("ABCD-1234", code.userCode)
        assertEquals(MicrosoftGraph.Poll.Pending, g.pollDeviceLogin(code))
        val done = g.pollDeviceLogin(code) as MicrosoftGraph.Poll.Done
        assertEquals("rt", done.tokens.refreshToken)
        assertFalse(done.tokens.expired())
        assertTrue(server.takeRequest().body.readUtf8().contains("scope=offline_access"))
        server.shutdown()
    }
}

class ContextSignalsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val t0 = ZonedDateTime.of(2026, 11, 19, 9, 0, 0, 0, zone)

    private fun ctx(min: Long, vararg extra: (ShowContext) -> ShowContext): ShowContext {
        var c = ShowContext(
            now = t0.plusMinutes(min), profile = UserProfile(name = "Sam"),
            drive = DriveState(startedAt = t0, elapsed = Duration.ofMinutes(min), distanceKm = min.toDouble()),
        )
        extra.forEach { c = it(c) }
        return c
    }

    @Test fun rendersSignals() {
        val c = ctx(5, {
            it.copy(
                inbox = listOf(InboxItem("w1", InboxKind.MESSAGE, "WhatsApp", "Priya", "milk?", t0, canReply = true, count = 2)),
                health = HealthSnapshot(sleepLastNight = Duration.ofMinutes(335), stepsToday = 4200, restingHeartRate = 60,
                    latestHeartRate = 92, latestHeartRateAt = t0),
                vehicle = VehicleStatus(fuelPercent = 12, alerts = listOf("iRA: Door unlocked")),
                traffic = TrafficInfo("Office", 9.5, 20, 31),
                events = listOf(CalendarEvent("Standup", t0.plusMinutes(30), t0.plusMinutes(45), organizer = "Asha")),
            )
        }).render()
        assertTrue("WhatsApp message from Priya (2)" in c, c)
        assertTrue("[can reply by voice]" in c)
        assertTrue("slept 5h 35m" in c && "elevated" in c, c)
        assertTrue("fuel 12%" in c && "iRA: Door unlocked" in c, c)
        assertTrue("arriving ~09:36 (11 min slower" in c, c)
        assertTrue("about 6 min late for 'Standup'" in c, c)
    }

    @Test fun plannerHandlesNewMoments() {
        val p = ShowPlanner()
        p.next(ctx(0))
        // Running late beats everything.
        val late = p.next(ctx(4, {
            it.copy(traffic = TrafficInfo("Office", 10.0, 20, 40), events = listOf(CalendarEvent("Standup", t0.plusMinutes(30), t0.plusMinutes(45))))
        }))
        assertEquals(CueType.RUNNING_LATE, late.type)
        // Car alert, once.
        val car = { c: ShowContext -> c.copy(vehicle = VehicleStatus(fuelPercent = 9)) }
        assertEquals(CueType.VEHICLE_ALERT, p.next(ctx(8, car)).type)
        assertEquals(CueType.LINK, p.next(ctx(12, car)).type)
        // VIP message straight away; normal ones wait for the roundup.
        val vip = InboxItem("v", InboxKind.MESSAGE, "WhatsApp", "Mum", null, t0.plusMinutes(13), vip = true)
        assertEquals(CueType.INBOX, p.next(ctx(14, { it.copy(inbox = listOf(vip)) })).type)
        val normal = InboxItem("n", InboxKind.EMAIL, "Outlook", "HR", null, t0.plusMinutes(15))
        assertEquals(CueType.LINK, p.next(ctx(16, { it.copy(inbox = listOf(vip, normal)) })).type)
        assertEquals(CueType.INBOX, p.next(ctx(20, { it.copy(inbox = listOf(vip, normal)) })).type)
    }

    @Test fun suggestsBreaksOnLongOrTiredDrives() {
        val p = ShowPlanner()
        val tired = { c: ShowContext -> c.copy(health = HealthSnapshot(sleepLastNight = Duration.ofHours(4))) }
        p.next(ctx(0, tired))
        val cues = (4L..64L step 4).map { p.next(ctx(it, tired)).type }
        assertTrue(CueType.TAKE_A_BREAK in cues, "$cues")
    }

    @Test fun parsesOffersAndKeepsSongOutOfSpeech() {
        val s = Segment.parse("""{"speech":"Priya asked about milk.","offer":{"type":"reply","to":"Priya","text":"Yes, getting it"},
            "song":{"artist":"Queen","title":"Don't Stop Me Now"},"intro_tag":"Here's Queen"}""")
        assertEquals(Offer(Offer.Kind.REPLY, "Priya", "Yes, getting it"), s.offer)
        assertEquals(Offer.Kind.LATE_NOTE, Segment.parse("""{"speech":"x","offer":{"type":"late_note","minutes":12}}""").offer?.kind)
        val sys = PromptBuilder.system(Persona(), UserProfile(), emptyList(), dialect = "British English")
        assertTrue("British English accent" in sys && "NEVER name the next song" in sys)
    }

    @Test fun offlineBrainCoversNewCues() {
        val b = OfflineBrain().apply { startShow(Persona(), UserProfile(name = "Sam"), emptyList(), listOf(SongPick("Queen", "Under Pressure"))) }
        val c = ctx(30, { it.copy(inbox = listOf(InboxItem("a", InboxKind.MESSAGE, "WhatsApp", "Priya", null, t0))) })
        listOf(CueType.INBOX, CueType.RUNNING_LATE, CueType.VEHICLE_ALERT, CueType.TAKE_A_BREAK, CueType.WELLBEING).forEach {
            assertTrue(b.next(Cue(it, listOf("note")), c).speech.isNotBlank(), "$it")
        }
        // Song intros live in intro_tag so the app can confirm the track first.
        val link = b.next(Cue(CueType.LINK), c)
        assertTrue(link.song == null || (link.introTag != null && link.song!!.title !in link.speech))
    }
}

@Suppress("unused")
private val keepInstantImport = Instant.EPOCH

class CommandsTest {
    @Test fun parsesCommands() {
        assertEquals(Command.ReplyTo("priya"), Commands.parse("Reply to Priya"))
        assertEquals(Command.ReplyTo(null), Commands.parse("reply"))
        assertEquals(Command.ReplyTo(null), Commands.parse("reply to her"))
        assertEquals(Command.ReadMessages, Commands.parse("read it out"))
        assertEquals(Command.ReadMessages, Commands.parse("Can you read my messages"))
        assertEquals(Command.RunningLate, Commands.parse("tell them I'm running late"))
        assertNull(Commands.parse("what a lovely day"))
    }

    @Test fun confirms() {
        assertEquals(true, Commands.confirm("Yes please"))
        assertEquals(true, Commands.confirm("ok send it"))
        assertEquals(false, Commands.confirm("no don't"))
        assertEquals(false, Commands.confirm("cancel"))
        assertNull(Commands.confirm("hmm what"))
        assertNull(Commands.confirm(null))
    }
}
