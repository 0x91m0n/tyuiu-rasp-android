package ru.tyuiu.rasp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

record WidgetOptions(Widgets.Kind kind, String subgroup, String theme, int opacity, int textSize,
                     boolean compact, int fields, String title) {
    static final String FOLLOW_GROUP = "@app";
    static final String FOLLOW_THEME = "app";
    static final int TIME = 1;
    static final int ROOM = 1 << 1;
    static final int TEACHER = 1 << 2;
    static final int TYPE = 1 << 3;
    static final int GAPS = 1 << 4;
    static final int GROUP = 1 << 5;
    static final int COUNTDOWN = 1 << 6;
    static final int WEEK_CLASSES = 1 << 7;
    static final int HEADER = 1 << 8;
    private static final int DEFAULT_FIELDS = TIME | GROUP | HEADER;

    boolean has(int field) { return (fields & field) != 0; }

    String subgroup(Schedule schedule, ScheduleStore store) {
        return FOLLOW_GROUP.equals(subgroup) || (!subgroup.isEmpty() && !schedule.subgroups().contains(subgroup))
                ? store.subgroup() : subgroup;
    }

    static WidgetOptions decode(String saved, Widgets.Kind fallback) {
        JSONObject object;
        try { object = new JSONObject(saved); }
        catch (org.json.JSONException invalid) { object = new JSONObject(); }
        Widgets.Kind kind;
        try { kind = Widgets.Kind.valueOf(object.optString("kind")); }
        catch (IllegalArgumentException missing) { kind = fallback; }
        int defaultFields = switch (kind) {
            case NEXT, PROGRESS -> DEFAULT_FIELDS | ROOM;
            case WEEK -> DEFAULT_FIELDS | WEEK_CLASSES;
            default -> DEFAULT_FIELDS;
        };
        return new WidgetOptions(kind, object.optString("subgroup", FOLLOW_GROUP),
                object.optString("theme", FOLLOW_THEME), clamp(object.optInt("opacity", 100), 0, 100),
                clamp(object.optInt("size", 0), -1, 1),
                object.optBoolean("compact"), object.optInt("fields", defaultFields), object.optString("title"));
    }

    String encode() {
        try {
            return new JSONObject().put("kind", kind.name()).put("subgroup", subgroup).put("theme", theme)
                    .put("opacity", opacity).put("size", textSize)
                    .put("compact", compact).put("fields", fields).put("title", title).toString();
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    static WidgetOptions load(Context context, int id, Widgets.Kind fallback) {
        return decode(prefs(context).getString(String.valueOf(id), "{}"), fallback);
    }

    static void save(Context context, int id, WidgetOptions options) {
        prefs(context).edit().putString(String.valueOf(id), options.encode()).apply();
    }

    static LocalDate dateOverride(Context context, int id, Widgets.Kind kind, LocalDate today) {
        String stored = prefs(context).getString("date:" + id, "");
        String prefix = datePrefix(kind, today);
        if (!stored.startsWith(prefix)) return null;
        try {
            return LocalDate.parse(stored.substring(prefix.length()));
        } catch (DateTimeParseException invalid) {
            return null;
        }
    }

    static void setDateOverride(Context context, int id, Widgets.Kind kind, LocalDate today, LocalDate target) {
        var editor = prefs(context).edit();
        String key = "date:" + id;
        if (target == null) editor.remove(key);
        else editor.putString(key, datePrefix(kind, today) + target);
        editor.apply();
    }

    private static String datePrefix(Widgets.Kind kind, LocalDate today) { return kind.name() + ":" + today + ":"; }

    static void deleted(Context context, int[] ids) {
        var editor = prefs(context).edit();
        for (int id : ids) editor.remove(String.valueOf(id)).remove("date:" + id);
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("widgets", Context.MODE_PRIVATE);
    }
}
