package ru.tyuiu.rasp;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class WidgetAgenda {
    private record Entry(String date, String gap, String leading, String title, String detail, String teacher) {}
    private record Block(List<RemoteViews> views, int height) {}

    private final Context context;
    private final WidgetOptions options;
    private final ThemePalette palette;

    private WidgetAgenda(Context context, WidgetOptions options, ThemePalette palette) {
        this.context = context;
        this.options = options;
        this.palette = palette;
    }

    static void show(Context context, RemoteViews widget, WidgetOptions options, ThemePalette palette,
                     Schedule schedule, LocalDate target, String subgroup, SizeF size) {
        var agenda = new WidgetAgenda(context, options, palette);
        agenda.place(widget, agenda.entries(schedule, target, subgroup), size);
    }

    private List<Entry> entries(Schedule schedule, LocalDate date, String subgroup) {
        var entries = new ArrayList<Entry>();
        if (options.kind() != Widgets.Kind.WEEK) {
            addDay(entries, schedule.day(date, subgroup), "", subgroup);
            return entries;
        }
        boolean full = options.has(WidgetOptions.WEEK_CLASSES);
        for (int index = 0; index < 7; index++) {
            var day = date.plusDays(index);
            var lessons = schedule.day(day, subgroup);
            String dateLabel = day.format(Schedule.DATE);
            if (full) {
                addDay(entries, lessons, dateLabel, subgroup);
            } else {
                String title = lessons.isEmpty() ? "Пар нет" : options.has(WidgetOptions.TIME)
                        ? "с " + lessons.get(0).start().format(Schedule.TIME) : lessons.get(0).title();
                entries.add(new Entry("", "", dateLabel, title, "", ""));
            }
        }
        return entries;
    }

    private void addDay(List<Entry> rows, List<Schedule.Lesson> lessons, String date, String subgroup) {
        if (lessons.isEmpty()) rows.add(new Entry(date, "", "", "Пар нет", "", ""));
        Instant previousEnd = null;
        for (var slot : Schedule.slots(lessons)) {
            var first = slot.get(0);
            long minutes = previousEnd == null ? 0 : Duration.between(previousEnd, first.starts()).toMinutes();
            String gap = minutes > 0 && options.has(WidgetOptions.GAPS) ? "Окно · " + minutes + " мин" : "";
            for (var lesson : slot) {
                String detail = Widgets.join(" · ",
                        subgroup.isEmpty() && !lesson.subgroup().isBlank() ? "Подгр. " + lesson.subgroup() : "",
                        details(lesson, options));
                rows.add(new Entry(date, gap,
                        options.has(WidgetOptions.TIME) ? first.start().format(Schedule.TIME) : "",
                        lesson.title(), detail, teacher(lesson, options)));
                date = "";
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

    private void place(RemoteViews widget, List<Entry> entries, SizeF size) {
        widget.removeAllViews(R.id.widget_schedule_rows);
        widget.setViewVisibility(R.id.widget_schedule_rows, View.VISIBLE);
        var shell = widget.apply(context, null);
        int width = px(size.getWidth());
        Widgets.measure(shell, width);
        LinearLayout parent = shell.findViewById(R.id.widget_schedule_rows);
        int bodyWidth = Math.max(1, parent.getMeasuredWidth());
        int available = px(size.getHeight()) - shell.getMeasuredHeight();
        int column = leadingWidth(entries, bodyWidth);
        int limit = options.lines() == 0 ? 8 : options.lines();
        boolean dense = options.compact() || size.getHeight() < 150;
        var visible = new ArrayList<Block>();
        int used = 0;
        for (Entry entry : entries) {
            if (visible.size() >= limit) break;
            Block block = block(parent, bodyWidth, entry, column, dense, false);
            if (used + block.height() > available) {
                if (!visible.isEmpty()) break;
                block = block(parent, bodyWidth, entry, column, true, true);
                if (block.height() > available) break;
            }
            visible.add(block);
            used += block.height();
        }
        RemoteViews footer = null;
        int footerHeight = 0;
        if (visible.size() < entries.size()) {
            footer = note("Ещё " + (entries.size() - visible.size()), 0, palette.muted, true);
            footerHeight = Widgets.measure(footer.apply(context, parent), bodyWidth);
            if (used + footerHeight > available && visible.size() > 1) {
                used -= visible.remove(visible.size() - 1).height();
                footer.setTextViewText(R.id.widget_note, "Ещё " + (entries.size() - visible.size()));
            }
        }
        for (Block block : visible) for (RemoteViews row : block.views()) widget.addView(R.id.widget_schedule_rows, row);
        if (footer != null && used + footerHeight <= available) widget.addView(R.id.widget_schedule_rows, footer);
    }

    private Block block(LinearLayout parent, int width, Entry entry, int column, boolean dense, boolean titleOnly) {
        var parts = new ArrayList<RemoteViews>();
        if (!entry.date().isEmpty()) parts.add(note(entry.date(), 0, palette.accent, false));
        if (!titleOnly && !entry.gap().isEmpty()) {
            parts.add(note(entry.gap(), column == 0 ? 0 : column + px(8), palette.muted, false));
        }
        parts.add(row(entry, column, dense, titleOnly));
        int height = 0;
        for (RemoteViews part : parts) height += Widgets.measure(part.apply(context, parent), width);
        return new Block(parts, height);
    }

    private RemoteViews row(Entry entry, int column, boolean dense, boolean titleOnly) {
        var row = new RemoteViews(context.getPackageName(), R.layout.widget_lesson_row);
        int font = options.textSize();
        row.setViewPadding(R.id.widget_lesson_row, 0, px(dense ? 3 : 5), 0, px(dense ? 3 : 5));
        row.setViewLayoutWidth(R.id.widget_row_time, column, TypedValue.COMPLEX_UNIT_PX);
        row.setViewVisibility(R.id.widget_row_time, column == 0 || entry.leading().isBlank() ? View.GONE : View.VISIBLE);
        Widgets.text(row, R.id.widget_row_time, entry.leading(), 12 + font, palette.accent);
        Widgets.text(row, R.id.widget_row_subject, entry.title(), 14 + font * 2, palette.text);
        row.setInt(R.id.widget_row_subject, "setMaxLines", dense ? 1 : 2);
        String details = titleOnly ? "" : entry.detail();
        String teacher = titleOnly ? "" : shortTeacher(entry.teacher());
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

    private RemoteViews note(String value, int indent, int color, boolean footer) {
        var line = new RemoteViews(context.getPackageName(), R.layout.widget_note);
        Widgets.text(line, R.id.widget_note, value, 11 + options.textSize(), color);
        line.setViewPadding(R.id.widget_note, indent, px(footer ? 3 : 6), 0, px(3));
        return line;
    }

    private int leadingWidth(List<Entry> entries, int bodyWidth) {
        var metrics = context.getResources().getDisplayMetrics();
        var paint = new TextPaint();
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12 + options.textSize(), metrics));
        float widest = 0;
        for (Entry entry : entries) widest = Math.max(widest, paint.measureText(entry.leading()));
        return widest == 0 ? 0 : Math.min((int) Math.ceil(widest) + px(2), bodyWidth / 3);
    }

    private int px(float dp) { return Math.round(dp * context.getResources().getDisplayMetrics().density); }
}
