package com.rj.core

import java.time.Duration
import java.time.ZonedDateTime

/** Something that arrived for the driver: a chat message, an email, or an alert from the car's app. */
enum class InboxKind { MESSAGE, EMAIL, VEHICLE }

data class InboxItem(
    /** Stable id (used to avoid announcing the same thing twice). */
    val id: String,
    val kind: InboxKind,
    /** "WhatsApp", "Outlook", "Messages", "iRA"... */
    val app: String,
    val from: String,
    /** Message text, or null when the driver chose not to share contents with the AI. */
    val text: String?,
    val at: ZonedDateTime,
    /** We hold a reply action for it (e.g. WhatsApp direct reply). */
    val canReply: Boolean = false,
    /** From someone on the driver's VIP list: announce promptly. */
    val vip: Boolean = false,
    val count: Int = 1,
)

/** Daily wellbeing from Samsung Health / smartwatch via Health Connect. All fields optional. */
data class HealthSnapshot(
    val sleepLastNight: Duration? = null,
    val stepsToday: Long? = null,
    val restingHeartRate: Int? = null,
    val latestHeartRate: Int? = null,
    val latestHeartRateAt: ZonedDateTime? = null,
    val exerciseToday: List<String> = emptyList(),
    val activeCaloriesToday: Int? = null,
) {
    /** Heart rate well above resting in the last 20 minutes: a gentle cue to calm things down. */
    fun elevated(now: ZonedDateTime): Boolean {
        val hr = latestHeartRate ?: return false
        val at = latestHeartRateAt ?: return false
        val rest = restingHeartRate ?: 65
        return Duration.between(at, now) <= Duration.ofMinutes(20) && hr >= rest + 25
    }

    val shortSleep: Boolean get() = sleepLastNight?.let { it < Duration.ofHours(6) } ?: false

    fun render(now: ZonedDateTime): String? {
        val parts = buildList {
            sleepLastNight?.let { add("slept ${it.toHours()}h ${it.toMinutesPart()}m last night") }
            stepsToday?.let { add("$it steps today") }
            if (exerciseToday.isNotEmpty()) add("workouts today: ${exerciseToday.joinToString()}")
            activeCaloriesToday?.let { add("$it active kcal") }
            restingHeartRate?.let { add("resting heart rate $it") }
            latestHeartRate?.let { hr ->
                val ago = latestHeartRateAt?.let { Duration.between(it, now).toMinutes() }
                add("latest heart rate $hr${ago?.let { " ($it min ago)" } ?: ""}${if (elevated(now)) ", elevated" else ""}")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

/** What we know about the car, from an OBD-II adapter and/or the car maker's app notifications. */
data class VehicleStatus(
    val fuelPercent: Int? = null,
    val batteryPercent: Int? = null,
    val rangeKm: Int? = null,
    val coolantC: Int? = null,
    val checkEngine: Boolean? = null,
    val batteryVolts: Double? = null,
    val ambientC: Int? = null,
    /** Recent alerts from the car's companion app, e.g. "iRA: Tyre pressure low (front left)". */
    val alerts: List<String> = emptyList(),
) {
    val lowEnergy: Boolean get() = (fuelPercent ?: 100) <= 15 || (batteryPercent ?: 100) <= 15 || (rangeKm ?: 1000) <= 50

    fun render(): String? {
        val parts = buildList {
            fuelPercent?.let { add("fuel $it%") }
            batteryPercent?.let { add("battery charge $it%") }
            rangeKm?.let { add("range ~$it km") }
            coolantC?.let { add("coolant ${it}°C") }
            if (checkEngine == true) add("CHECK ENGINE LIGHT ON")
            batteryVolts?.let { add("12V battery ${"%.1f".format(it)}V") }
            ambientC?.let { add("outside ${it}°C") }
            if (alerts.isNotEmpty()) add("car app alerts: ${alerts.joinToString("; ")}")
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }
}

data class TrafficInfo(
    val destination: String,
    val remainingKm: Double,
    /** Minutes to destination with no traffic. */
    val freeFlowMinutes: Int,
    /** Best live estimate of minutes to destination. */
    val etaMinutes: Int,
    /** Live traffic on the road we're on, e.g. "heavy: 18 km/h vs 45 normally". */
    val flowNote: String? = null,
    val incidents: List<String> = emptyList(),
    /** Where the live estimate came from ("TomTom live traffic" / "your pace + OpenStreetMap route"). */
    val source: String = "",
) {
    val delayMinutes: Int get() = (etaMinutes - freeFlowMinutes).coerceAtLeast(0)
    fun arrival(now: ZonedDateTime): ZonedDateTime = now.plusMinutes(etaMinutes.toLong())
}
