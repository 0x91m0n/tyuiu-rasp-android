package ru.tyuiu.rasp;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class WidgetAgenda {
    private record Entry(LocalDate day, String heading, String gap, String leading, String title,
                         String detail, String teacher) {}

    private final Context context;
    private final WidgetOptions options;
    private final ThemePalette palette;

    private WidgetAgenda(Context context, WidgetOptions options, ThemePalette palette) {
        this.context = context;
        this.options = options;
        this.palette = palette;
    }

    static void show(Context context, RemoteViews widget, WidgetOptions options, ThemePalette palette,
                     Schedule schedule, LocalDate target, String subgroup, SizeF size, int id) {
        var agenda = new WidgetAgenda(context, options, palette);
        agenda.place(widget, agenda.entries(schedule, target, subgroup), size, id, subgroup);
    }

    private List<Entry> entries(Schedule schedule, LocalDate date, String subgroup) {
        var entries = new ArrayList<Entry>();
        if (options.kind() != Widgets.Kind.WEEK) {
            addDay(entries, schedule.day(date, subgroup), date, "", subgroup);
            return entries;
        }
        boolean full = options.has(WidgetOptions.WEEK_CLASSES);
        for (int index = 0; index < 7; index++) {
            var day = date.plusDays(index);
            var lessons = schedule.day(day, subgroup);
            String dateLabel = day.format(Schedule.DATE);
            if (full) {
                addDay(entries, lessons, day, dateLabel, subgroup);
            } else {
                String title = lessons.isEmpty() ? "Пар нет" : options.has(WidgetOptions.TIME)
                        ? "с " + lessons.get(0).start().format(Schedule.TIME) : lessons.get(0).title();
                entries.add(new Entry(day, "", "", dateLabel, title, "", ""));
            }
        }
        return entries;
    }

    private void addDay(List<Entry> rows, List<Schedule.Lesson> lessons, LocalDate day, String heading, String subgroup) {
        if (lessons.isEmpty()) rows.add(new Entry(day, heading, "", "", "Пар нет", "", ""));
        Instant previousEnd = null;
        for (var slot : Schedule.slots(lessons)) {
            var first = slot.get(0);
            long minutes = previousEnd == null ? 0 : Duration.between(previousEnd, first.starts()).toMinutes();
            String gap = minutes > 0 && options.has(WidgetOptions.GAPS) ? "Окно · " + minutes + " мин" : "";
            for (var lesson : slot) {
                String detail = Widgets.join(" · ",
                        subgroup.isEmpty() && !lesson.subgroup().isBlank() ? "Подгр. " + lesson.subgroup() : "",
                        details(lesson, options));
                rows.add(new Entry(day, heading, gap,
                        options.has(WidgetOptions.TIME) ? first.start().format(Schedule.TIME) : "",
                        lesson.title(), detail, teacher(lesson, options)));
                heading = "";
                gap = "";
            }
            if (previousEnd == null || first.ends().isAfter(previousEnd)) previousEnd = first.ends();
        }
    }

    static String details(Schedule.Lesson lesson, WidgetOptions options) {
        return Widgets.join(" · ", options.has(WidgetOptions.ROOM) ? lesson.room() : "",
                options.has(WidgetOptions.TYPE) ? lesson.type() : "");
    }

    static String teacher(Schedule.Lesson lesson, WidgetOptions options) {
        return options.has(WidgetOptions.TEACHER) ? lesson.teacher() : "";
    }

    static String shortTeacher(String teacher) {
        String[] parts = teacher.trim().split("\\s+");
        if (parts.length != 3 || !teacher.matches("[\\p{L}-]+(\\s+[\\p{L}-]+){2}")) return teacher;
        return parts[0] + " " + initial(parts[1]) + ". " + initial(parts[2]) + ".";
    }

    private static String initial(String name) {
        return name.substring(0, name.offsetByCodePoints(0, 1));
    }

    private void place(RemoteViews widget, List<Entry> entries, SizeF size, int id, String subgroup) {
        int column = leadingWidth(entries, size);
        boolean dense = options.compact() || size.getHeight() < 150;
        boolean clickable = id != AppWidgetManager.INVALID_APPWIDGET_ID;
        var items = new RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(2);
        long position = 0;
        for (Entry entry : entries) {
            var day = clickable ? new Intent().putExtra(ScheduleEvents.OPEN_DATE, entry.day().toString()) : null;
            if (!entry.heading().isEmpty()) {
                items.addItem(position++, link(note(entry.heading(), 0, palette.accent), R.id.widget_note, day));
            }
            if (!entry.gap().isEmpty()) {
                items.addItem(position++, link(note(entry.gap(), column == 0 ? 0 : column + px(8), palette.muted),
                        R.id.widget_note, day));
            }
            items.addItem(position++, link(row(entry, column, dense), R.id.widget_lesson_row, day));
        }
        if (clickable) widget.setPendingIntentTemplate(R.id.widget_schedule_rows, ScheduleEvents.openWidgetDay(context, id, subgroup));
        widget.setRemoteAdapter(R.id.widget_schedule_rows, items.build());
    }

    private static RemoteViews link(RemoteViews row, int viewId, Intent day) {
        if (day != null) row.setOnClickFillInIntent(viewId, day);
        return row;
    }

    private RemoteViews row(Entry entry, int column, boolean dense) {
        var row = new RemoteViews(context.getPackageName(), R.layout.widget_lesson_row);
        int font = options.textSize();
        row.setViewPadding(R.id.widget_lesson_row, 0, px(dense ? 3 : 5), 0, px(dense ? 3 : 5));
        row.setViewLayoutWidth(R.id.widget_row_time, column, TypedValue.COMPLEX_UNIT_PX);
        row.setViewVisibility(R.id.widget_row_time, column == 0 || entry.leading().isBlank() ? View.GONE : View.VISIBLE);
        Widgets.text(row, R.id.widget_row_time, entry.leading(), 12 + font, palette.accent);
        Widgets.text(row, R.id.widget_row_subject, entry.title(), 14 + font * 2, palette.text);
        String details = entry.detail();
        String teacher = shortTeacher(entry.teacher());
        if (dense && !details.isEmpty() && !teacher.isEmpty()) {
            row.setContentDescription(R.id.widget_row_metadata, Widgets.join(" · ", details, entry.teacher()));
            details = Widgets.join(" · ", details, teacher);
            teacher = "";
        }
        Widgets.optionalText(row, R.id.widget_row_metadata, details, 11 + font, palette.muted);
        Widgets.optionalText(row, R.id.widget_row_teacher, teacher, 11 + font, palette.muted);
        if (!teacher.isEmpty()) row.setContentDescription(R.id.widget_row_teacher, entry.teacher());
        return row;
    }

    private RemoteViews note(String value, int indent, int color) {
        var line = new RemoteViews(context.getPackageName(), R.layout.widget_note);
        Widgets.text(line, R.id.widget_note, value, 11 + options.textSize(), color);
        line.setViewPadding(R.id.widget_note, indent, px(5), 0, px(3));
        return line;
    }

    private int leadingWidth(List<Entry> entries, SizeF size) {
        var metrics = context.getResources().getDisplayMetrics();
        var paint = new TextPaint();
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12 + options.textSize(), metrics));
        float widest = 0;
        for (Entry entry : entries) widest = Math.max(widest, paint.measureText(entry.leading()));
        return widest == 0 ? 0 : Math.min((int) Math.ceil(widest) + px(2), px(size.getWidth()) / 3);
    }

    private int px(float dp) { return Math.round(dp * context.getResources().getDisplayMetrics().density); }
}
