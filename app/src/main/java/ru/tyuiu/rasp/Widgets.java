package ru.tyuiu.rasp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class Widgets {
    enum Kind {
        NEXT(NextWidget.class, R.string.widget_next, ""),
        DAY(DayWidget.class, R.string.widget_day, ""),
        TOMORROW(TomorrowWidget.class, R.string.widget_tomorrow, "tomorrow"),
        WEEK(WeekWidget.class, R.string.widget_week, "week"),
        PROGRESS(ProgressWidget.class, R.string.widget_progress, "");

        final Class<? extends AppWidgetProvider> provider;
        final int title;
        final String openView;

        Kind(Class<? extends AppWidgetProvider> provider, int title, String openView) {
            this.provider = provider;
            this.title = title;
            this.openView = openView;
        }
    }

    record Active(boolean any, boolean progress) {}

    static Active updateAll(Context context, ScheduleStore store, Schedule schedule, Instant now) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        boolean any = false;
        boolean progress = false;
        for (Kind kind : Kind.values()) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, kind.provider));
            if (ids.length == 0) continue;
            manager.updateAppWidget(ids, make(context, store, schedule, now, kind));
            any = true;
            if (kind == Kind.PROGRESS) progress = true;
        }
        return new Active(any, progress);
    }

    private static RemoteViews make(Context context, ScheduleStore store, Schedule schedule, Instant now, Kind kind) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.widget_card);
        view.setOnClickPendingIntent(R.id.widget_root, ScheduleEvents.open(context, kind.openView));
        ThemePalette palette = ThemePalette.resolve(context, store.theme());
        view.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(palette.card));
        view.setTextColor(R.id.widget_label, palette.accent);
        view.setTextColor(R.id.widget_title, palette.text);
        view.setTextColor(R.id.widget_details, palette.muted);
        view.setViewVisibility(R.id.widget_progress, View.GONE);
        LocalDate today = now.atZone(Schedule.ZONE).toLocalDate();
        String subgroup = store.subgroup();
        LocalDate defaultDay = schedule.defaultDay(now, subgroup);
        String label = switch (kind) {
            case NEXT -> "БЛИЖАЙШАЯ ПАРА";
            case DAY -> defaultDay.equals(today) ? "ПАРЫ НА СЕГОДНЯ" : "ПАРЫ НА ЗАВТРА";
            case TOMORROW -> "ПАРЫ НА ЗАВТРА";
            case WEEK -> "БЛИЖАЙШИЕ 7 ДНЕЙ";
            case PROGRESS -> "ПРОГРЕСС ПАРЫ";
        };
        String title = "Выберите группу";
        String info = "Открыть Пары ТИУ";
        if (!schedule.group.isEmpty()) {
            if (kind == Kind.WEEK) {
                title = schedule.group;
                var lines = new ArrayList<String>();
                for (int i = 0; i < 7; i++) {
                    LocalDate date = defaultDay.plusDays(i);
                    List<Schedule.Lesson> lessons = schedule.day(date, subgroup);
                    String when = lessons.isEmpty() ? "пар нет" : "с " + lessons.get(0).start().format(Schedule.TIME);
                    lines.add(date.format(Schedule.DATE) + "  ·  " + when);
                }
                info = String.join("\n", lines);
            } else if (kind == Kind.DAY || kind == Kind.TOMORROW) {
                LocalDate date = kind == Kind.TOMORROW ? today.plusDays(1) : defaultDay;
                title = date.format(Schedule.DATE) + " · " + schedule.group;
                info = dayLines(schedule.day(date, subgroup));
            } else {
                Schedule.Lesson lesson = schedule.next(now, subgroup);
                boolean active = lesson != null && !lesson.starts().isAfter(now);
                title = "Пар впереди нет";
                info = "Открыть расписание";
                if (lesson != null) {
                    title = lesson.title();
                    LocalDate date = lesson.start().toLocalDate();
                    info = (date.equals(today) ? "" : date.format(Schedule.DATE) + " · ") + lesson.time()
                            + (lesson.room().isEmpty() ? "" : " · " + lesson.room());
                    if (active) {
                        label = "СЕЙЧАС ИДЁТ";
                        if (kind == Kind.PROGRESS) {
                            int percent = lesson.progress(now);
                            info = percent + "% · до " + lesson.end().format(Schedule.TIME);
                            view.setViewVisibility(R.id.widget_progress, View.VISIBLE);
                            view.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(palette.accent));
                            view.setProgressBar(R.id.widget_progress, 100, percent, false);
                        }
                    } else if (kind == Kind.PROGRESS) {
                        label = "СЛЕДУЮЩАЯ ПАРА";
                    }
                }
            }
            if (schedule.demo) label = "ДЕМО · " + label;
            else if (!store.feed().isEmpty() && now.toEpochMilli() - store.lastSync() > 48 * 3600_000L) {
                label = "СОХРАНЁННАЯ КОПИЯ";
            }
        }
        view.setTextViewText(R.id.widget_label, label);
        view.setTextViewText(R.id.widget_title, title);
        view.setTextViewText(R.id.widget_details, info);
        view.setInt(R.id.widget_details, "setMaxLines", kind == Kind.WEEK ? 7 : 5);
        return view;
    }

    private static String dayLines(List<Schedule.Lesson> lessons) {
        if (lessons.isEmpty()) return "Пар нет";
        var lines = new java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>>();
        for (Schedule.Lesson lesson : lessons) {
            String time = lesson.start().format(Schedule.TIME);
            lines.computeIfAbsent(time, ignored -> new java.util.LinkedHashSet<>()).add(lesson.title());
        }
        var result = new ArrayList<String>();
        for (var entry : lines.entrySet()) {
            if (result.size() == 4) break;
            result.add(entry.getKey() + "  " + String.join(" / ", entry.getValue()));
        }
        if (lines.size() > 4) result.add("Ещё " + (lines.size() - 4));
        return String.join("\n", result);
    }
}
