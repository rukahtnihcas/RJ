package com.rj.core

/**
 * Decodes standard OBD-II (SAE J1979, mode 01) answers from an ELM327-style adapter.
 * The transport (Bluetooth) lives in the app; this part is pure and unit-tested.
 */
object Obd {
    const val FUEL_LEVEL = 0x2F
    const val COOLANT_TEMP = 0x05
    const val MONITOR_STATUS = 0x01
    const val MODULE_VOLTAGE = 0x42
    const val AMBIENT_TEMP = 0x46
    const val HYBRID_BATTERY = 0x5B

    /** PIDs we poll, in order. */
    val POLLED = listOf(FUEL_LEVEL, COOLANT_TEMP, MONITOR_STATUS, MODULE_VOLTAGE, AMBIENT_TEMP, HYBRID_BATTERY)

    /** Adapter setup: reset, echo off, linefeeds off, spaces on, headers off, auto protocol. */
    val INIT = listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATSP0")

    fun request(pid: Int) = "01%02X".format(pid)

    /** Data bytes of a mode-01 reply for [pid], or null for NO DATA / errors / other PIDs. */
    fun dataBytes(raw: String, pid: Int): List<Int>? {
        val tokens = raw.uppercase()
            .replace("SEARCHING...", " ").replace(">", " ")
            .split(Regex("[\\s\\r\\n]+"))
            .filter { it.isNotEmpty() }
        if (tokens.any { it in setOf("NO", "DATA", "ERROR", "UNABLE", "STOPPED", "?") }) return null
        // Accept both "41 2F 6B" and "412F6B" (spaces off).
        val hex = tokens.joinToString("").chunked(2)
        val target = "%02X".format(pid)
        for (i in 0 until hex.size - 1) {
            if (hex[i] == "41" && hex[i + 1] == target) {
                return hex.drop(i + 2).mapNotNull { it.toIntOrNull(16) }.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Fold one PID answer into the vehicle status. */
    fun apply(status: VehicleStatus, pid: Int, raw: String): VehicleStatus {
        val b = dataBytes(raw, pid) ?: return status
        val a = b[0]
        return when (pid) {
            FUEL_LEVEL -> status.copy(fuelPercent = (a * 100 / 255.0).toInt())
            COOLANT_TEMP -> status.copy(coolantC = a - 40)
            MONITOR_STATUS -> status.copy(checkEngine = a and 0x80 != 0)
            MODULE_VOLTAGE -> if (b.size >= 2) status.copy(batteryVolts = (a * 256 + b[1]) / 1000.0) else status
            AMBIENT_TEMP -> status.copy(ambientC = a - 40)
            HYBRID_BATTERY -> status.copy(batteryPercent = (a * 100 / 255.0).toInt())
            else -> status
        }
    }
}
