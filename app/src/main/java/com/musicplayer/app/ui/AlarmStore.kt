package com.musicplayer.app.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.musicplayer.app.R
import com.musicplayer.app.data.MusicRepository
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * One music alarm: time, optional name, repeat days, the song to play,
 * gradual volume, on/off.
 *
 * days is a bitmask — bit 0 = Monday ... bit 6 = Sunday; 0 = one-time
 * (fires once at the next occurrence of the time, then disables itself).
 */
data class MusicAlarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val name: String,
    val days: Int,
    val songId: Long,
    val songTitle: String,
    val fadeIn: Boolean,
    val enabled: Boolean
)

/**
 * v1.0.13: the alarm is a full alarm app — several alarms, stored as JSON
 * in SharedPreferences, each with its own exact AlarmManager alarm.
 * The old single-alarm prefs ("zeno_alarm", v1.0.4) are migrated once.
 */
object AlarmStore {

    const val ACTION_ALARM = "com.musicplayer.app.ALARM"
    const val ACTION_STOP_ALARM = "com.musicplayer.app.STOP_ALARM"
    const val ACTION_ALARM_STOPPED = "com.musicplayer.app.ALARM_STOPPED"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_SONG_ID = "alarm_song_id"
    const val EXTRA_ALARM_NAME = "alarm_name"
    const val EXTRA_SONG_TITLE = "alarm_song_title"
    const val EXTRA_FADE_IN = "alarm_fade_in"

    private const val PREFS = "zeno_alarms"
    private const val KEY_ALARMS = "alarms_json"
    private const val KEY_MIGRATED = "migrated_v1"
    private const val LEGACY_PREFS = "zeno_alarm"

    /** Bit i (0..6) = weekday, Monday first. */
    fun dayBit(dayOfWeek: Int): Int = (dayOfWeek + 5) % 7

    fun load(context: Context): List<MusicAlarm> {
        migrateLegacy(context)
        return try {
            val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ALARMS, null) ?: return emptyList()
            val array = JSONArray(json)
            val out = ArrayList<MusicAlarm>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                out.add(
                    MusicAlarm(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        hour = o.optInt("hour", 7),
                        minute = o.optInt("minute", 0),
                        name = o.optString("name", ""),
                        days = o.optInt("days", 0),
                        songId = o.optLong("songId", -1L),
                        songTitle = o.optString("songTitle", ""),
                        fadeIn = o.optBoolean("fadeIn", true),
                        enabled = o.optBoolean("enabled", false)
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, alarms: List<MusicAlarm>) {
        try {
            val array = JSONArray()
            for (a in alarms) {
                array.put(
                    JSONObject()
                        .put("id", a.id)
                        .put("hour", a.hour)
                        .put("minute", a.minute)
                        .put("name", a.name)
                        .put("days", a.days)
                        .put("songId", a.songId)
                        .put("songTitle", a.songTitle)
                        .put("fadeIn", a.fadeIn)
                        .put("enabled", a.enabled)
                )
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ALARMS, array.toString()).apply()
        } catch (e: Exception) {
        }
    }

    /** Insert a new alarm or replace the one with the same id. */
    fun saveAlarm(context: Context, alarm: MusicAlarm): List<MusicAlarm> {
        val list = load(context).toMutableList()
        val index = list.indexOfFirst { it.id == alarm.id }
        if (index >= 0) list[index] = alarm else list.add(alarm)
        save(context, list)
        return list
    }

    fun deleteAlarm(context: Context, id: String): List<MusicAlarm> {
        val list = load(context).filter { it.id != id }
        save(context, list)
        return list
    }

    /** The next enabled alarm and its trigger time, or null. */
    fun nextAlarm(alarms: List<MusicAlarm>, nowMs: Long): Pair<MusicAlarm, Long>? {
        var best: Pair<MusicAlarm, Long>? = null
        for (alarm in alarms) {
            if (!alarm.enabled) continue
            val trigger = nextTriggerMillis(alarm, nowMs)
            if (best == null || trigger < best.second) best = alarm to trigger
        }
        return best
    }

    /** Next occurrence of this alarm's time (today if still ahead). */
    fun nextTriggerMillis(alarm: MusicAlarm, nowMs: Long): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (alarm.days == 0) {
            if (base.timeInMillis <= nowMs) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        for (offset in 0..7) {
            val day = Calendar.getInstance().apply {
                timeInMillis = base.timeInMillis
                add(Calendar.DAY_OF_YEAR, offset)
            }
            val bit = dayBit(day.get(Calendar.DAY_OF_WEEK))
            if (alarm.days and (1 shl bit) != 0 && day.timeInMillis > nowMs) {
                return day.timeInMillis
            }
        }
        // Unreachable when at least one day is set; fall back to +7 days.
        return base.timeInMillis + 7L * 24 * 60 * 60 * 1000
    }

    /** "dans 8 h 15" / "dans 45 min". */
    fun formatCountdown(context: Context, triggerMs: Long, nowMs: Long): String {
        val diffMin = ((triggerMs - nowMs) / 60000L).coerceAtLeast(0L)
        val hours = (diffMin / 60).toInt()
        val mins = (diffMin % 60).toInt()
        return if (hours > 0) {
            context.getString(R.string.alarm_in_hours, hours, mins)
        } else {
            context.getString(R.string.alarm_in_minutes, mins.coerceAtLeast(1))
        }
    }

    /** "Lun · Mar · Mer · Jeu · Ven" or "Une seule fois". */
    fun daysSummary(context: Context, days: Int): String {
        if (days == 0) return context.getString(R.string.alarm_once)
        val names = shortDayNames()
        val selected = (0..6).filter { days and (1 shl it) != 0 }
        return selected.joinToString(" · ") { names[it] }
    }

    /** Short weekday names, Monday first, in the app language. */
    fun shortDayNames(): List<String> {
        val locale = Locale.getDefault()
        val format = SimpleDateFormat("EEE", locale)
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
        val out = ArrayList<String>(7)
        for (i in 0..6) {
            out.add(format.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    // ----- one-time migration from the v1.0.4 single alarm -----

    private fun migrateLegacy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        try {
            val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            val enabled = legacy.getBoolean("enabled", false)
            val songId = legacy.getLong("song_id", -1L)
            if (!enabled || songId < 0) {
                // Nothing to migrate — still clear the old keys.
                legacy.edit().clear().apply()
                return
            }
            val hour = legacy.getInt("hour", 7)
            val minute = legacy.getInt("minute", 0)
            val title = try {
                MusicRepository.loadSongs(context)
                    .firstOrNull { it.id == songId }?.title ?: ""
            } catch (e: Exception) {
                ""
            }
            val alarm = MusicAlarm(
                id = UUID.randomUUID().toString(),
                hour = hour,
                minute = minute,
                name = context.getString(R.string.alarm_default_name),
                days = 0,
                songId = songId,
                songTitle = title,
                fadeIn = true,
                enabled = true
            )
            save(context, listOf(alarm))
            // Cancel the old single-alarm PendingIntent (requestCode 0).
            try {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val oldIntent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_ALARM
                }
                val oldPending = PendingIntent.getBroadcast(
                    context, 0, oldIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                manager.cancel(oldPending)
            } catch (e: Exception) {
            }
            legacy.edit().clear().apply()
            AlarmScheduler.schedule(context, alarm)
        } catch (e: Exception) {
        }
    }
}

/** Exact AlarmManager scheduling for one alarm. */
object AlarmScheduler {

    fun pendingIntent(context: Context, alarm: MusicAlarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmStore.ACTION_ALARM
            putExtra(AlarmStore.EXTRA_ALARM_ID, alarm.id)
            putExtra(AlarmStore.EXTRA_SONG_ID, alarm.songId)
        }
        return PendingIntent.getBroadcast(
            context, alarm.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Schedule (or re-schedule) this alarm's next occurrence. */
    fun schedule(context: Context, alarm: MusicAlarm) {
        if (!alarm.enabled) return
        val trigger = AlarmStore.nextTriggerMillis(alarm, System.currentTimeMillis())
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Android 12+: exact alarms need the user's grant in system settings.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
            Toast.makeText(
                context, R.string.alarm_need_permission, Toast.LENGTH_LONG
            ).show()
            try {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (e: Exception) {
            }
        }
        try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, alarm)
            )
        } catch (e: Exception) {
            // Exact alarm not permitted: fall back to an inexact alarm —
            // it still rings, just possibly a few minutes late.
            try {
                manager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, alarm)
                )
            } catch (e2: Exception) {
            }
        }
    }

    fun cancel(context: Context, alarm: MusicAlarm) {
        try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            manager.cancel(pendingIntent(context, alarm))
        } catch (e: Exception) {
        }
    }

    /** After a reboot: re-schedule every enabled alarm. */
    fun rescheduleAll(context: Context) {
        for (alarm in AlarmStore.load(context)) {
            if (alarm.enabled) {
                val manager =
                    context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val trigger =
                    AlarmStore.nextTriggerMillis(alarm, System.currentTimeMillis())
                try {
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, alarm)
                    )
                } catch (e: Exception) {
                    try {
                        manager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, trigger, pendingIntent(context, alarm)
                        )
                    } catch (e2: Exception) {
                    }
                }
            }
        }
    }
}
