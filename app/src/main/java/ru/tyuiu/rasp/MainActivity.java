package ru.tyuiu.rasp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT = 101, EXPORT = 102, NOTIFICATIONS = 103;
    private static final String OFFICIAL = OfficialTiu.HOME;
    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Schedule.RU);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    private ScheduleStore store;
    private GlyphProgress glyph;
    private LinearLayout page;
    private ScrollView scroll;
    private LocalDate date;
    private LocalDate shownToday;
    private ThemePalette palette;
    private boolean followDay = true;
    private boolean week;
    private TextView featuredStatus;
    private ProgressBar featuredProgress;
    private String featuredKey = "";
    private List<Schedule.Group> cachedGroups = List.of();
    private String cachedCatalog = "";
    private long catalogFetchedAt;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private boolean busy;
    private boolean foreground;

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            Schedule schedule = store.read();
            Instant now = Instant.now();
            LocalDate today = Schedule.today();
            String key = featuredKey(schedule.next(now, store.subgroup()), now);
            boolean switchDay = followDay && !date.equals(schedule.defaultDay(now, store.subgroup()));
            if (switchDay || !today.equals(shownToday) || !key.equals(featuredKey)) render(!switchDay);
            else updateProgress(now);
            handler.postDelayed(this, 5_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        store = new ScheduleStore(this);
        palette = ThemePalette.resolve(this, store.theme());
        if (palette == ThemePalette.LIGHT) setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        glyph = new GlyphProgress(this);
        date = state == null ? Schedule.today() : LocalDate.parse(state.getString("date", Schedule.today().toString()));
        if (state == null) applyEntry(getIntent());
        else {
            followDay = state.getBoolean("followDay", true);
            week = state.getBoolean("week");
        }
        preferenceListener = (prefs, key) -> {
            if ("data".equals(key) && foreground && !busy) render(true);
        };
        store.prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        RefreshJob.configure(this);
        render(false);
        var insets = getWindow().getInsetsController();
        if (insets != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            insets.setSystemBarsAppearance(palette == ThemePalette.LIGHT ? mask : 0, mask);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("date", date.toString());
        state.putBoolean("followDay", followDay);
        state.putBoolean("week", week);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyEntry(intent);
        render(false);
    }

    private void applyEntry(Intent intent) {
        String view = intent.getStringExtra(ScheduleEvents.OPEN_VIEW);
        week = "week".equals(view);
        followDay = !"tomorrow".equals(view);
        if (!followDay) date = Schedule.today().plusDays(1);
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        ScheduleEvents.run(this, true);
        render(true);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        foreground = false;
        handler.removeCallbacks(ticker);
        glyph.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void render(boolean keepScroll) {
        int scrollY = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        shownToday = Schedule.today();
        Schedule schedule = store.read();
        Instant now = Instant.now();
        if (followDay) date = schedule.defaultDay(now, store.subgroup());
        Schedule.Lesson next = schedule.next(now, store.subgroup());
        featuredKey = featuredKey(next, now);
        featuredStatus = null;
        featuredProgress = null;

        var outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(palette.background);
        outer.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        page = column();
        pad(page, 20, 22, 20, 29);
        scroll.addView(page);
        outer.addView(scroll);
        setContentView(outer);
        if (scrollY != 0) scroll.post(() -> scroll.scrollTo(0, scrollY));

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        var heading = label("Пары ТИУ", 29, palette.text, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("Настройки", false, this::settings));
        page.addView(top);

        String group = schedule.group;
        String groupLabel = group.isEmpty() ? "Выбрать группу" : group;
        if (schedule.demo) groupLabel += " · демо";
        if (!store.subgroup().isBlank()) groupLabel += " · подгр. " + store.subgroup();
        var groupButton = button(groupLabel + "  ▾", false, this::chooseGroup);
        if (!group.isEmpty()) groupButton.setContentDescription("Выбрать группу. Сейчас " + groupLabel);
        add(page, groupButton, 12);
        if (busy) {
            var progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(true);
            progress.setContentDescription("Загрузка расписания");
            add(page, progress, 8);
        }

        if (group.isEmpty()) {
            var welcome = box(palette.card);
            welcome.addView(label("Расписание твоей группы", 24, palette.text, true));
            add(welcome, label("Выбери группу, чтобы увидеть пары, аудитории и преподавателей.", 15, palette.muted, false), 10);
            add(welcome, button("Открыть сайт расписания ТИУ", false, () -> openLink(OFFICIAL)), 14);
            add(welcome, button("Посмотреть демо", false, () -> save(Schedule.example(), "")), 8);
            add(page, welcome, 22);
            add(page, button("Виджеты", false, this::pinWidget), 16);
            return;
        }

        if (next != null) {
            boolean active = !next.starts().isAfter(now);
            var featured = box(palette.accent);
            featuredStatus = label(active ? "СЕЙЧАС ИДЁТ" : "БЛИЖАЙШАЯ ПАРА", 12, palette.onAccent, true);
            featured.addView(featuredStatus);
            add(featured, label(next.title(), 25, palette.onAccent, true), 7);
            String day = next.start().toLocalDate().equals(shownToday) ? "" : next.start().format(Schedule.DATE) + " · ";
            add(featured, label(day + next.time() + (next.room().isBlank() ? "" : "  ·  " + next.room()),
                    15, palette.onAccent, false), 7);
            if (active) {
                featuredProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                featuredProgress.setProgressTintList(ColorStateList.valueOf(palette.onAccent));
                featuredProgress.setContentDescription("Прогресс пары");
                add(featured, featuredProgress, 7);
            }
            add(page, featured, 22);
        }

        LinearLayout dateRow = row();
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        var previous = button("‹", false, () -> moveDay(-1));
        previous.setContentDescription(week ? "Предыдущие 7 дней" : "Предыдущий день");
        dateRow.addView(previous);
        TextView dateTitle = label(week ? date.format(Schedule.DATE) + " — " + date.plusDays(6).format(Schedule.DATE)
                : capitalize(date.format(FULL_DATE)), 18, palette.text, true);
        dateTitle.setGravity(Gravity.CENTER);
        dateTitle.setOnClickListener(v -> new android.app.DatePickerDialog(this, (picker, year, month, dayOfMonth) -> {
            date = LocalDate.of(year, month + 1, dayOfMonth);
            followDay = false;
            render(false);
        }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show());
        dateRow.addView(dateTitle, new LinearLayout.LayoutParams(0, -2, 1));
        var following = button("›", false, () -> moveDay(1));
        following.setContentDescription(week ? "Следующие 7 дней" : "Следующий день");
        dateRow.addView(following);
        add(page, dateRow, 22);

        LinearLayout shortcuts = row();
        for (int offset = 0; offset < 3; offset++) {
            LocalDate target = shownToday.plusDays(offset);
            boolean showWeek = offset == 2;
            String name = showWeek ? "7 дней" : offset == 0 ? "Сегодня" : "Завтра";
            var dayButton = button(name, showWeek ? week : !week && date.equals(target), () -> {
                if (!showWeek) {
                    date = target;
                    followDay = false;
                } else if (!week) {
                    date = schedule.defaultDay(now, store.subgroup());
                    followDay = false;
                }
                week = showWeek;
                render(false);
            });
            var weight = new LinearLayout.LayoutParams(0, -2, 1);
            if (offset > 0) weight.setMarginStart(dp(8));
            shortcuts.addView(dayButton, weight);
        }
        add(page, shortcuts, 9);
        if (!week && schedule.source.endsWith("типовые недели")) {
            String parity = OfficialTiu.evenWeek(date) ? "Чётная учебная неделя" : "Нечётная учебная неделя";
            add(page, label(parity, 13, palette.muted, false), 9);
        }
        List<Schedule.Lesson> lessons = schedule.day(date, store.subgroup());
        if (week) {
            for (int offset = 0; offset < 7; offset++) {
                LocalDate target = date.plusDays(offset);
                add(page, label(capitalize(target.format(FULL_DATE)), 18, palette.text, true), 16);
                addLessons(schedule.day(target, store.subgroup()));
            }
        } else {
            addLessons(lessons);
        }
        if (!schedule.demo) {
            Schedule.Lesson first = lessons.isEmpty() || !lessons.get(0).starts().isAfter(now)
                    ? firstUpcomingDay(schedule, now) : lessons.get(0);
            if (first != null) {
                String when = date.equals(first.start().toLocalDate()) ? "" : " · " + first.start().format(Schedule.DATE);
                add(page, button("Будильник к первой паре" + when, false, () -> clock(first)), 16);
            }
        }
        add(page, button("Виджеты", false, this::pinWidget), 9);
        if (!store.feed().isEmpty()) add(page, button("Обновить расписание", false, this::refresh), 9);
        String source = schedule.source.isBlank() ? "Сохранённое расписание" : schedule.source;
        if (!store.feed().isEmpty() && System.currentTimeMillis() - store.lastSync() > 48 * 3600_000L) {
            source += " · давно не обновлялось";
        }
        if (!ZoneId.systemDefault().equals(Schedule.ZONE)) source += " · время Тюмени";
        add(page, label(source, 12, palette.muted, false), 15);
        if (store.feed().startsWith(OfficialTiu.HOME + "schedule/group/")) {
            add(page, button("Эта группа на сайте ТИУ", false, () -> openLink(store.feed())), 8);
        }
        updateProgress(now);
    }

    private void addLessons(List<Schedule.Lesson> lessons) {
        if (lessons.isEmpty()) {
            var empty = box(palette.card);
            empty.addView(label("Пар нет", 16, palette.muted, false));
            add(page, empty, 10);
        }
        LinearLayout slot = null;
        Schedule.Lesson previous = null;
        for (Schedule.Lesson lesson : lessons) {
            if (previous == null || !previous.start().equals(lesson.start()) || !previous.end().equals(lesson.end())) {
                slot = box(palette.card);
                slot.addView(label(lesson.time(), 14, palette.accent, true));
                add(page, slot, 10);
            }
            add(slot, label(lesson.title(), 19, palette.text, true), 6);
            if (!lesson.meta().isBlank()) add(slot, label(lesson.meta(), 14, palette.muted, false), 4);
            previous = lesson;
        }
    }

    private static String featuredKey(Schedule.Lesson next, Instant now) {
        return next == null ? "" : next.key() + next.starts().isAfter(now);
    }

    private void updateProgress(Instant now) {
        Schedule schedule = store.read();
        Schedule.Lesson lesson = schedule.next(now, store.subgroup());
        boolean active = lesson != null && !lesson.starts().isAfter(now);
        int percent = active ? lesson.progress(now) : 0;
        if (featuredProgress != null && featuredStatus != null && active) {
            featuredProgress.setProgress(percent);
            long minutes = Math.max(1, (lesson.ends().getEpochSecond() - now.getEpochSecond() + 59) / 60);
            featuredStatus.setText(getString(R.string.class_remaining, minutes));
        }
        glyph.show(foreground && store.glyph() && active && !schedule.demo ? percent : -1);
    }

    private Schedule.Lesson firstUpcomingDay(Schedule schedule, Instant now) {
        for (Schedule.Lesson lesson : schedule.lessons) {
            if (lesson.start().toLocalDate().isAfter(shownToday) && lesson.starts().isAfter(now)
                    && Schedule.matches(lesson, store.subgroup())) return lesson;
        }
        return null;
    }

    private void moveDay(int delta) {
        followDay = false;
        date = date.plusDays(week ? 7L * delta : delta);
        render(false);
    }

    private void chooseGroup() {
        String catalog = store.catalog();
        if (catalog.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Выбор группы")
                    .setMessage("Для списка групп нужен каталог расписания. Можно подключить HTTPS-каталог или открыть файл своей группы.")
                    .setPositiveButton("Подключить каталог", (dialog, which) -> catalogSettings())
                    .setNeutralButton("Открыть файл", (dialog, which) -> importSchedule())
                    .setNegativeButton("Закрыть", null).show();
            return;
        }
        if (catalog.equals(cachedCatalog) && android.os.SystemClock.elapsedRealtime() - catalogFetchedAt < 15 * 60_000L) {
            groupList(cachedGroups);
        } else {
            work(() -> Schedule.groups(Schedule.https(catalog)), groups -> showCatalog(catalog, groups));
        }
    }

    private void showCatalog(String catalog, List<Schedule.Group> groups) {
        cachedCatalog = catalog;
        cachedGroups = groups;
        catalogFetchedAt = android.os.SystemClock.elapsedRealtime();
        groupList(groups);
    }

    private void groupList(List<Schedule.Group> groups) {
        LinearLayout panel = column();
        pad(panel, 22, 2, 22, 8);
        EditText search = edit("Название группы", "");
        panel.addView(search);
        var adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        var entries = new ListView(this);
        var filtered = new java.util.ArrayList<Schedule.Group>();
        entries.setAdapter(adapter);
        panel.addView(entries, new LinearLayout.LayoutParams(-1,
                Math.min(dp(360), getResources().getDisplayMetrics().heightPixels / 2)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Группы ТИУ").setView(panel)
                .setNegativeButton("Закрыть", null).create();
        Runnable filter = () -> {
            filtered.clear();
            String query = groupKey(search.getText().toString());
            var names = new java.util.ArrayList<String>();
            for (Schedule.Group group : groups) {
                if (!groupKey(group.name()).contains(query)) continue;
                filtered.add(group);
                String subtitle = group.details().isBlank() ? "" : "\n" + group.details();
                names.add(group.name() + subtitle);
            }
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(names.isEmpty() ? List.of("Группы не найдены") : names);
            adapter.notifyDataSetChanged();
            entries.setSelection(0);
        };
        entries.setOnItemClickListener((list, view, position, id) -> {
            if (filtered.isEmpty()) return;
            Schedule.Group group = filtered.get(position);
            dialog.dismiss();
            work(() -> {
                Schedule data = Schedule.load(group.url());
                if (data.demo || !data.group.equals(group.name())) {
                    throw new IllegalArgumentException("Источник вернул другое расписание");
                }
                return data;
            }, data -> {
                boolean changed = !store.read().group.equals(data.group);
                save(data, group.url().toString());
                if (changed && !data.subgroups().isEmpty()) subgroup();
            });
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            @Override public void afterTextChanged(Editable content) {}
        });
        dialog.show();
        filter.run();
    }

    private static String groupKey(String value) {
        return value.toLowerCase(Schedule.RU).replaceAll("[\\s\\p{Pd}]+", "");
    }

    private void catalogSettings() {
        EditText input = edit("HTTPS-адрес каталога групп", store.catalog());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        var dialog = new AlertDialog.Builder(this).setTitle("Каталог расписания").setView(wrap(input))
                .setPositiveButton("Подключить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = input.getText().toString().trim();
            try {
                Schedule.https(value);
            } catch (Exception invalid) {
                input.setError("Введите HTTPS-адрес каталога");
                return;
            }
            dialog.dismiss();
            work(() -> Schedule.groups(Schedule.https(value)), groups -> {
                store.prefs.edit().putString("catalog", value).apply();
                showCatalog(value, groups);
            });
        }));
        dialog.show();
    }

    static boolean isNextOccurrence(LocalDateTime wake, LocalDateTime now) {
        LocalDateTime occurrence = now.toLocalDate().atTime(wake.toLocalTime());
        if (!occurrence.isAfter(now)) occurrence = occurrence.plusDays(1);
        return wake.equals(occurrence);
    }

    private void refresh() {
        String url = store.feed();
        if (url.isEmpty()) return;
        work(() -> Schedule.load(Schedule.https(url)), data -> {
            try {
                if (!store.refresh(url, data)) throw new IllegalArgumentException("Источник вернул другое расписание");
                ScheduleEvents.run(this, false);
                render(true);
                toast("Расписание обновлено");
            } catch (Exception failure) { report(failure); }
        });
    }

    private void save(Schedule schedule, String feed) {
        try {
            store.select(schedule, feed);
            date = Schedule.today();
            followDay = true;
            week = false;
            ScheduleEvents.run(this, false);
            RefreshJob.configure(this);
            render(false);
        } catch (Exception failure) { report(failure); }
    }

    private void settings() {
        var names = new java.util.ArrayList<String>();
        var actions = new java.util.ArrayList<Runnable>();
        option(names, actions, "Каталог групп", this::catalogSettings);
        option(names, actions, "Открыть файл расписания", this::importSchedule);
        if (!store.read().group.isEmpty()) {
            option(names, actions, "Подгруппа", this::subgroup);
            if (!store.read().demo) option(names, actions, "Экспорт расписания в JSON", this::exportSchedule);
        }
        option(names, actions, "Напоминания о парах", this::reminderSettings);
        option(names, actions, "Тема оформления", this::themeSettings);
        option(names, actions, "Будильник за " + store.wakeLead() + " мин до пары",
                () -> minutes("Когда будить", "wakeLead", store.wakeLead(), 360));
        option(names, actions, "Добавить виджет", this::pinWidget);
        if (GlyphProgress.supported()) option(names, actions, "Glyph · " + (store.glyph() ? "включён" : "выключен"), this::glyphSettings);
        option(names, actions, "Открыть сайт расписания ТИУ", () -> openLink(OFFICIAL));
        new AlertDialog.Builder(this).setTitle("Настройки").setItems(names.toArray(new String[0]),
                (dialog, which) -> actions.get(which).run()).setNegativeButton("Закрыть", null).show();
    }

    private static void option(List<String> names, List<Runnable> actions, String name, Runnable action) {
        names.add(name);
        actions.add(action);
    }

    private void subgroup() {
        List<String> available = store.read().subgroups();
        if (available.isEmpty()) {
            toast("В расписании группы нет раздельных занятий");
            return;
        }
        var choices = new java.util.ArrayList<String>();
        choices.add("Вся группа");
        for (String group : available) choices.add("Подгруппа " + group);
        int current = available.indexOf(store.subgroup()) + 1;
        new AlertDialog.Builder(this).setTitle("Подгруппа")
                .setSingleChoiceItems(choices.toArray(new String[0]), current, (dialog, which) -> {
                    store.prefs.edit().putString("subgroup", which == 0 ? "" : available.get(which - 1)).apply();
                    ScheduleEvents.run(this, false);
                    render(true);
                    dialog.dismiss();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void reminderSettings() {
        String timing = ScheduleEvents.exact(this) ? "Точные напоминания разрешены" : "Android может задерживать напоминания";
        String state = store.notifications()
                ? (ScheduleEvents.notificationsAvailable(this) ? "Напоминания включены." : "Уведомления выключены в Android.")
                : "Напоминания выключены.";
        String[] items = {store.notifications() && ScheduleEvents.notificationsAvailable(this) ? "Выключить" : "Включить",
                "За " + store.lead() + " мин до начала", timing};
        new AlertDialog.Builder(this).setTitle(state).setItems(items, (dialog, which) -> {
            if (which == 0) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                } else if (!ScheduleEvents.notificationsAvailable(this)) {
                    startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                } else {
                    store.prefs.edit().putBoolean("notifications", !store.notifications()).apply();
                    ScheduleEvents.run(this, false);
                }
            } else if (which == 1) {
                minutes("Когда напоминать", "lead", store.lead(), 180);
            } else if (!ScheduleEvents.exact(this)) {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException unavailable) { toast("Настройка недоступна"); }
            }
        }).setNegativeButton("Закрыть", null).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(requestCode, permissions, result);
        if (requestCode == NOTIFICATIONS && result.length > 0) {
            if (result[0] == PackageManager.PERMISSION_GRANTED) {
                store.prefs.edit().putBoolean("notifications", true).apply();
                ScheduleEvents.run(this, false);
            } else {
                toast("Уведомления можно разрешить в настройках Android");
            }
        }
    }

    private void minutes(String title, String key, int value, int max) {
        EditText input = edit("Минут до пары", Integer.toString(value));
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        var dialog = new AlertDialog.Builder(this).setTitle(title).setView(wrap(input))
                .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                int lead = Integer.parseInt(input.getText().toString());
                if (lead < 1 || lead > max) throw new NumberFormatException();
                store.prefs.edit().putInt(key, lead).apply();
                ScheduleEvents.run(this, false);
                dialog.dismiss();
            } catch (NumberFormatException invalid) { input.setError("От 1 до " + max + " мин"); }
        }));
        dialog.show();
    }

    private void clock(Schedule.Lesson first) {
        if (!store.feed().isEmpty() && System.currentTimeMillis() - store.lastSync() > 48 * 3600_000L) {
            toast("Обновите расписание перед установкой будильника");
            return;
        }
        ZoneId deviceZone = ZoneId.systemDefault();
        LocalDateTime wake = first.starts().minus(store.wakeLead(), ChronoUnit.MINUTES)
                .atZone(deviceZone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES);
        String message = capitalize(wake.format(FULL_DATE)) + " в " + wake.format(Schedule.TIME)
                + " по времени телефона. Первая пара — " + first.title() + ".";
        var dialog = new AlertDialog.Builder(this).setTitle("Будильник к первой паре").setNegativeButton("Закрыть", null);
        if (!isNextOccurrence(wake, LocalDateTime.now(deviceZone))) {
            dialog.setMessage(message + "\n\nДля этой даты установи будильник вручную в приложении «Часы».")
                    .setPositiveButton("Открыть часы", (d, which) -> startClock(new Intent(AlarmClock.ACTION_SHOW_ALARMS)));
        } else {
            dialog.setMessage(message).setPositiveButton("Открыть часы", (d, which) -> {
                if (!isNextOccurrence(wake, LocalDateTime.now(deviceZone)) || !deviceZone.equals(ZoneId.systemDefault())) {
                    toast("Время изменилось. Откройте расчёт будильника ещё раз.");
                    return;
                }
                Intent alarm = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, wake.getHour())
                        .putExtra(AlarmClock.EXTRA_MINUTES, wake.getMinute())
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Пары ТИУ")
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                startClock(alarm);
            });
        }
        dialog.show();
    }

    private void startClock(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException unavailable) { toast("Приложение «Часы» не найдено"); }
    }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (!manager.isRequestPinAppWidgetSupported()) {
            toast("Добавьте виджет через меню домашнего экрана");
            return;
        }
        var types = Widgets.Kind.values();
        String[] titles = java.util.Arrays.stream(types).map(item -> getString(item.title)).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Виджеты ТИУ").setItems(titles,
                (dialog, choice) -> manager.requestPinAppWidget(new ComponentName(this, types[choice].provider), null, null))
                .setNegativeButton("Закрыть", null).show();
    }

    private void themeSettings() {
        int current = java.util.Arrays.asList(ThemePalette.KEYS).indexOf(store.theme());
        new AlertDialog.Builder(this).setTitle("Тема оформления")
                .setSingleChoiceItems(ThemePalette.LABELS, current, (dialog, choice) -> {
                    dialog.dismiss();
                    if (choice == current) return;
                    store.prefs.edit().putString("theme", ThemePalette.KEYS[choice]).apply();
                    ScheduleEvents.run(this, false);
                    recreate();
                }).setNegativeButton("Закрыть", null).show();
    }

    private void glyphSettings() {
        boolean enabled = store.glyph();
        new AlertDialog.Builder(this).setTitle("Прогресс пары на Glyph")
                .setMessage("Подсветка Nothing Phone (3a) показывает ход текущей пары, пока расписание открыто.")
                .setPositiveButton(enabled ? "Выключить" : "Включить", (dialog, which) -> {
                    store.prefs.edit().putBoolean("glyph", !enabled).apply();
                    updateProgress(Instant.now());
                }).setNegativeButton("Закрыть", null).show();
    }

    private void importSchedule() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*"), IMPORT);
    }

    private void exportSchedule() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "schedule-tiu.json"), EXPORT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT) {
            work(() -> {
                try (var input = getContentResolver().openInputStream(uri)) {
                    return Schedule.parse(Schedule.read(input));
                }
            }, schedule -> save(schedule, ""));
        } else if (request == EXPORT) {
            byte[] snapshot = store.read().json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            work(() -> {
                try (var output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new java.io.IOException("Не удалось открыть файл");
                    output.write(snapshot);
                }
                return true;
            }, saved -> toast("Файл сохранён"));
        }
    }

    private void openLink(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException unavailable) { toast("Браузер не найден"); }
    }

    private <T> void work(java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> result) {
        if (busy) return;
        busy = true;
        render(true);
        executor.execute(() -> {
            try {
                T value = task.call();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    busy = false;
                    render(true);
                    result.accept(value);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    busy = false;
                    render(true);
                    report(failure);
                });
            }
        });
    }

    private void report(Exception error) {
        String details = (error instanceof java.io.IOException || error instanceof IllegalArgumentException)
                ? error.getMessage() : "Проверь формат файла и доступ к расписанию";
        if (details == null || details.contains("://")) details = "Проверь источник и соединение";
        new AlertDialog.Builder(this).setTitle("Не удалось загрузить расписание")
                .setMessage(details).setPositiveButton("Закрыть", null).show();
    }

    private EditText edit(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        return input;
    }

    private LinearLayout wrap(View input) {
        var content = column();
        pad(content, 22, 8, 22, 4);
        content.addView(input);
        return content;
    }

    private LinearLayout box(int color) {
        var result = column();
        result.setBackground(shape(color, 21));
        pad(result, 17, 15, 17, 15);
        return result;
    }

    private LinearLayout column() {
        var layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() { return new LinearLayout(this); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void pad(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    private void add(LinearLayout parent, View child, int margin) {
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private GradientDrawable shape(int color, int radius) {
        var style = new GradientDrawable();
        style.setColor(color);
        style.setCornerRadius(dp(radius));
        return style;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private TextView button(String title, boolean active, Runnable action) {
        var button = label(title, 14, active ? palette.onAccent : palette.text, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        pad(button, 13, 8, 13, 8);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),
                shape(active ? palette.accent : palette.control, 16), null));
        button.setOnClickListener(v -> action.run());
        button.setFocusable(true);
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        return button;
    }

    private static String capitalize(String value) {
        return value.substring(0, 1).toUpperCase(Schedule.RU) + value.substring(1);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
