package ru.tyuiu.rasp;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

public final class ScheduleSmoke extends Instrumentation {
    private boolean officialNetwork;
    private boolean ringAlarm;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        officialNetwork = "true".equals(arguments.getString("official"));
        ringAlarm = "true".equals(arguments.getString("weekAlarm"));
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
            var odd = new int[7];
            var even = new int[7];
            Arrays.fill(odd, -1);
            Arrays.fill(even, -1);
            odd[0] = 7 * 60;
            even[0] = 8 * 60 + 15;
            var monday = LocalDate.of(2026, 11, 23);
            var before = monday.atTime(6, 0).atZone(Schedule.ZONE).toInstant();
            var oddAlarm = monday.atTime(7, 0).atZone(Schedule.ZONE).toInstant();
            var evenAlarm = monday.plusWeeks(1).atTime(8, 15).atZone(Schedule.ZONE).toInstant();
            require(oddAlarm.equals(WeekAlarms.next(odd, even, before, Schedule.ZONE)), "Odd week alarm time");
            require(evenAlarm.equals(WeekAlarms.next(odd, even, oddAlarm, Schedule.ZONE)), "Even week alarm time");
            var profile = getContext().getSharedPreferences("alarm_profile_test", Context.MODE_PRIVATE);
            profile.edit().clear().putInt(WeekAlarms.key(false, DayOfWeek.MONDAY), 420).apply();
            require(WeekAlarms.dayEnabled(profile, false, DayOfWeek.MONDAY), "Legacy alarm stays enabled");
            WeekAlarms.saveDay(profile, false, DayOfWeek.MONDAY, 420, false);
            require(WeekAlarms.dayTime(profile, false, DayOfWeek.MONDAY) == 420
                    && WeekAlarms.next(profile, before, Schedule.ZONE) == null, "Paused alarm keeps its time");
            WeekAlarms.saveDay(profile, false, DayOfWeek.MONDAY, 420, true);
            require(oddAlarm.equals(WeekAlarms.next(profile, before, Schedule.ZONE)), "Re-enabling uses the saved time");
            if (officialNetwork) {
                var group = Schedule.groups(Schedule.https(OfficialTiu.HOME)).stream()
                        .findFirst().orElseThrow();
                Schedule live = Schedule.load(group.url());
                require(live.group.equals(group.name()) && !live.lessons.isEmpty(), "Official timetable on Android");
            }
            var other = new Schedule.Lesson(start, start.plusMinutes(95), "Пара другой подгруппы", "", "Ауд. 215", "", "2");
            var withGap = new Schedule(schedule.group, schedule.source, List.of(lesson, other,
                    new Schedule.Lesson(lesson.end().plusMinutes(40), lesson.end().plusMinutes(135),
                            "Вторая пара", "", "", "", "")), false);
            store.select(withGap, "");
            store.prefs.edit().putBoolean("notifications", true).putInt("lead", 20).putString("theme", "indigo").apply();
            activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity screen = activity;
            waitForIdleSync();
            runOnMainSync(() -> require(hasText(screen.getWindow().getDecorView(), lesson.title()), "Next lesson UI"));
            runOnMainSync(() -> {
                View root = screen.getWindow().getDecorView();
                require(hasText(root, "Окно · 40 мин"), "Gap drawn between lessons");
                TextView header = findText(root, "Пары ТИУ");
                require(header != null && header.getCurrentTextColor() == ThemePalette.INDIGO.text, "Saved theme");
                require(!hasText(root, "Будильник к первой паре") && !hasText(root, "Виджеты"), "Schedule actions moved to settings");
                View second = findDescription(root, "Подгруппа 2");
                require(second != null && second.performClick(), "Subgroup preview");
                root = screen.getWindow().getDecorView();
                require(hasText(root, other.title()) && !hasText(root, lesson.title()) && store.subgroup().isEmpty(),
                        "Subgroup preview does not overwrite the personal setting");
                require(findDescription(root, "Все подгруппы").performClick(), "Clear subgroup preview");
                root = screen.getWindow().getDecorView();
                require(findText(root, "Настройки").performClick(), "Open settings");
                root = screen.getWindow().getDecorView();
                require(hasText(root, "Данные расписания") && hasText(root, "Виджеты"), "Grouped settings");
                require(findDescription(root, "Назад").performClick(), "Back to schedule");
                TextView weekly = findText(screen.getWindow().getDecorView(), "7 дней");
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
            store.select(schedule, "");
            var activeLesson = new Schedule.Lesson(start.minusMinutes(29), start.plusMinutes(66),
                    "Проверка прогресса", "", "", "", "");
            for (Widgets.Kind kind : Widgets.Kind.providers()) {
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
                    Instant renderedAt = Instant.now();
                    require(Widgets.updateAll(context, store, widgetSchedule, renderedAt).any(), "Widget update");
                    var mounted = new java.util.concurrent.atomic.AtomicReference<AppWidgetHostView>();
                    runOnMainSync(() -> mounted.set(host.createView(context, id, widgets.getAppWidgetInfo(id))));
                    waitForIdleSync();
                    String expected = switch (kind) {
                        case NEXT -> lesson.title();
                        case PROGRESS -> activeLesson.title();
                        case WEEK -> schedule.group;
                        case DAY, TODAY, TOMORROW -> {
                            var date = kind == Widgets.Kind.DAY ? Schedule.today() : Schedule.today().plusDays(1);
                            yield date.format(Schedule.DATE) + " · " + schedule.group;
                        }
                    };
                    Schedule renderedSchedule = widgetSchedule;
                    runOnMainSync(() -> {
                        require(hasText(mounted.get(), expected), "Rendered widget " + type.getSimpleName());
                        if (kind == Widgets.Kind.PROGRESS) {
                            WidgetOptions options = WidgetOptions.load(context, id, kind);
                            var view = Widgets.make(context, store, renderedSchedule, renderedAt, id, options, kind.size)
                                    .apply(context, null);
                            ProgressBar progress = view.findViewById(R.id.widget_progress);
                            require(progress.getVisibility() == View.VISIBLE && progress.getProgress() > 0,
                                    "Visible class progress (visibility=" + progress.getVisibility()
                                            + ", progress=" + progress.getProgress() + ", indeterminate="
                                            + progress.isIndeterminate() + ")");
                        }
                    });
                } finally {
                    host.deleteAppWidgetId(id);
                }
            }
            checkWidgetOptions(context, store, host, widgets, withGap, lesson, other);
            checkWidgetAgenda(context, store);
            if (ringAlarm) {
                runOnMainSync(() -> screen.moveTaskToBack(true));
                checkAlarmRing(context, notifications);
            }
            result.putString("stream", "\nSchedule, subgroup preview, settings, reminders, five widgets and independent widget options passed.\n"
                    + (officialNetwork ? "The official catalog and group timetable loaded on Android.\n" : "")
                    + (ringAlarm ? "The exact week alarm fired, showed its stop action and scheduled the next week.\n" : ""));
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

    private void checkWidgetAgenda(Context context, ScheduleStore store) {
        var day = LocalDate.of(2026, 11, 20);
        var now = day.atTime(11, 0).atZone(Schedule.ZONE).toInstant();
        var schedule = new Schedule("СМКб-26-1", "", List.of(
                new Schedule.Lesson(day.atTime(13, 45), day.atTime(15, 20), "Основы российской государственности",
                        "Практика", "315 / Корпус 2", "Смирнова Александра Владимировна", "1"),
                new Schedule.Lesson(day.atTime(17, 15), day.atTime(18, 50), "Проектная деятельность",
                        "Практика", "504а / Корпус 1а", "Кузнецова Ирина Юрьевна", "1")), false);
        int fields = WidgetOptions.TIME | WidgetOptions.ROOM | WidgetOptions.TYPE | WidgetOptions.TEACHER
                | WidgetOptions.GAPS | WidgetOptions.HEADER | WidgetOptions.GROUP;
        var options = new WidgetOptions(Widgets.Kind.DAY, "1", "dark", 100, 0, 0, false, fields, "");
        runOnMainSync(() -> {
            var full = new SizeF(250, 250);
            var remote = Widgets.make(context, store, schedule, now, AppWidgetManager.INVALID_APPWIDGET_ID, options, full);
            View widget = remote.apply(context, null);
            layout(widget, context, full);
            LinearLayout rows = widget.findViewById(R.id.widget_schedule_rows);
            require(rows.getChildCount() == 3, "Two classes and a separate gap");
            View first = rows.getChildAt(0);
            var time = (TextView) first.findViewById(R.id.widget_row_time);
            var subject = (TextView) first.findViewById(R.id.widget_row_subject);
            require(time.getText().toString().equals("13:45") && subject.getText().toString().startsWith("Основы"),
                    "Time and subject have independent text views");
            require(hasText(first, "315 / Корпус 2 · Практика") && hasText(first, "Смирнова А. В."),
                    "Optional metadata and teacher are on separate lines");
            require(hasText(rows.getChildAt(1), "Окно · 115 мин") && hasText(rows.getChildAt(2), "Проектная деятельность"),
                    "Gap belongs to the next visible class");
            require(rows.getBottom() <= widget.getHeight() - widget.getPaddingBottom(), "Roomy agenda fits");
            remote.reapply(context, widget);
            require(rows.getChildCount() == 3, "Reapplying the widget does not duplicate rows");

            var small = new SizeF(160, 133);
            View compact = Widgets.make(context, store, schedule, now, AppWidgetManager.INVALID_APPWIDGET_ID, options, small)
                    .apply(context, null);
            layout(compact, context, small);
            LinearLayout shortRows = compact.findViewById(R.id.widget_schedule_rows);
            require(hasText(compact, "13:45") && hasText(compact, "Ещё 1") && !hasText(compact, "Окно · 115 мин"),
                    "Short widgets keep the first class and skip orphaned gaps");
            require(shortRows.getBottom() <= compact.getHeight() - compact.getPaddingBottom(), "Compact agenda fits");

            var hostOptions = new Bundle();
            hostOptions.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, new java.util.ArrayList<>(List.of(small, full)));
            var sizes = Widgets.sizes(hostOptions, Widgets.Kind.DAY);
            require(sizes.equals(List.of(small, full)), "Both launcher sizes are passed to the renderer");
            View responsive = Widgets.renderSizes(context, store, schedule, now, AppWidgetManager.INVALID_APPWIDGET_ID, options, sizes)
                    .apply(context, null);
            require(hasText(responsive, "13:45"), "Responsive widget can inflate");

            var largeFont = new android.content.res.Configuration(context.getResources().getConfiguration());
            largeFont.fontScale = 1.35f;
            Context scaled = context.createConfigurationContext(largeFont);
            View scaledWidget = Widgets.make(scaled, store, schedule, now, AppWidgetManager.INVALID_APPWIDGET_ID, options, small)
                    .apply(scaled, null);
            layout(scaledWidget, scaled, small);
            View scaledRows = scaledWidget.findViewById(R.id.widget_schedule_rows);
            require(hasText(scaledWidget, "13:45")
                    && scaledRows.getBottom() <= scaledWidget.getHeight() - scaledWidget.getPaddingBottom(),
                    "Small widget fits with large system text");

            var progressOptions = new WidgetOptions(Widgets.Kind.PROGRESS, "1", "dark", 100, 0, 0,
                    false, fields | WidgetOptions.COUNTDOWN, "");
            var progressSize = new SizeF(145, 85);
            var during = day.atTime(14, 0).atZone(Schedule.ZONE).toInstant();
            View progressWidget = Widgets.make(context, store, schedule, during, AppWidgetManager.INVALID_APPWIDGET_ID,
                    progressOptions, progressSize).apply(context, null);
            layout(progressWidget, context, progressSize);
            View progress = progressWidget.findViewById(R.id.widget_progress);
            View countdown = progressWidget.findViewById(R.id.widget_countdown);
            require(progress.getVisibility() == View.VISIBLE && progress.getHeight() > 0
                    && countdown.getVisibility() == View.VISIBLE && countdown.getHeight() > 0
                    && progress.getBottom() <= progressWidget.getHeight() - progressWidget.getPaddingBottom(),
                    "Countdown and progress bar fit in the compact widget");
        });
    }

    private static void layout(View view, Context context, SizeF size) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(size.getWidth() * density);
        int height = Math.round(size.getHeight() * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private void checkWidgetOptions(Context context, ScheduleStore store, AppWidgetHost host, AppWidgetManager widgets,
                                    Schedule schedule, Schedule.Lesson first, Schedule.Lesson second) throws Exception {
        store.select(schedule, "");
        int one = host.allocateAppWidgetId();
        int two = host.allocateAppWidgetId();
        try {
            var provider = new ComponentName(context, NextWidget.class);
            require(widgets.bindAppWidgetIdIfAllowed(one, provider) && widgets.bindAppWidgetIdIfAllowed(two, provider),
                    "Bind two independent widget instances");
            WidgetOptions.save(context, one, new WidgetOptions(Widgets.Kind.NEXT, "1", "mono", 60, 1, 3, true,
                    WidgetOptions.HEADER | WidgetOptions.ROOM | WidgetOptions.COUNTDOWN, "Моя пара"));
            WidgetOptions.save(context, two, new WidgetOptions(Widgets.Kind.NEXT, "2", "light", 100, -1, 2, false,
                    WidgetOptions.HEADER | WidgetOptions.TIME, "Вторая подгруппа"));
            Widgets.updateAll(context, store, schedule, Instant.now());
            var left = new java.util.concurrent.atomic.AtomicReference<AppWidgetHostView>();
            var right = new java.util.concurrent.atomic.AtomicReference<AppWidgetHostView>();
            runOnMainSync(() -> {
                left.set(host.createView(context, one, widgets.getAppWidgetInfo(one)));
                right.set(host.createView(context, two, widgets.getAppWidgetInfo(two)));
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                require(hasText(left.get(), first.title()) && hasText(left.get(), "Моя пара"), "Custom title and first subgroup");
                require(hasText(right.get(), second.title()) && hasText(right.get(), "Вторая подгруппа"), "Second subgroup");
                require(left.get().findViewById(R.id.widget_countdown).getVisibility() == View.VISIBLE,
                        "Configured countdown");
                require(right.get().findViewById(R.id.widget_countdown).getVisibility() == View.GONE,
                        "Countdown preference is per widget");
            });
            Activity settings = startActivitySync(WidgetConfigurationActivity.intent(context, two)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                View root = settings.getWindow().getDecorView();
                require(hasText(root, "Настройка виджета") && hasText(root, "Вторая подгруппа"), "Live configuration preview");
                require(findText(root, "Сохранить").performClick(), "Save widget configuration");
            });
            require(WidgetOptions.load(context, two, Widgets.Kind.NEXT).subgroup().equals("2"), "Saved subgroup preserved");
        } finally {
            host.deleteAppWidgetId(one);
            host.deleteAppWidgetId(two);
        }
    }

    private static void checkAlarmRing(Context context, NotificationManager notifications) throws Exception {
        var alarms = context.getSystemService(AlarmManager.class);
        require(alarms.canScheduleExactAlarms(), "Grant exact alarm access to the emulator first");
        var prefs = WeekAlarms.prefs(context);
        var old = prefs.getAll();
        try {
            var wake = Instant.now().plusSeconds(85).truncatedTo(ChronoUnit.MINUTES);
            var local = wake.atZone(ZoneId.systemDefault());
            DayOfWeek weekday = local.getDayOfWeek();
            boolean even = OfficialTiu.evenWeek(local.toLocalDate());
            int minutes = local.getHour() * 60 + local.getMinute();
            prefs.edit().clear().putBoolean("enabled", true).putInt(WeekAlarms.key(even, weekday), minutes).commit();
            WeekAlarms.reschedule(context);
            require(alarms.getNextAlarmClock().getTriggerTime() == wake.toEpochMilli(), "Alarm is scheduled");
            var deadline = wake.plusSeconds(12);
            while (Instant.now().isBefore(deadline)) {
                for (var notice : notifications.getActiveNotifications()) {
                    if (WeekAlarms.CHANNEL.equals(notice.getNotification().getChannelId())) {
                        require(notice.getNotification().actions.length == 1, "Alarm offers a stop action");
                        require(alarms.getNextAlarmClock().getTriggerTime() > wake.toEpochMilli(), "Next alarm is scheduled");
                        notice.getNotification().actions[0].actionIntent.send();
                        return;
                    }
                }
                Thread.sleep(300);
            }
            throw new AssertionError("Alarm did not start its foreground notification");
        } finally {
            context.stopService(new Intent(context, WeekAlarmService.class));
            var editor = prefs.edit().clear();
            for (var item : old.entrySet()) restore(editor, item.getKey(), item.getValue());
            editor.commit();
            WeekAlarms.reschedule(context);
        }
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

    private static View findDescription(View node, String value) {
        if (node.getContentDescription() != null && value.contentEquals(node.getContentDescription())) return node;
        if (node instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                var found = findDescription(group.getChildAt(i), value);
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
