package ru.tyuiu.rasp;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class ScheduleStore {
    record Favorite(String name, String feed) {}
    record SavedGroup(String feed, Schedule schedule, long updatedAt) {}

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
        select(new SavedGroup(feed, schedule, System.currentTimeMillis()));
    }

    void select(SavedGroup group) throws IOException {
        synchronized (LOCK) {
            Schedule schedule = group.schedule();
            SharedPreferences.Editor editor = prefs.edit().putString("data", schedule.json().toString())
                    .putString("feed", group.feed()).putLong("lastSync", group.updatedAt());
            if (!schedule.group.equals(read().group)) editor.remove("subgroup").remove("sent");
            writeFavoriteCache(editor, group);
            if (!editor.commit()) throw new IOException("Не удалось сохранить расписание");
        }
    }

    boolean refresh(String expectedFeed, long expectedSync, Schedule schedule) throws IOException {
        synchronized (LOCK) {
            if (!expectedFeed.equals(feed()) || expectedSync != lastSync()
                    || !read().group.equals(schedule.group) || schedule.demo) return false;
            long now = Instant.now().toEpochMilli();
            var editor = prefs.edit().putString("data", schedule.json().toString()).putLong("lastSync", now);
            writeFavoriteCache(editor, new SavedGroup(expectedFeed, schedule, now));
            if (!editor.commit()) {
                throw new IOException("Не удалось обновить расписание");
            }
            return true;
        }
    }

    List<Favorite> favorites() {
        var entries = new ArrayList<Favorite>();
        try {
            var index = new JSONArray(prefs.getString("favorites", "[]"));
            for (int i = 0; i < index.length(); i++) {
                var item = index.getJSONObject(i);
                entries.add(new Favorite(item.getString("name"), item.getString("feed")));
            }
        } catch (org.json.JSONException ignored) {}
        return entries;
    }

    boolean isFavorite(String feed) {
        return !feed.isBlank() && favorites().stream().anyMatch(group -> group.feed().equals(feed));
    }

    void toggleFavorite(SavedGroup group) {
        if (group.feed().isBlank() || group.schedule().demo) return;
        synchronized (LOCK) {
            var entries = new LinkedHashMap<String, String>();
            for (Favorite favorite : favorites()) entries.put(favorite.feed(), favorite.name());
            var editor = prefs.edit();
            String key = group.feed();
            if (entries.remove(key) == null) {
                entries.put(key, group.schedule().group);
                saveCache(editor, group);
            } else {
                editor.remove("favorite.data:" + key).remove("favorite.updated:" + key);
            }
            var index = new JSONArray();
            entries.forEach((feed, name) -> index.put(new JSONObject(java.util.Map.of("feed", feed, "name", name))));
            editor.putString("favorites", index.toString()).apply();
        }
    }

    SavedGroup cachedFavorite(Favorite group) {
        String data = prefs.getString("favorite.data:" + group.feed(), "");
        try {
            Schedule schedule = Schedule.parse(data);
            if (!schedule.demo && schedule.group.equals(group.name())) {
                return new SavedGroup(group.feed(), schedule, prefs.getLong("favorite.updated:" + group.feed(), 0));
            }
        } catch (Exception invalid) {}
        return null;
    }

    void cacheFavorite(SavedGroup group) {
        synchronized (LOCK) {
            if (!isFavorite(group.feed())) return;
            var editor = prefs.edit();
            saveCache(editor, group);
            editor.apply();
        }
    }

    private void writeFavoriteCache(SharedPreferences.Editor editor, SavedGroup group) {
        if (isFavorite(group.feed())) saveCache(editor, group);
    }

    private void saveCache(SharedPreferences.Editor editor, SavedGroup group) {
        editor.putString("favorite.data:" + group.feed(), group.schedule().json().toString())
                .putLong("favorite.updated:" + group.feed(), group.updatedAt());
    }
}
