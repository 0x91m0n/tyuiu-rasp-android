package ru.tyuiu.rasp;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class ScheduleSmoke extends Instrumentation {
    private boolean officialNetwork;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        officialNetwork = "true".equals(arguments.getString("official"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        var store = new ScheduleStore(context);
        var old = store.prefs.getAll();
        var host = new AppWidgetHost(context, 854);
        Activity activity = null;
        int status = Activity.RESULT_OK;
        try {
            LocalDateTime start = Instant.now().plusSeconds(19 * 60L).atZone(Schedule.ZONE).toLocalDateTime();
            Schedule.Lesson lesson = new Schedule.Lesson(start, start.plusMinutes(95),
                    "Проверочная пара", "Лекция", "Ауд. 152", "Преподаватель", "1");
            var schedule = new Schedule("ПРОВЕРКА-1", "Проверка", List.of(lesson), false);
            require(schedule.lessons.equals(Schedule.parse(schedule.json().toString()).lessons),
                    "JSON round trip");
            require(schedule.day(start.toLocalDate(), "2").isEmpty(), "Subgroup filtering");
            var evening = LocalDateTime.of(2026, 11, 20, 18, 0);
            var later = new Schedule.Lesson(evening, evening.plusMinutes(80), "Вечерняя", "", "", "", "2");
            var timed = new Schedule("ПРОВЕРКА-2", "Проверка", List.of(later), false);
            var cutoff = later.ends().plusSeconds(7200);
            require(timed.defaultDay(cutoff.minusSeconds(1), "2").equals(evening.toLocalDate()),
                    "Keep today's lessons until the cutoff");
            require(timed.defaultDay(cutoff, "2").equals(evening.toLocalDate().plusDays(1)),
                    "Show tomorrow two hours after the last lesson");
            require(timed.defaultDay(cutoff, "1").equals(evening.toLocalDate()), "Use the selected subgroup");
            require(MainActivity.isNextOccurrence(start, start.minusHours(1)), "Same-day wake");
            require(!MainActivity.isNextOccurrence(start.plusDays(2), start.minusHours(1)), "Future-date wake");
            require(!MainActivity.isNextOccurrence(start, start.plusMinutes(1)), "Elapsed wake");
            require(OfficialTiu.evenWeek(LocalDate.of(2026, 11, 19)), "Academic week, not ISO parity");
            require(!OfficialTiu.evenWeek(LocalDate.of(2026, 11, 26)), "Parity alternates");
            if (officialNetwork) {
                var group = Schedule.groups(Schedule.https(OfficialTiu.HOME)).stream()
                        .findFirst().orElseThrow();
                Schedule live = Schedule.load(group.url());
                require(live.group.equals(group.name()) && !live.lessons.isEmpty(), "Official timetable on Android");
            }
            store.select(schedule, "");
            store.prefs.edit().putBoolean("notifications", true).putInt("lead", 20).putString("theme", "indigo").apply();
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity screen = activity;
            waitForIdleSync();
            runOnMainSync(() -> require(hasText(screen.getWindow().getDecorView(), lesson.title()), "Next lesson UI"));
            runOnMainSync(() -> {
                View root = screen.getWindow().getDecorView();
                TextView header = findText(root, "Пары ТИУ");
                require(header != null && header.getCurrentTextColor() == ThemePalette.INDIGO.text, "Saved theme");
                TextView weekly = findText(root, "7 дней");
                require(weekly != null && weekly.performClick(), "Open seven-day feed");
                LocalDate date = start.toLocalDate().plusDays(6);
                String label = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Schedule.RU));
                String heading = label.substring(0, 1).toUpperCase(Schedule.RU) + label.substring(1);
                require(hasText(screen.getWindow().getDecorView(), heading), "Latest day in the same scroll");
            });
            NotificationManager notifications = context.getSystemService(NotificationManager.class);
            require(ScheduleEvents.notificationsAvailable(context), "Grant POST_NOTIFICATIONS to the emulator first");
            ScheduleEvents.run(context, true);
            ScheduleEvents.run(context, true);
            var active = notifications.getActiveNotifications();
            require(java.util.Arrays.stream(active).filter(n -> lesson.key().equals(n.getTag())).count() == 1,
                    "Reminder delivery and deduplication");
            notifications.cancel(lesson.key(), 1);

            AppWidgetManager widgets = context.getSystemService(AppWidgetManager.class);
            runOnMainSync(host::startListening);
            var activeLesson = new Schedule.Lesson(start.minusMinutes(29), start.plusMinutes(66),
                    "Проверка прогресса", "", "", "", "");
            for (Widgets.Kind kind : Widgets.Kind.values()) {
                Class<?> type = kind.provider;
                Schedule widgetSchedule = schedule;
                if (kind == Widgets.Kind.PROGRESS) {
                    widgetSchedule = new Schedule(schedule.group, schedule.source, List.of(activeLesson), false);
                    store.select(widgetSchedule, "");
                }
                int id = host.allocateAppWidgetId();
                try {
                    require(widgets.bindAppWidgetIdIfAllowed(id, new ComponentName(context, type)),
                            "Grant widget binding to the emulator first");
                    waitForIdleSync();
                    require(Widgets.updateAll(context, store, widgetSchedule, Instant.now()).any(), "Widget update");
                    var mounted = new java.util.concurrent.atomic.AtomicReference<AppWidgetHostView>();
                    runOnMainSync(() -> mounted.set(host.createView(context, id, widgets.getAppWidgetInfo(id))));
                    waitForIdleSync();
                    String expected = switch (kind) {
                        case NEXT -> lesson.title();
                        case PROGRESS -> activeLesson.title();
                        case WEEK -> schedule.group;
                        case DAY, TOMORROW -> {
                            var date = kind == Widgets.Kind.DAY ? Schedule.today() : Schedule.today().plusDays(1);
                            yield date.format(Schedule.DATE) + " · " + schedule.group;
                        }
                    };
                    runOnMainSync(() -> {
                        require(hasText(mounted.get(), expected), "Rendered widget " + type.getSimpleName());
                        if (kind == Widgets.Kind.PROGRESS) {
                            ProgressBar progress = mounted.get().findViewById(R.id.widget_progress);
                            require(progress.getVisibility() == View.VISIBLE && progress.getProgress() > 0, "Visible class progress");
                        }
                    });
                } finally {
                    host.deleteAppWidgetId(id);
                }
            }
            result.putString("stream", "\nSchedule, reminders, wake-date calculation and five widgets passed.\n"
                    + (officialNetwork ? "The official catalog and group timetable loaded on Android.\n" : ""));
        } catch (Throwable failure) {
            status = Activity.RESULT_CANCELED;
            result.putString("stream", "\n" + android.util.Log.getStackTraceString(failure));
        } finally {
            runOnMainSync(host::stopListening);
            if (activity != null) activity.finish();
            var editor = store.prefs.edit().clear();
            for (var entry : old.entrySet()) restore(editor, entry.getKey(), entry.getValue());
            editor.commit();
            ScheduleEvents.run(context, false);
        }
        finish(status, result);
    }

    private static boolean hasText(View node, String value) {
        return findText(node, value) != null;
    }

    private static TextView findText(View node, String value) {
        if (node instanceof TextView text && value.contentEquals(text.getText())) return text;
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                var found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void restore(android.content.SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String text) editor.putString(key, text);
        else if (value instanceof Integer number) editor.putInt(key, number);
        else if (value instanceof Long number) editor.putLong(key, number);
        else if (value instanceof Boolean enabled) editor.putBoolean(key, enabled);
        else if (value instanceof java.util.Set<?> set) {
            var strings = new java.util.HashSet<String>();
            for (Object member : set) strings.add((String) member);
            editor.putStringSet(key, strings);
        }
    }

    private static void require(boolean condition, String check) {
        if (!condition) throw new AssertionError(check);
    }
}
