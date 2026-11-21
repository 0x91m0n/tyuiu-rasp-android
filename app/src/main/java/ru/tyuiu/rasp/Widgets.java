package ru.tyuiu.rasp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class Widgets {
    enum Kind {
        NEXT(NextWidget.class, R.string.widget_next, 140, 85),
        DAY(DayWidget.class, R.string.widget_day, 200, 115),
        TODAY(null, R.string.widget_today, 200, 115),
        TOMORROW(TomorrowWidget.class, R.string.widget_tomorrow, 200, 115),
        WEEK(WeekWidget.class, R.string.widget_week, 200, 195),
        PROGRESS(ProgressWidget.class, R.string.widget_progress, 140, 85);

        final Class<? extends AppWidgetProvider> provider;
        final int title;
        final SizeF size;

        Kind(Class<? extends AppWidgetProvider> provider, int title, int width, int height) {
            this.provider = provider;
            this.title = title;
            this.size = new SizeF(width, height);
        }

        boolean agenda() { return this != NEXT && this != PROGRESS; }

        static List<Kind> providers() {
            return java.util.Arrays.stream(values()).filter(kind -> kind.provider != null).collect(Collectors.toList());
        }
    }

    record Instance(int id, Kind kind) {}
    record Active(Set<String> subgroups, Set<String> rolling, Set<String> progressGroups) {
        boolean any() { return !subgroups.isEmpty(); }
        boolean tracks(Schedule.Lesson lesson) {
            return subgroups.stream().anyMatch(subgroup -> Schedule.matches(lesson, subgroup));
        }
        boolean tracksProgress(Schedule.Lesson lesson) {
            return progressGroups.stream().anyMatch(subgroup -> Schedule.matches(lesson, subgroup));
        }
    }

    static List<Instance> installed(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        var found = new ArrayList<Instance>();
        for (Kind kind : Kind.providers()) {
            for (int id : manager.getAppWidgetIds(new ComponentName(context, kind.provider))) found.add(new Instance(id, kind));
        }
        return found;
    }

    static Kind providerFor(Context context, int id) {
        var info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id);
        if (info == null || !context.getPackageName().equals(info.provider.getPackageName())) return null;
        for (Kind kind : Kind.providers()) {
            if (kind.provider.getName().equals(info.provider.getClassName())) return kind;
        }
        return null;
    }

    static Active updateAll(Context context, ScheduleStore store, Schedule schedule, Instant now) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        var groups = new HashSet<String>();
        var rolling = new HashSet<String>();
        var progress = new HashSet<String>();
        for (Instance item : installed(context)) {
            var options = WidgetOptions.load(context, item.id(), item.kind());
            List<SizeF> sizes = sizes(manager.getAppWidgetOptions(item.id()), item.kind());
            manager.updateAppWidget(item.id(), renderSizes(context, store, schedule, now, item.id(), options, sizes));
            String subgroup = options.subgroup(schedule, store);
            groups.add(subgroup);
            if (options.kind() == Kind.DAY || options.kind() == Kind.WEEK) rolling.add(subgroup);
            if (options.kind() == Kind.PROGRESS) progress.add(subgroup);
        }
        return new Active(groups, rolling, progress);
    }

    static List<SizeF> sizes(Bundle information, Kind fallback) {
        var exact = information.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF.class);
        if (exact != null) {
            var valid = exact.stream().filter(size -> size != null && size.getWidth() > 0 && size.getHeight() > 0)
                    .distinct().limit(16).collect(Collectors.toList());
            if (!valid.isEmpty()) return valid;
        }
        int width = information.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        int height = information.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
        return List.of(new SizeF(width > 0 ? width : fallback.size.getWidth(),
                height > 0 ? height : fallback.size.getHeight()));
    }

    static RemoteViews renderSizes(Context context, ScheduleStore store, Schedule schedule, Instant now,
                                   int id, WidgetOptions options, List<SizeF> sizes) {
        if (sizes.size() == 1) return make(context, store, schedule, now, id, options, sizes.get(0));
        if (options.kind().agenda()) {
            // The list fills its viewport at any size. Send the entries once to the launcher.
            SizeF smallest = sizes.stream().min(java.util.Comparator.comparingDouble(SizeF::getHeight)).orElseThrow();
            return make(context, store, schedule, now, id, options, smallest);
        }
        var layouts = new LinkedHashMap<SizeF, RemoteViews>();
        for (SizeF size : sizes) layouts.put(size, make(context, store, schedule, now, id, options, size));
        return new RemoteViews(layouts);
    }

    static RemoteViews make(Context context, ScheduleStore store, Schedule schedule, Instant now,
                            int id, WidgetOptions options, SizeF size) {
        var view = new RemoteViews(context.getPackageName(), R.layout.widget_card);
        ThemePalette palette = ThemePalette.resolve(context, WidgetOptions.FOLLOW_THEME.equals(options.theme())
                ? store.theme() : options.theme());
        int background = (options.opacity() * 255 / 100) << 24 | palette.card & 0x00ffffff;
        view.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(background));
        boolean small = size.getHeight() < 105;
        int padding = Math.round((options.compact() || small ? 9 : 14) * context.getResources().getDisplayMetrics().density);
        view.setViewPadding(R.id.widget_root, padding, padding, padding, padding);
        int font = options.textSize();
        view.setTextViewTextSize(R.id.widget_label, TypedValue.COMPLEX_UNIT_SP, 11 + font * 2);
        view.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 17 + font * 3);
        view.setTextViewTextSize(R.id.widget_countdown, TypedValue.COMPLEX_UNIT_SP, 15 + font * 2);
        view.setTextColor(R.id.widget_label, palette.accent);
        view.setTextColor(R.id.widget_title, palette.text);
        view.setTextColor(R.id.widget_countdown, palette.accent);
        view.setViewVisibility(R.id.widget_label, options.has(WidgetOptions.HEADER) ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.widget_progress, View.GONE);
        view.setViewVisibility(R.id.widget_countdown, View.GONE);
        view.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime(), null, false);
        LocalDate today = now.atZone(Schedule.ZONE).toLocalDate();
        String subgroup = options.subgroup(schedule, store);
        Kind kind = options.kind();
        LocalDate target = switch (kind) {
            case TODAY -> today;
            case TOMORROW -> today.plusDays(1);
            default -> schedule.defaultDay(now, subgroup);
        };
        String label = switch (kind) {
            case NEXT -> "БЛИЖАЙШАЯ ПАРА";
            case DAY -> target.equals(today) ? "ПАРЫ НА СЕГОДНЯ" : "ПАРЫ НА ЗАВТРА";
            case TODAY -> "ПАРЫ НА СЕГОДНЯ";
            case TOMORROW -> "ПАРЫ НА ЗАВТРА";
            case WEEK -> "БЛИЖАЙШИЕ 7 ДНЕЙ";
            case PROGRESS -> "ПРОГРЕСС ПАРЫ";
        };
        boolean agenda = kind.agenda();
        view.setViewVisibility(R.id.widget_schedule_rows, agenda && !schedule.group.isEmpty() ? View.VISIBLE : View.GONE);
        view.setInt(R.id.widget_title, "setMaxLines", agenda || small ? 1 : 2);
        String title = "Выберите группу";
        String timing = "";
        String info = "Открыть Пары ТИУ";
        String teacher = "";
        if (!schedule.group.isEmpty()) {
            String group = options.has(WidgetOptions.GROUP) ? schedule.group : "";
            if (kind == Kind.WEEK) {
                title = group.isEmpty() ? target.format(Schedule.DATE) + " — " + target.plusDays(6).format(Schedule.DATE) : group;
                info = "";
            } else if (kind == Kind.DAY || kind == Kind.TODAY || kind == Kind.TOMORROW) {
                title = join(" · ", target.format(Schedule.DATE), group);
                info = "";
            } else {
                Schedule.Lesson lesson = schedule.next(now, subgroup);
                boolean active = lesson != null && !lesson.starts().isAfter(now);
                title = "Пар впереди нет";
                info = group;
                if (lesson != null) {
                    target = lesson.start().toLocalDate();
                    title = lesson.title();
                    timing = join(" · ", target.equals(today) ? "" : target.format(Schedule.DATE),
                            options.has(WidgetOptions.TIME) ? lesson.time() : "");
                    info = WidgetAgenda.details(lesson, options);
                    if (!small || info.isEmpty()) teacher = WidgetAgenda.teacher(lesson, options);
                    if (active) {
                        label = "СЕЙЧАС ИДЁТ";
                        if (kind == Kind.PROGRESS) {
                            int percent = lesson.progress(now);
                            timing = join(" · ", timing, percent + "%");
                            view.setViewVisibility(R.id.widget_progress, View.VISIBLE);
                            view.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(palette.accent));
                            view.setProgressBar(R.id.widget_progress, 100, percent, false);
                        }
                    } else if (kind == Kind.PROGRESS) label = "СЛЕДУЮЩАЯ ПАРА";
                    if (options.has(WidgetOptions.COUNTDOWN)) {
                        Instant end = active ? lesson.ends() : lesson.starts();
                        view.setViewVisibility(R.id.widget_countdown, View.VISIBLE);
                        view.setChronometerCountDown(R.id.widget_countdown, true);
                        view.setChronometer(R.id.widget_countdown, SystemClock.elapsedRealtime()
                                + Duration.between(now, end).toMillis(), null, true);
                    }
                }
            }
            if (schedule.demo) label = "ДЕМО · " + label;
            else if (!store.feed().isEmpty() && now.toEpochMilli() - store.lastSync() > 48 * 3600_000L) label = "СОХРАНЁННАЯ КОПИЯ";
        }
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            view.setOnClickPendingIntent(R.id.widget_root, ScheduleEvents.openWidget(context, id,
                    kind == Kind.WEEK ? "week" : "", target, subgroup));
        }
        view.setTextViewText(R.id.widget_label, options.title().isBlank() ? label : options.title());
        view.setTextViewText(R.id.widget_title, title);
        optionalText(view, R.id.widget_time, timing, 12 + font, palette.accent);
        optionalText(view, R.id.widget_details, info, 12 + font, palette.muted);
        optionalText(view, R.id.widget_teacher, WidgetAgenda.shortTeacher(teacher), 11 + font, palette.muted);
        if (!teacher.isEmpty()) view.setContentDescription(R.id.widget_teacher, teacher);
        if (agenda && !schedule.group.isEmpty()) {
            WidgetAgenda.show(context, view, options, palette, schedule, target, subgroup, size, id);
        } else if (!agenda && !schedule.group.isEmpty()) {
            fitSingleLesson(context, view, size);
        }
        return view;
    }

    private static void fitSingleLesson(Context context, RemoteViews widget, SizeF size) {
        var metrics = context.getResources().getDisplayMetrics();
        int width = Math.round(size.getWidth() * metrics.density);
        int height = Math.round(size.getHeight() * metrics.density);
        View sample = widget.apply(context, null);
        for (int field : new int[]{R.id.widget_teacher, R.id.widget_details, R.id.widget_time}) {
            if (measure(sample, width) <= height) return;
            View extra = sample.findViewById(field);
            if (extra.getVisibility() != View.VISIBLE) continue;
            extra.setVisibility(View.GONE);
            widget.setViewVisibility(field, View.GONE);
        }
        if (measure(sample, width) <= height) return;
        int inset = Math.round(5 * metrics.density);
        sample.setPadding(sample.getPaddingLeft(), inset, sample.getPaddingRight(), inset);
        widget.setViewPadding(R.id.widget_root, sample.getPaddingLeft(), inset, sample.getPaddingRight(), inset);
        if (measure(sample, width) > height) widget.setViewVisibility(R.id.widget_label, View.GONE);
    }

    static int measure(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    static void optionalText(RemoteViews view, int id, String value, int size, int color) {
        view.setViewVisibility(id, value.isBlank() ? View.GONE : View.VISIBLE);
        text(view, id, value, size, color);
    }

    static void text(RemoteViews view, int id, String value, int size, int color) {
        view.setTextViewText(id, value);
        view.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(id, color);
    }

    static String join(String separator, String... values) {
        return java.util.Arrays.stream(values).filter(value -> !value.isBlank()).collect(Collectors.joining(separator));
    }
}
