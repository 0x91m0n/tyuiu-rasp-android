package ru.tyuiu.rasp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class Widgets {
    enum Kind {
        NEXT(NextWidget.class, R.string.widget_next, 85),
        DAY(DayWidget.class, R.string.widget_day, 115),
        TODAY(null, R.string.widget_today, 115),
        TOMORROW(TomorrowWidget.class, R.string.widget_tomorrow, 115),
        WEEK(WeekWidget.class, R.string.widget_week, 195),
        PROGRESS(ProgressWidget.class, R.string.widget_progress, 85);

        final Class<? extends AppWidgetProvider> provider;
        final int title;
        final int height;

        Kind(Class<? extends AppWidgetProvider> provider, int title, int height) {
            this.provider = provider;
            this.title = title;
            this.height = height;
        }

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
            int height = manager.getAppWidgetOptions(item.id()).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            manager.updateAppWidget(item.id(), make(context, store, schedule, now, item.id(), options, height));
            String subgroup = options.subgroup(schedule, store);
            groups.add(subgroup);
            if (options.kind() == Kind.DAY || options.kind() == Kind.WEEK) rolling.add(subgroup);
            if (options.kind() == Kind.PROGRESS) progress.add(subgroup);
        }
        return new Active(groups, rolling, progress);
    }

    static RemoteViews make(Context context, ScheduleStore store, Schedule schedule, Instant now,
                            int id, WidgetOptions options, int availableHeight) {
        var view = new RemoteViews(context.getPackageName(), R.layout.widget_card);
        ThemePalette palette = ThemePalette.resolve(context, WidgetOptions.FOLLOW_THEME.equals(options.theme())
                ? store.theme() : options.theme());
        int background = (options.opacity() * 255 / 100) << 24 | palette.card & 0x00ffffff;
        view.setColorStateList(R.id.widget_root, "setBackgroundTintList", ColorStateList.valueOf(background));
        int padding = Math.round((options.compact() ? 9 : 14) * context.getResources().getDisplayMetrics().density);
        view.setViewPadding(R.id.widget_root, padding, padding, padding, padding);
        int font = options.textSize();
        view.setTextViewTextSize(R.id.widget_label, TypedValue.COMPLEX_UNIT_SP, 11 + font * 2);
        view.setTextViewTextSize(R.id.widget_title, TypedValue.COMPLEX_UNIT_SP, 17 + font * 3);
        view.setTextViewTextSize(R.id.widget_details, TypedValue.COMPLEX_UNIT_SP, 13 + font * 2);
        view.setTextViewTextSize(R.id.widget_countdown, TypedValue.COMPLEX_UNIT_SP, 15 + font * 2);
        view.setTextColor(R.id.widget_label, palette.accent);
        view.setTextColor(R.id.widget_title, palette.text);
        view.setTextColor(R.id.widget_details, palette.muted);
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
        int height = availableHeight > 0 ? availableHeight : kind.height;
        int lines = options.lines() > 0 ? options.lines()
                : Math.max(1, Math.min(8, (height - (options.compact() ? 38 : 51)) / (14 + font * 2)));
        if (kind == Kind.WEEK && !options.has(WidgetOptions.WEEK_CLASSES)) lines = Math.min(7, lines);
        String title = "Выберите группу";
        String info = "Открыть Пары ТИУ";
        if (!schedule.group.isEmpty()) {
            String group = options.has(WidgetOptions.GROUP) ? schedule.group : "";
            if (kind == Kind.WEEK) {
                title = group.isEmpty() ? target.format(Schedule.DATE) + " — " + target.plusDays(6).format(Schedule.DATE) : group;
                info = summarize(weekLines(schedule, target, subgroup, options), lines, options.compact());
            } else if (kind == Kind.DAY || kind == Kind.TODAY || kind == Kind.TOMORROW) {
                title = join(" · ", target.format(Schedule.DATE), group);
                info = summarize(dayLines(schedule.day(target, subgroup), options), lines, options.compact());
            } else {
                Schedule.Lesson lesson = schedule.next(now, subgroup);
                boolean active = lesson != null && !lesson.starts().isAfter(now);
                title = "Пар впереди нет";
                info = group;
                if (lesson != null) {
                    target = lesson.start().toLocalDate();
                    title = lesson.title();
                    info = join(" · ", target.equals(today) ? "" : target.format(Schedule.DATE),
                            options.has(WidgetOptions.TIME) ? lesson.time() : "", metadata(lesson, options));
                    if (active) {
                        label = "СЕЙЧАС ИДЁТ";
                        if (kind == Kind.PROGRESS) {
                            int percent = lesson.progress(now);
                            info = join(" · ", percent + "%", info);
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
        view.setTextViewText(R.id.widget_details, info);
        view.setViewVisibility(R.id.widget_details, info.isEmpty() ? View.GONE : View.VISIBLE);
        view.setInt(R.id.widget_details, "setMaxLines", lines);
        return view;
    }

    private static List<String> weekLines(Schedule schedule, LocalDate first, String subgroup, WidgetOptions options) {
        var lines = new ArrayList<String>();
        for (int i = 0; i < 7; i++) {
            var date = first.plusDays(i);
            var lessons = schedule.day(date, subgroup);
            if (options.has(WidgetOptions.WEEK_CLASSES) && !lessons.isEmpty()) {
                lines.add(date.format(Schedule.DATE));
                lines.addAll(dayLines(lessons, options));
            } else {
                String detail = lessons.isEmpty() ? "пар нет" : options.has(WidgetOptions.TIME)
                        ? "с " + lessons.get(0).start().format(Schedule.TIME) : lessons.get(0).title();
                lines.add(join("  ·  ", date.format(Schedule.DATE), detail));
            }
        }
        return lines;
    }

    private static List<String> dayLines(List<Schedule.Lesson> lessons, WidgetOptions options) {
        if (lessons.isEmpty()) return List.of("Пар нет");
        var lines = new ArrayList<String>();
        Instant end = null;
        for (var slot : Schedule.slots(lessons)) {
            var first = slot.get(0);
            long gap = end == null ? 0 : Duration.between(end, first.starts()).toMinutes();
            if (gap > 0 && options.has(WidgetOptions.GAPS)) lines.add("Окно · " + gap + " мин");
            String subjects = slot.stream().map(lesson -> join(" · ", lesson.title(), metadata(lesson, options)))
                    .distinct().collect(Collectors.joining(" / "));
            lines.add(join("  ", options.has(WidgetOptions.TIME) ? first.start().format(Schedule.TIME) : "", subjects));
            if (end == null || first.ends().isAfter(end)) end = first.ends();
        }
        return lines;
    }

    private static String metadata(Schedule.Lesson lesson, WidgetOptions options) {
        return join(" · ", options.has(WidgetOptions.TYPE) ? lesson.type() : "",
                options.has(WidgetOptions.ROOM) ? lesson.room() : "", options.has(WidgetOptions.TEACHER) ? lesson.teacher() : "");
    }

    private static String summarize(List<String> rows, int lines, boolean compact) {
        var visible = new ArrayList<>(rows.subList(0, Math.min(rows.size(), lines)));
        if (rows.size() > lines && lines > 1) {
            visible.set(lines - 1, compact ? "…" : "Ещё " + (rows.size() - lines + 1));
        }
        return String.join("\n", visible);
    }

    private static String join(String separator, String... values) {
        return java.util.Arrays.stream(values).filter(value -> !value.isBlank()).collect(Collectors.joining(separator));
    }
}
