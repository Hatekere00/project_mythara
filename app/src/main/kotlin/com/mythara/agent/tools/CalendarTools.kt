package com.mythara.agent.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar tool pair — list + create.
 *
 * - `list_calendar_events(hoursAhead, max)` reads upcoming events from
 *   the user's default calendars via CalendarContract. Permission:
 *   READ_CALENDAR.
 * - `create_calendar_event(title, startMs, endMs, location?, description?)`
 *   inserts an event into the user's primary writable calendar.
 *   Permission: WRITE_CALENDAR. Returns the inserted event id + system
 *   calendar uri.
 *
 * Read tool is unconfirmed; create tool is technically destructive but
 * inserting an event is forgiving (user can edit / delete in Calendar
 * app trivially) — we ship it without confirmation today. M5 part 4's
 * ConfirmationGate can add per-call prompting if the user wants
 * stricter behaviour.
 */

@Singleton
class ListCalendarEventsTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable
    data class Event(
        val id: Long,
        val title: String? = null,
        val startMs: Long,
        val endMs: Long,
        val location: String? = null,
        val allDay: Boolean = false,
        val calendarName: String? = null,
    )

    @Serializable
    data class Response(val count: Int, val events: List<Event>)

    override val name: String = "list_calendar_events"
    override val description: String =
        "Upcoming events from the user's calendars. Includes ALL calendars the user has linked + synced through Android's CalendarContract: " +
            "Google Calendar, Microsoft Outlook (com.microsoft.office.outlook), Exchange / corporate accounts, local calendars. " +
            "Teams meetings show up here as long as the user has Outlook syncing — Teams itself doesn't expose a separate calendar surface on Android. " +
            "Defaults to the next 48 hours; pass `hoursAhead` to widen up to 30 days. " +
            "Use when the user asks 'what's on my schedule', 'do I have anything tomorrow', 'when's my next Teams meeting'. " +
            "This is a READ tool — it works even when autopilot is off."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "hoursAhead",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "How far in the future to look. Default 48. Max 720 (30 days).")
                    },
                )
                put(
                    "max",
                    buildJsonObject {
                        put("type", "integer")
                        put("description", "Maximum number of events to return. Default 25, max 100.")
                    },
                )
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"Calendar permission isn't granted. Open Settings → Apps → Mythara → Permissions and allow Calendar."}""",
            )
        }
        val hoursAhead = ((args["hoursAhead"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 48)
            .coerceIn(1, 720)
        val max = ((args["max"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 25)
            .coerceIn(1, 100)
        val now = System.currentTimeMillis()
        val end = now + hoursAhead * 3600L * 1000L
        val events = withContext(Dispatchers.IO) { queryEvents(now, end, max) }
        return ToolResult(
            ok = true,
            output = JSON.encodeToString(
                Response.serializer(),
                Response(count = events.size, events = events),
            ),
        )
    }

    private fun queryEvents(startMs: Long, endMs: Long, max: Int): List<Event> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<Event>()
        ctx.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC LIMIT $max",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val locIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            val allDayIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val calIdx = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
            while (c.moveToNext()) {
                out.add(
                    Event(
                        id = c.getLong(idIdx),
                        title = c.getString(titleIdx),
                        startMs = c.getLong(beginIdx),
                        endMs = c.getLong(endIdx),
                        location = c.getString(locIdx),
                        allDay = c.getInt(allDayIdx) == 1,
                        calendarName = c.getString(calIdx),
                    ),
                )
            }
        }
        return out
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}

@Singleton
class CreateCalendarEventTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
) : Tool {

    @Serializable
    data class Response(
        val ok: Boolean,
        val eventId: Long?,
        val uri: String?,
        val calendarId: Long?,
        val calendarName: String? = null,
        val accountName: String? = null,
        val accountType: String? = null,
        /** Round-trip verification — true if the inserted row was found in the Events table afterwards. */
        val verified: Boolean = false,
    )

    override val name: String = "create_calendar_event"
    override val description: String =
        "Add an event to the user's calendar. Times are epoch millis (UTC). " +
            "Use when the user says 'add a meeting tomorrow at 3pm' or 'put dentist on my calendar Friday at 10'. " +
            "Resolve relative times ('tomorrow', 'next Tuesday') against the user's local time zone before calling. " +
            "The response includes which calendar the event landed in (calendarName + accountName) — relay this to the user " +
            "so they know where to find it. Returns verified=true only when the row is confirmed present after insert."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("title", buildJsonObject { put("type", "string"); put("description", "Event title.") })
                put("startMs", buildJsonObject { put("type", "integer"); put("description", "Event start time as epoch millis.") })
                put("endMs", buildJsonObject { put("type", "integer"); put("description", "Event end time as epoch millis. Must be > startMs.") })
                put("location", buildJsonObject { put("type", "string"); put("description", "Optional location.") })
                put("description", buildJsonObject { put("type", "string"); put("description", "Optional notes/description.") })
                put("allDay", buildJsonObject { put("type", "boolean"); put("description", "True for an all-day event; startMs/endMs should be midnight UTC of the days.") })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("startMs"), JsonPrimitive("endMs"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolResult(
                ok = false,
                output = """{"error":"permission_denied","detail":"Calendar write permission isn't granted. Open Settings → Apps → Mythara → Permissions and allow Calendar."}""",
            )
        }
        val title = (args["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return ToolResult(false, """{"error":"missing_title"}""")
        val startMs = (args["startMs"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult(false, """{"error":"missing_or_bad_startMs"}""")
        val endMs = (args["endMs"] as? JsonPrimitive)?.content?.toLongOrNull()
            ?: return ToolResult(false, """{"error":"missing_or_bad_endMs"}""")
        if (endMs <= startMs) {
            return ToolResult(false, """{"error":"end_before_start","detail":"endMs must be greater than startMs."}""")
        }
        val location = (args["location"] as? JsonPrimitive)?.content
        val description = (args["description"] as? JsonPrimitive)?.content
        val allDay = (args["allDay"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

        val cal = withContext(Dispatchers.IO) { findPrimaryWritableCalendar() }
            ?: return ToolResult(
                false,
                """{"error":"no_writable_calendar","detail":"Couldn't find a writable calendar on this device. Open Google Calendar (or any calendar app), make sure at least one account/calendar is visible + syncing, then retry."}""",
            )
        val tz = TimeZone.getDefault().id
        // IMPORTANT: ACCOUNT_NAME / ACCOUNT_TYPE are READ-ONLY columns
        // on Events for non-sync-adapter callers — setting them on
        // this insert causes the provider to reject the write (returns
        // null URI silently). The account routing happens automatically
        // via CALENDAR_ID, which inherits the parent calendar's
        // account. Don't put ACCOUNT_NAME / ACCOUNT_TYPE here.
        //
        // Minimal required fields per the Android docs:
        //   CALENDAR_ID, DTSTART, DTEND (or DURATION), EVENT_TIMEZONE.
        // TITLE is optional but the user always wants one.
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            if (location != null) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (description != null) put(CalendarContract.Events.DESCRIPTION, description)
            if (allDay) put(CalendarContract.Events.ALL_DAY, 1)
        }
        android.util.Log.d(
            TAG,
            "inserting event title=$title start=$startMs end=$endMs tz=$tz " +
                "cal=${cal.id}/${cal.displayName} acct=${cal.accountName}/${cal.accountType}",
        )
        val uri = withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) }
                .onFailure { android.util.Log.w(TAG, "calendar insert threw: ${it.message}", it) }
                .getOrNull()
        } ?: return ToolResult(
            false,
            """{"error":"insert_failed","detail":"Calendar provider rejected the insert. Picked calendar=${cal.displayName} on ${cal.accountName}/${cal.accountType}. The calendar may be read-only or the account may not allow writes. Open the Calendar app and confirm this calendar accepts new events."}""",
        )
        val eventId = ContentUris.parseId(uri)

        // Verify the row is actually queryable AFTER insert. If the
        // sync adapter or content provider dropped it for any reason
        // (missing required field, calendar not syncable, etc.), the
        // row's gone by the time we look. Report verified=false in
        // that case so the model tells the user instead of lying about
        // success.
        val verified = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                    arrayOf(CalendarContract.Events._ID),
                    null, null, null,
                )?.use { it.moveToFirst() } == true
            }.getOrDefault(false)
        }
        android.util.Log.d(
            TAG,
            "created event id=$eventId verified=$verified cal=${cal.displayName} acct=${cal.accountName}/${cal.accountType}",
        )
        return ToolResult(
            true,
            JSON.encodeToString(
                Response.serializer(),
                Response(
                    ok = true,
                    eventId = eventId,
                    uri = uri.toString(),
                    calendarId = cal.id,
                    calendarName = cal.displayName,
                    accountName = cal.accountName,
                    accountType = cal.accountType,
                    verified = verified,
                ),
            ),
        )
    }

    private data class CalRow(
        val id: Long,
        val displayName: String?,
        val accountName: String?,
        val accountType: String?,
    )

    /**
     * Pick the first writable calendar. Strategy:
     *   1. Prefer the device's marked-primary calendar (IS_PRIMARY=1).
     *   2. Then any OWNER-access calendar (Google account, local).
     *   3. Then CONTRIBUTOR-access (e.g. shared work calendar where
     *      the user can add events).
     * VISIBLE=1 + SYNC_EVENTS=1 filter so we never write to a stale
     * unsubscribed account whose events the user can't see.
     */
    private fun findPrimaryWritableCalendar(): CalRow? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            // VISIBLE so we never write where the user can't see it.
            // SYNC_EVENTS so we don't write to a calendar that won't
            // round-trip to the user's Google/Exchange account.
            "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.SYNC_EVENTS} = 1",
            null,
            // Primary first; then most-privileged access level; then
            // alphabetic by display name as a stable tiebreaker.
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} DESC, " +
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accessIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val acctNameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val acctTypeIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            while (c.moveToNext()) {
                val access = c.getInt(accessIdx)
                if (access >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return CalRow(
                        id = c.getLong(idIdx),
                        displayName = c.getString(nameIdx),
                        accountName = c.getString(acctNameIdx),
                        accountType = c.getString(acctTypeIdx),
                    )
                }
            }
        }
        // Fallback path: maybe the user disabled SYNC_EVENTS but still
        // wants events added. Try again without the sync filter.
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} DESC",
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accessIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            val acctNameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val acctTypeIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
            while (c.moveToNext()) {
                if (c.getInt(accessIdx) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return CalRow(
                        id = c.getLong(idIdx),
                        displayName = c.getString(nameIdx),
                        accountName = c.getString(acctNameIdx),
                        accountType = c.getString(acctTypeIdx),
                    )
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Mythara/Cal"
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
