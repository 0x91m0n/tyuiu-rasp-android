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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;

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
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Schedule.RU);
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
    private String viewedSubgroup;
    private ScheduleStore.SavedGroup otherGroup;
    private String settingsPage = "";
    private int agendaScroll;
    private final OnBackInvokedCallback settingsBack = this::backFromSettings;
    private TextView featuredStatus;
    private ProgressBar featuredProgress;
    private String featuredKey = "";
    private List<Schedule.Group> cachedGroups = List.of();
    private final java.util.Map<String, Long> previewAttempts = new java.util.HashMap<>();
    private String cachedCatalog = "";
    private long catalogFetchedAt;
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private boolean busy;
    private boolean foreground;
    private boolean glyphTesting;
    private String glyphStatus = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            Schedule schedule = currentSchedule();
            Instant now = Instant.now();
            LocalDate today = Schedule.today();
            String key = featuredKey(schedule.next(now, visibleSubgroup(schedule)), now);
            boolean switchDay = followDay && !date.equals(schedule.defaultDay(now, visibleSubgroup(schedule)));
            if (settingsPage.isEmpty() && (switchDay || !today.equals(shownToday) || !key.equals(featuredKey))) render(!switchDay);
            else updateProgress(now);
            handler.postDelayed(this, 5_000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        store = new ScheduleStore(this);
        palette = ThemePalette.resolve(this, store.theme());
        if (palette.light()) setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        getWindow().setDecorFitsSystemWindows(false);
        glyph = new GlyphProgress(this);
        otherGroup = (ScheduleStore.SavedGroup) getLastNonConfigurationInstance();
        date = state == null ? Schedule.today() : LocalDate.parse(state.getString("date", Schedule.today().toString()));
        if (state == null) applyEntry(getIntent());
        else {
            followDay = state.getBoolean("followDay", true);
            week = state.getBoolean("week");
            viewedSubgroup = state.getString("viewedSubgroup");
            settingsPage = state.getString("settingsPage", "");
            agendaScroll = state.getInt("agendaScroll");
            if (!settingsPage.isEmpty()) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, settingsBack);
        }
        preferenceListener = (prefs, key) -> {
            if (("data".equals(key) || "lastSync".equals(key)) && foreground && !busy) render(true);
        };
        store.prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
        RefreshJob.configure(this);
        render(false);
        var insets = getWindow().getInsetsController();
        if (insets != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            insets.setSystemBarsAppearance(palette.light() ? mask : 0, mask);
        }
        if (state == null && "week-alarms".equals(getIntent().getStringExtra(ScheduleEvents.OPEN_VIEW))) {
            WeekAlarmSettings.show(this);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("date", date.toString());
        state.putBoolean("followDay", followDay);
        state.putBoolean("week", week);
        state.putString("viewedSubgroup", viewedSubgroup);
        state.putString("settingsPage", settingsPage);
        state.putInt("agendaScroll", agendaScroll);
    }

    @Override public Object onRetainNonConfigurationInstance() { return otherGroup; }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyEntry(intent);
        if (!settingsPage.isEmpty()) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(settingsBack);
            settingsPage = "";
        }
        render(false);
        if ("week-alarms".equals(intent.getStringExtra(ScheduleEvents.OPEN_VIEW))) WeekAlarmSettings.show(this);
    }

    private void applyEntry(Intent intent) {
        String view = intent.getStringExtra(ScheduleEvents.OPEN_VIEW);
        otherGroup = null;
        week = "week".equals(view);
        followDay = !"tomorrow".equals(view);
        viewedSubgroup = intent.getStringExtra(ScheduleEvents.OPEN_SUBGROUP);
        if (!followDay) date = Schedule.today().plusDays(1);
        String widgetDate = intent.getStringExtra(ScheduleEvents.OPEN_DATE);
        if (widgetDate != null) {
            try {
                LocalDate target = LocalDate.parse(widgetDate);
                if (Math.abs(ChronoUnit.DAYS.between(Schedule.today(), target)) < 370) {
                    date = target;
                    followDay = false;
                }
            } catch (java.time.format.DateTimeParseException ignored) {}
        }
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        ScheduleEvents.run(this, true);
        WeekAlarms.reschedule(this);
        RefreshJob.configure(this);
        RefreshJob.catchUp(this);
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
        if (!settingsPage.isEmpty()) {
            renderSettings(keepScroll);
            return;
        }
        int scrollY = keepScroll && scroll != null ? scroll.getScrollY() : 0;
        renderSchedule(scrollY);
    }

    private void renderSchedule(int scrollY) {
        shownToday = Schedule.today();
        Schedule schedule = currentSchedule();
        String subgroup = visibleSubgroup(schedule);
        Instant now = Instant.now();
        if (followDay) date = schedule.defaultDay(now, subgroup);
        Schedule.Lesson next = schedule.next(now, subgroup);
        featuredKey = featuredKey(next, now);
        featuredStatus = null;
        featuredProgress = null;

        beginPage(scrollY);

        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        var heading = label("Пары ТИУ", 29, palette.text, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("Настройки", false, this::settings));
        page.addView(top);

        String group = schedule.group;
        String groupLabel = group.isEmpty() ? "Выбрать группу" : group;
        if (schedule.demo) groupLabel += " · демо";
        var groupButton = button(groupLabel + "  ▾", false, this::chooseGroup);
        if (!group.isEmpty()) groupButton.setContentDescription("Выбрать группу. Сейчас " + groupLabel);
        var groupRow = row();
        groupRow.addView(groupButton, new LinearLayout.LayoutParams(0, -2, 1));
        ScheduleStore.SavedGroup visible = currentGroup();
        if (!visible.feed().isBlank() && !schedule.demo) {
            boolean starred = store.isFavorite(visible.feed());
            var star = button(starred ? "★" : "☆", starred, () -> {
                store.toggleFavorite(visible);
                render(true);
            });
            star.setTextSize(20);
            star.setContentDescription(starred ? "Убрать из избранного" : "Добавить в избранное");
            var space = new LinearLayout.LayoutParams(-2, -2);
            space.setMarginStart(dp(8));
            groupRow.addView(star, space);
        }
        add(page, groupRow, 12);
        addFavorites(visible.feed());
        var shortcutsPanel = row();
        shortcutsPanel.setBaselineAligned(false);
        shortcutsPanel.addView(button("Важные ссылки", false, () -> openFromSchedule("links")),
                new LinearLayout.LayoutParams(0, -1, 1));
        var vaultWidth = new LinearLayout.LayoutParams(0, -1, 1);
        vaultWidth.setMarginStart(dp(8));
        shortcutsPanel.addView(button("Логин и пароль", false, () ->
                startActivity(new Intent(this, CredentialVaultActivity.class))), vaultWidth);
        add(page, shortcutsPanel, 8);
        if (otherGroup != null) {
            var useGroup = button("Сделать моей группой", false, () -> useAsPersonal(visible));
            add(page, useGroup, 8);
        }
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
            add(welcome, button("Посмотреть демо", false, () -> save(Schedule.example(), "")), 14);
            add(page, welcome, 22);
            return;
        }

        addSubgroupFilter(schedule, subgroup);
        if (next != null) {
            boolean active = !next.starts().isAfter(now);
            var featured = box(palette.accent);
            featuredStatus = label(active ? "СЕЙЧАС ИДЁТ" : "БЛИЖАЙШАЯ ПАРА", 13, palette.onAccent, true);
            DisplayFonts.heading(featuredStatus, palette);
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
        DisplayFonts.heading(dateTitle, palette);
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
                    date = schedule.defaultDay(now, subgroup);
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
        List<Schedule.Lesson> lessons = schedule.day(date, subgroup);
        if (week) {
            for (int offset = 0; offset < 7; offset++) {
                LocalDate target = date.plusDays(offset);
                var dayHeading = label(capitalize(target.format(FULL_DATE)), 18, palette.text, true);
                DisplayFonts.heading(dayHeading, palette);
                add(page, dayHeading, 16);
                addLessons(schedule.day(target, subgroup));
            }
        } else {
            addLessons(lessons);
        }
        if (!schedule.demo && visible.updatedAt() > 0) {
            String updated = Instant.ofEpochMilli(visible.updatedAt()).atZone(ZoneId.systemDefault()).format(UPDATED);
            add(page, label("Обновлено · " + updated, 12, palette.muted, false), 15);
        }
        updateProgress(now);
    }

    private void beginPage(int scrollY) {
        var outer = column();
        outer.setBackgroundColor(palette.background);
        outer.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = column();
        pad(page, 20, 22, 20, 29);
        scroll.addView(page);
        outer.addView(scroll);
        setContentView(outer);
        if (scrollY != 0) {
            ScrollView current = scroll;
            current.post(() -> current.scrollTo(0, scrollY));
        }
    }

    private String visibleSubgroup(Schedule schedule) {
        if (viewedSubgroup != null && (viewedSubgroup.isBlank() || schedule.subgroups().contains(viewedSubgroup))) {
            return viewedSubgroup;
        }
        return otherGroup == null ? store.subgroup() : "";
    }

    private void addSubgroupFilter(Schedule schedule, String selected) {
        List<String> subgroups = schedule.subgroups();
        if (subgroups.isEmpty()) return;
        var viewport = new HorizontalScrollView(this);
        viewport.setHorizontalScrollBarEnabled(false);
        var options = row();
        var values = new java.util.ArrayList<String>();
        values.add("");
        values.addAll(subgroups);
        for (String value : values) {
            boolean active = value.equals(selected);
            var chip = button(value.isEmpty() ? "Все" : value, active, () -> {
                if (value.equals(selected)) return;
                viewedSubgroup = value;
                render(true);
            });
            chip.setSelected(active);
            chip.setContentDescription(value.isEmpty() ? "Все подгруппы" : "Подгруппа " + value);
            var space = new LinearLayout.LayoutParams(-2, -2);
            space.setMarginEnd(dp(7));
            options.addView(chip, space);
        }
        viewport.addView(options);
        add(page, viewport, 9);
    }

    private void addLessons(List<Schedule.Lesson> lessons) {
        if (lessons.isEmpty()) {
            var empty = box(palette.card);
            empty.addView(label("Пар нет", 16, palette.muted, false));
            add(page, empty, 10);
        }
        Instant previousEnd = null;
        for (List<Schedule.Lesson> lessonsAtTime : Schedule.slots(lessons)) {
            Schedule.Lesson first = lessonsAtTime.get(0);
            if (previousEnd != null) {
                long gap = ChronoUnit.MINUTES.between(previousEnd, first.starts());
                if (gap > 0) add(page, label("Окно · " + gap + " мин", 13, palette.muted, false), 10);
            }
            var slot = box(palette.card);
            slot.addView(label(first.time(), 14, palette.accent, true));
            for (Schedule.Lesson lesson : lessonsAtTime) {
                add(slot, label(lesson.title(), 19, palette.text, true), 6);
                if (!lesson.meta().isBlank()) add(slot, label(lesson.meta(), 14, palette.muted, false), 4);
            }
            add(page, slot, 10);
            if (previousEnd == null || first.ends().isAfter(previousEnd)) previousEnd = first.ends();
        }
    }

    private static String featuredKey(Schedule.Lesson next, Instant now) {
        return next == null ? "" : next.key() + next.starts().isAfter(now);
    }

    private void updateProgress(Instant now) {
        Schedule schedule = currentSchedule();
        Schedule.Lesson lesson = schedule.next(now, visibleSubgroup(schedule));
        boolean active = lesson != null && !lesson.starts().isAfter(now);
        int percent = active ? lesson.progress(now) : 0;
        if (featuredProgress != null && featuredStatus != null && active) {
            featuredProgress.setProgress(percent);
            long minutes = Math.max(1, (lesson.ends().getEpochSecond() - now.getEpochSecond() + 59) / 60);
            featuredStatus.setText(getString(R.string.class_remaining, minutes));
        }
        Schedule.Lesson personalLesson = foreground && store.glyph() && !GlyphProgress.tileRunning()
                ? GlyphOptions.activeLesson(store, now) : null;
        glyph.show(personalLesson != null ? personalLesson.progress(now) : -1, GlyphOptions.load(store.prefs));
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

    private Schedule currentSchedule() { return otherGroup == null ? store.read() : otherGroup.schedule(); }

    private ScheduleStore.SavedGroup currentGroup() {
        return otherGroup == null ? new ScheduleStore.SavedGroup(store.feed(), store.read(), store.lastSync()) : otherGroup;
    }

    private void addFavorites(String currentFeed) {
        var favorites = store.favorites();
        if (favorites.isEmpty() && otherGroup == null) return;
        var viewport = new HorizontalScrollView(this);
        viewport.setHorizontalScrollBarEnabled(false);
        var chips = row();
        if (otherGroup != null && favorites.stream().noneMatch(item -> item.feed().equals(store.feed()))) {
            addFavoriteChip(chips, "Моя группа", false, () -> viewGroup(null));
        }
        for (var favorite : favorites) {
            boolean active = favorite.feed().equals(currentFeed);
            addFavoriteChip(chips, favorite.name(), active, () -> {
                if (!active) openGroup(favorite);
            });
        }
        viewport.addView(chips);
        add(page, viewport, 7);
    }

    private void addFavoriteChip(LinearLayout row, String name, boolean selected, Runnable action) {
        var chip = button(name, selected, action);
        chip.setSelected(selected);
        var spacing = new LinearLayout.LayoutParams(-2, -2);
        spacing.setMarginEnd(dp(7));
        row.addView(chip, spacing);
    }

    private void viewGroup(ScheduleStore.SavedGroup group) {
        otherGroup = group;
        viewedSubgroup = null;
        render(false);
    }

    private void openGroup(ScheduleStore.Favorite group) {
        if (busy) return;
        if (group.feed().equals(store.feed())) {
            viewGroup(null);
            return;
        }
        var cached = store.cachedFavorite(group);
        if (cached != null) {
            viewGroup(cached);
            long now = System.currentTimeMillis();
            if (now - cached.updatedAt() < 24 * 3600_000L
                    || now - previewAttempts.getOrDefault(group.feed(), 0L) < 2 * 3600_000L) return;
            previewAttempts.put(group.feed(), now);
            executor.execute(() -> {
                try {
                    var snapshot = loadGroup(group);
                    store.cacheFavorite(snapshot);
                    runOnUiThread(() -> {
                        if (!isFinishing() && !isDestroyed() && otherGroup == cached) {
                            otherGroup = snapshot;
                            render(true);
                        }
                    });
                } catch (Exception unavailable) {
                    // The saved timetable is available when the network is down.
                }
            });
        } else {
            work(() -> loadGroup(group), snapshot -> {
                if (store.read().group.isEmpty() || store.read().demo) useAsPersonal(snapshot);
                else viewGroup(snapshot);
            });
        }
    }

    private static ScheduleStore.SavedGroup loadGroup(ScheduleStore.Favorite group) throws Exception {
        Schedule data = Schedule.load(Schedule.https(group.feed()));
        if (data.demo || !data.group.equals(group.name())) {
            throw new IllegalArgumentException("Источник вернул другое расписание");
        }
        return new ScheduleStore.SavedGroup(group.feed(), data, System.currentTimeMillis());
    }

    private void useAsPersonal(ScheduleStore.SavedGroup group) {
        boolean changed = !store.read().group.equals(group.schedule().group);
        if (save(group) && changed && !group.schedule().subgroups().isEmpty()) subgroup();
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
            openGroup(new ScheduleStore.Favorite(group.name(), group.url().toString()));
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
        if (otherGroup != null) {
            var visible = otherGroup;
            var group = new ScheduleStore.Favorite(visible.schedule().group, visible.feed());
            work(() -> loadGroup(group), snapshot -> {
                store.cacheFavorite(snapshot);
                if (otherGroup == visible) otherGroup = snapshot;
                render(true);
                toast("Расписание обновлено");
            });
            return;
        }
        String url = store.feed();
        if (url.isEmpty()) return;
        work(() -> RefreshJob.refresh(this, url, true, () -> !Thread.currentThread().isInterrupted()), updated -> {
            RefreshJob.configure(this);
            render(true);
            if (updated) toast("Расписание обновлено");
        });
    }

    private void save(Schedule schedule, String feed) {
        save(new ScheduleStore.SavedGroup(feed, schedule, System.currentTimeMillis()));
    }

    private boolean save(ScheduleStore.SavedGroup group) {
        try {
            store.select(group);
            ScheduleEvents.run(this, false);
            RefreshJob.configure(this);
            date = Schedule.today();
            followDay = true;
            week = false;
            viewGroup(null);
            return true;
        } catch (Exception failure) {
            report(failure);
            return false;
        }
    }

    private void settings() {
        openFromSchedule("settings");
    }

    private void openFromSchedule(String section) {
        agendaScroll = scroll.getScrollY();
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(0, settingsBack);
        openSettingsPage(section);
    }

    private void openSettingsPage(String value) {
        settingsPage = value;
        renderSettings(false);
    }

    private void backFromSettings() {
        if (!"settings".equals(settingsPage) && !"links".equals(settingsPage)) {
            boolean stopPreview = "glyph".equals(settingsPage) && glyphTesting;
            openSettingsPage("settings");
            if (stopPreview) glyph.stop();
            return;
        }
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(settingsBack);
        settingsPage = "";
        renderSchedule(agendaScroll);
    }

    // Android 13 can still dispatch a hardware back key through this method.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (settingsPage.isEmpty()) super.onBackPressed();
        else backFromSettings();
    }

    private void renderSettings(boolean keepScroll) {
        beginPage(keepScroll && scroll != null ? scroll.getScrollY() : 0);
        featuredStatus = null;
        featuredProgress = null;
        String heading = switch (settingsPage) {
            case "widgets" -> "Виджеты";
            case "alerts" -> "Уведомления и будильники";
            case "data" -> "Данные расписания";
            case "themes" -> "Оформление";
            case "glyph" -> "Glyph Interface";
            case "links" -> "Важные ссылки";
            default -> "Настройки";
        };
        var top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        var back = button("‹", false, this::backFromSettings);
        back.setContentDescription("Назад");
        top.addView(back);
        var title = label(heading, 26, palette.text, true);
        title.setAccessibilityHeading(true);
        var space = new LinearLayout.LayoutParams(0, -2, 1);
        space.setMarginStart(dp(13));
        top.addView(title, space);
        page.addView(top);

        Schedule schedule = store.read();
        switch (settingsPage) {
            case "links" -> importantLinks();
            case "widgets" -> {
                var controls = settingsCard();
                setting(controls, "Добавить виджет", "Выбор стартового формата", this::pinWidget);
                setting(controls, "Настроить установленные", "У каждого виджета свои параметры", this::configureWidgets);
            }
            case "alerts" -> {
                var controls = settingsCard();
                setting(controls, "Будильники по учебным неделям", "Время подъёма для каждого дня",
                        () -> WeekAlarmSettings.show(this));
                setting(controls, "Напоминания о парах", store.notifications() ? "За " + store.lead() + " мин" : "Выключены",
                        this::reminderSettings);
                Instant now = Instant.now();
                var first = firstClassForAlarm(schedule, now);
                if (first != null && !schedule.demo) {
                    setting(controls, "Разовый будильник к первой паре", first.start().format(Schedule.DATE) + " · " + first.time(),
                            () -> clock(first));
                    setting(controls, "Когда будить", "За " + store.wakeLead() + " мин до пары",
                            () -> minutes("Когда будить", "wakeLead", store.wakeLead(), 360));
                }
            }
            case "themes" -> themeGallery();
            case "glyph" -> glyphControls();
            case "data" -> {
                var controls = settingsCard();
                if (busy) add(page, label("Обновляю расписание…", 14, palette.muted, false), 10);
                var visible = currentGroup();
                if (!visible.feed().isEmpty()) {
                    setting(controls, "Обновить сейчас", visible.schedule().group, this::refresh);
                    if (visible.feed().startsWith(OFFICIAL + "schedule/group/")) {
                        setting(controls, "Эта группа на сайте ТИУ", visible.schedule().group, () -> openLink(visible.feed()));
                    }
                }
                setting(controls, "Каталог групп", "", this::catalogSettings);
                setting(controls, "Открыть файл расписания", "Импорт JSON", this::importSchedule);
                if (!schedule.group.isEmpty() && !schedule.demo) {
                    setting(controls, "Экспорт расписания в JSON", "", this::exportSchedule);
                }
                setting(controls, "Сайт расписания ТИУ", "", () -> openLink(OFFICIAL));
            }
            default -> {
                var personalize = settingsCard();
                setting(personalize, "Виджеты", "Содержимое, оформление и размер текста", () -> openSettingsPage("widgets"));
                int theme = java.util.Arrays.asList(ThemePalette.KEYS).indexOf(store.theme());
                setting(personalize, "Тема оформления", ThemePalette.LABELS[Math.max(theme, 0)], () -> openSettingsPage("themes"));
                if (!schedule.subgroups().isEmpty()) {
                    String subgroup = store.subgroup().isBlank() ? "Вся группа" : "Подгруппа " + store.subgroup();
                    setting(personalize, "Моя подгруппа", subgroup, this::subgroup);
                }
                if (GlyphProgress.supported()) {
                    setting(personalize, "Glyph Interface", "Плитка и режимы подсветки", () -> openSettingsPage("glyph"));
                }
                var tools = settingsCard();
                setting(tools, "Уведомления и будильники", "", () -> openSettingsPage("alerts"));
                setting(tools, "Данные расписания", "", () -> openSettingsPage("data"));
                setting(tools, "О приложении", "", this::about);
            }
        }
    }

    private Schedule.Lesson firstClassForAlarm(Schedule schedule, Instant now) {
        var lessons = schedule.day(date, store.subgroup());
        return lessons.isEmpty() || !lessons.get(0).starts().isAfter(now)
                ? firstUpcomingDay(schedule, now) : lessons.get(0);
    }

    private LinearLayout settingsCard() {
        var panel = column();
        panel.setBackground(shape(palette.card, 21));
        pad(panel, 8, 4, 8, 4);
        add(page, panel, 15);
        return panel;
    }

    private void setting(LinearLayout panel, String name, String detail, Runnable action) {
        var line = row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(56));
        pad(line, 12, 9, 10, 9);
        line.setBackground(new RippleDrawable(ColorStateList.valueOf(0x247f7f7f), null, shape(0xffffffff, 12)));
        var wording = column();
        wording.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        wording.addView(label(name, 16, palette.text, true));
        if (!detail.isBlank()) add(wording, label(detail, 13, palette.muted, false), 3);
        line.addView(wording, new LinearLayout.LayoutParams(0, -2, 1));
        var arrow = label("›", 22, palette.muted, false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(arrow);
        line.setContentDescription(detail.isBlank() ? name : name + ". " + detail);
        line.setFocusable(true);
        line.setOnClickListener(v -> action.run());
        panel.addView(line, new LinearLayout.LayoutParams(-1, -2));
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
                    viewedSubgroup = null;
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
            dialog.setMessage(message).setPositiveButton("Установить в часах", (d, which) -> {
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

    private void about() {
        new AlertDialog.Builder(this).setTitle("О приложении")
                .setMessage("Пары ТИУ " + BuildConfig.VERSION_NAME + "\n"
                        + "Исходный код доступен по лицензии GNU GPL v3.\n\n"
                        + "Предложения и замечания можно отправить @gnomecore.")
                .setPositiveButton("Предложить идею", (dialog, choice) -> openLink("https://t.me/gnomecore"))
                .setNeutralButton("Исходный код", (dialog, choice) ->
                        openLink("https://github.com/0x91m0n/tyuiu-rasp-android"))
                .setNegativeButton("Закрыть", null).show();
    }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (!manager.isRequestPinAppWidgetSupported()) {
            toast("Добавьте виджет через меню домашнего экрана");
            return;
        }
        var types = Widgets.Kind.providers();
        String[] titles = types.stream().map(item -> getString(item.title)).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Виджеты ТИУ").setItems(titles,
                (dialog, choice) -> manager.requestPinAppWidget(new ComponentName(this, types.get(choice).provider), null, null))
                .setNegativeButton("Закрыть", null).show();
    }

    private void configureWidgets() {
        var installed = Widgets.installed(this);
        if (installed.isEmpty()) {
            toast("Добавь виджет на главный экран");
            return;
        }
        String[] names = installed.stream().map(entry -> {
            var options = WidgetOptions.load(this, entry.id(), entry.kind());
            return options.title().isBlank() ? getString(options.kind().title) : options.title();
        }).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("Установленные виджеты").setItems(names, (dialog, which) ->
                startActivity(WidgetConfigurationActivity.intent(this, installed.get(which).id())))
                .setNegativeButton("Закрыть", null).show();
    }

    private void themeGallery() {
        var config = getResources().getConfiguration();
        int columns = config.screenWidthDp >= 340 && config.fontScale <= 1.3f ? 2 : 1;
        for (int index = 0; index < ThemePalette.KEYS.length;) {
            var tiles = row();
            for (int column = 0; column < columns; column++) {
                var space = new LinearLayout.LayoutParams(0, -1, 1);
                if (column != 0) space.setMarginStart(dp(9));
                if (index == ThemePalette.KEYS.length) tiles.addView(new View(this), space);
                else {
                    tiles.addView(themeTile(ThemePalette.KEYS[index], ThemePalette.LABELS[index]), space);
                    index++;
                }
            }
            add(page, tiles, 10);
        }
    }

    private View themeTile(String key, String name) {
        ThemePalette preview = ThemePalette.resolve(this, key);
        boolean selected = key.equals(store.theme());
        var tile = column();
        pad(tile, 13, 12, 13, 12);
        var background = shape(preview.card, 17);
        background.setStroke(dp(selected ? 2 : 1), selected ? preview.accent : preview.control);
        tile.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3d7f7f7f), background, null));
        var sample = label("08:00", 26, preview.accent, true);
        DisplayFonts.time(sample, preview);
        DisplayFonts.heading(sample, preview);
        var title = label(name, 15, preview.text, true);
        tile.addView(sample);
        add(tile, title, 5);
        tile.setContentDescription(name + (selected ? " · выбрана" : ""));
        tile.setSelected(selected);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> {
            if (selected) return;
            store.prefs.edit().putString("theme", key).apply();
            ScheduleEvents.run(this, false);
            recreate();
        });
        return tile;
    }

    private void glyphControls() {
        var controls = settingsCard();
        var options = GlyphOptions.load(store.prefs);
        setting(controls, "Добавить плитку Glyph", "Быстрые настройки Android", () -> GlyphProgress.requestTile(this));
        var effects = GlyphOptions.Effect.values();
        setting(controls, "Режим подсветки", options.effect().label, () -> glyphChoice(
                "Режим подсветки", java.util.Arrays.stream(effects).map(effect -> effect.label).toArray(String[]::new),
                options.effect().ordinal(), index -> store.prefs.edit().putString("glyph.effect", effects[index].name()).apply()));
        String[] brightness = {"10%", "25%", "50%", "75%", "100%"};
        int[] levels = {10, 25, 50, 75, 100};
        setting(controls, "Яркость", options.brightness() + "%", () -> {
            int selected = java.util.Arrays.binarySearch(levels, options.brightness());
            glyphChoice("Яркость", brightness, Math.max(-1, selected),
                    index -> store.prefs.edit().putInt("glyph.brightness", levels[index]).apply());
        });
        if (options.effect() != GlyphOptions.Effect.PULSE && options.effect() != GlyphOptions.Effect.STEADY) {
            setting(controls, "Направление индикатора", options.reverse() ? "Обратное" : "Обычное",
                    () -> glyphChoice("Направление индикатора", new String[]{"Обычное", "Обратное"},
                            options.reverse() ? 1 : 0,
                            index -> store.prefs.edit().putBoolean("glyph.reverse", index == 1).apply()));
        }
        var timers = GlyphOptions.Cutoff.values();
        setting(controls, "Таймер плитки", options.cutoff().label, () -> glyphChoice(
                "Таймер плитки", java.util.Arrays.stream(timers).map(timer -> timer.label).toArray(String[]::new),
                options.cutoff().ordinal(), index -> store.prefs.edit().putString("glyph.cutoff", timers[index].name()).apply()));
        add(page, label("Плитка запускает подсветку текущей пары и показывает кнопку остановки в уведомлении. "
                + "Подсветка выключается по таймеру или после пары.", 14, palette.muted, false), 12);
        var extra = settingsCard();
        boolean enabled = store.glyph();
        setting(extra, "Включать в приложении", enabled ? "Автоматически во время пары" : "Выключено", () -> {
            store.prefs.edit().putBoolean("glyph", !enabled).apply();
            renderSettings(true);
            updateProgress(Instant.now());
        });
        setting(extra, "Проверить подсветку", glyphTesting ? "Идёт проверка…" : "На три секунды", () -> {
            if (glyphTesting || GlyphProgress.tileRunning()) {
                if (GlyphProgress.tileRunning()) toast("Сначала останови Glyph в шторке");
                return;
            }
            glyphTesting = true;
            glyphStatus = "";
            renderSettings(true);
            glyph.preview(result -> {
                glyphTesting = false;
                glyphStatus = switch (result) {
                    case LIT -> "Проверка завершена. Подсветка получила команду.";
                    case NO_SERVICE -> "Служба Glyph не ответила. Проверь, включён ли Glyph Interface в настройках телефона.";
                    case REJECTED -> "Nothing OS отклонила доступ к Glyph. Проверь настройки интерфейса и версию Nothing OS.";
                    case ERROR -> "Не удалось запустить подсветку. Попробуй проверить ещё раз.";
                    case CANCELLED -> "";
                };
                if (foreground && "glyph".equals(settingsPage)) renderSettings(true);
            });
        });
        setting(extra, "Запустить в окне", "Если Nothing OS отклоняет фоновый сеанс", () -> GlyphProgress.openWindow(this));
        setting(extra, "Экран при запуске в окне", options.keepScreenOn() ? "Не гасить" : "По настройке Android", () -> {
            store.prefs.edit().putBoolean("glyph.keepScreen", !options.keepScreenOn()).apply();
            renderSettings(true);
        });
        if (!glyphStatus.isEmpty()) {
            var status = label(glyphStatus, 14, palette.text, false);
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            add(page, status, 10);
        }
    }

    private void glyphChoice(String title, String[] labels, int selected, java.util.function.IntConsumer update) {
        new AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, selected, (dialog, index) -> {
            update.accept(index);
            dialog.dismiss();
            renderSettings(true);
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

    private void importantLinks() {
        String feed = currentGroup().feed();
        String timetable = feed.startsWith(OFFICIAL + "schedule/group/") ? feed : OFFICIAL;
        var links = settingsCard();
        setting(links, "Эдукон", "Учебные материалы и задания", () -> openLink("https://educon2.tyuiu.ru/"));
        setting(links, "Мой ТИУ", "Личный кабинет", () -> openLink("https://my.tyuiu.ru/"));
        setting(links, "Расписание ТИУ", "", () -> openLink(timetable));
        setting(links, "Электронная библиотека", "", () -> openLink("https://jirbis.tyuiu.ru/"));
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
        if (size >= 26) DisplayFonts.heading(label, palette);
        if (palette == ThemePalette.MONO && value.matches("[0-9]{2}:[0-9]{2}(–[0-9]{2}:[0-9]{2})?")) {
            DisplayFonts.time(label, palette);
        }
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
