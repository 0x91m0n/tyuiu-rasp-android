package ru.tyuiu.rasp;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

final class WeekAlarms {
    static final String ACTION_RING = "ru.tyuiu.rasp.RING_WEEK_ALARM";
    static final String EXTRA_TIME = "alarm_time";
    static final String CHANNEL = "week_alarms";
    private static final String SCHEDULED = "scheduled";

    private WeekAlarms() {}

    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("week_alarms", Context.MODE_PRIVATE);
    }

    static String key(boolean even, DayOfWeek day) {
        return (even ? "even_" : "odd_") + day.getValue();
    }

    static int dayTime(SharedPreferences prefs, boolean even, DayOfWeek day) {
        int minute = prefs.getInt(key(even, day), -1);
        return minute >= 0 && minute < 1440 ? minute : -1;
    }

    static boolean dayEnabled(SharedPreferences prefs, boolean even, DayOfWeek day) {
        int minute = dayTime(prefs, even, day);
        return minute >= 0 && prefs.getBoolean(key(even, day) + "_enabled", true);
    }

    static void saveDay(SharedPreferences prefs, boolean even, DayOfWeek day, int minute, boolean enabled) {
        String key = key(even, day);
        prefs.edit().putInt(key, minute).putBoolean(key + "_enabled", enabled).apply();
    }

    static Instant next(int[] odd, int[] even, Instant now, ZoneId zone) {
        var today = now.atZone(zone).toLocalDate();
        for (int offset = 0; offset <= 14; offset++) {
            var date = today.plusDays(offset);
            int[] profile = OfficialTiu.evenWeek(date) ? even : odd;
            int minute = profile[date.getDayOfWeek().getValue() - 1];
            if (minute < 0 || minute >= 1440) continue;
            Instant candidate = date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant();
            if (candidate.isAfter(now)) return candidate;
        }
        return null;
    }

    static Instant next(SharedPreferences prefs, Instant now, ZoneId zone) {
        int[] odd = new int[7];
        int[] even = new int[7];
        for (DayOfWeek day : DayOfWeek.values()) {
            odd[day.getValue() - 1] = dayEnabled(prefs, false, day) ? dayTime(prefs, false, day) : -1;
            even[day.getValue() - 1] = dayEnabled(prefs, true, day) ? dayTime(prefs, true, day) : -1;
        }
        return next(odd, even, now, zone);
    }

    static boolean notifications(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        var channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static synchronized void reschedule(Context context) {
        var prefs = prefs(context);
        var alarms = context.getSystemService(AlarmManager.class);
        var intent = new Intent(context, WeekAlarmReceiver.class).setAction(ACTION_RING);
        PendingIntent callback = PendingIntent.getBroadcast(context, 73, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
        if (callback != null) alarms.cancel(callback);
        Instant next = prefs.getBoolean("enabled", false) && alarms.canScheduleExactAlarms() && notifications(context)
                ? next(prefs, Instant.now(), ZoneId.systemDefault()) : null;
        long timestamp = next != null ? next.toEpochMilli() : 0;
        prefs.edit().putLong(SCHEDULED, timestamp).apply();
        if (next == null) return;
        callback = PendingIntent.getBroadcast(context, 73, intent.putExtra(EXTRA_TIME, timestamp),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(timestamp, ScheduleEvents.open(context, "week-alarms")),
                    callback);
        } catch (SecurityException revoked) {
            prefs.edit().remove(SCHEDULED).apply();
        }
    }

    static synchronized boolean fire(Context context, long requested, Instant now) {
        var prefs = prefs(context);
        long elapsed = now.toEpochMilli() - requested;
        if (!prefs.getBoolean("enabled", false) || requested <= 0 || requested != prefs.getLong(SCHEDULED, 0)
                || elapsed < -1000 || elapsed > 5 * 60_000L) return false;
        prefs.edit().remove(SCHEDULED).apply();
        return notifications(context);
    }
}
