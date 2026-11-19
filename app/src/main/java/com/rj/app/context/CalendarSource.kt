package com.rj.app.context

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.rj.core.CalendarEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reads today's (and tomorrow morning's) events from every calendar synced to the phone. */
class CalendarSource(private val context: Context) {

    fun upcoming(now: ZonedDateTime = ZonedDateTime.now()): List<CalendarEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val begin = now.minusHours(2).toInstant().toEpochMilli()
        val end = now.toLocalDate().plusDays(1).atTime(12, 0).atZone(now.zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = mutableListOf<CalendarEvent>()
        runCatching {
            context.contentResolver.query(
                uri, projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 20) {
                    val declined = c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                    if (declined) continue
                    val allDay = c.getInt(4) == 1
                    // All-day events are stored in UTC.
                    val zone = if (allDay) ZoneId.of("UTC") else now.zone
                    out += CalendarEvent(
                        title = c.getString(0)?.ifBlank { null } ?: "(untitled)",
                        start = Instant.ofEpochMilli(c.getLong(1)).atZone(zone).withZoneSameInstant(now.zone),
                        end = Instant.ofEpochMilli(c.getLong(2)).atZone(zone).withZoneSameInstant(now.zone),
                        location = c.getString(3)?.ifBlank { null },
                        allDay = allDay,
                    )
                }
            }
        }
        return out
    }
}
