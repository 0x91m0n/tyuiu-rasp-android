package ru.tyuiu.rasp;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.Instant;

final class ScheduleStore {
    private static final Object LOCK = new Object();
    final SharedPreferences prefs;
    private String lastData = "";
    private Schedule lastSchedule = Schedule.EMPTY;

    ScheduleStore(Context context) {
        prefs = context.getSharedPreferences("schedule", Context.MODE_PRIVATE);
    }

    synchronized Schedule read() {
        String stored = prefs.getString("data", "");
        if (stored.equals(lastData)) return lastSchedule;
        try {
            lastSchedule = stored.isEmpty() ? Schedule.EMPTY : Schedule.parse(stored);
        } catch (Exception invalid) {
            lastSchedule = Schedule.EMPTY;
        }
        lastData = stored;
        return lastSchedule;
    }

    String catalog() { return prefs.getString("catalog",
            BuildConfig.CATALOG_URL.isBlank() ? OfficialTiu.HOME : BuildConfig.CATALOG_URL); }
    String subgroup() { return prefs.getString("subgroup", ""); }
    String feed() { return prefs.getString("feed", ""); }
    long lastSync() { return prefs.getLong("lastSync", 0); }
    boolean notifications() { return prefs.getBoolean("notifications", false); }
    boolean glyph() { return prefs.getBoolean("glyph", false); }
    String theme() { return prefs.getString("theme", "auto"); }
    int lead() { return prefs.getInt("lead", 20); }
    int wakeLead() { return prefs.getInt("wakeLead", 90); }

    void select(Schedule schedule, String feed) throws IOException {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs.edit().putString("data", schedule.json().toString())
                    .putString("feed", feed).putLong("lastSync", Instant.now().toEpochMilli());
            if (!schedule.group.equals(read().group)) editor.remove("subgroup").remove("sent");
            if (!editor.commit()) throw new IOException("Не удалось сохранить расписание");
        }
    }

    boolean refresh(String expectedFeed, Schedule schedule) throws IOException {
        synchronized (LOCK) {
            if (!expectedFeed.equals(feed()) || !read().group.equals(schedule.group) || schedule.demo) return false;
            if (!prefs.edit().putString("data", schedule.json().toString())
                    .putLong("lastSync", Instant.now().toEpochMilli()).commit()) {
                throw new IOException("Не удалось обновить расписание");
            }
            return true;
        }
    }
}
