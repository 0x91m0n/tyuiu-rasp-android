package ru.tyuiu.rasp;

import android.Manifest;
import android.app.Activity;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;

public final class WeekAlarmSettings extends Activity {
    private static final int NOTIFICATION_PERMISSION = 207;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM", Schedule.RU);
    private SharedPreferences prefs;
    private ThemePalette palette;
    private boolean even;
    private ScrollView scroll;

    static void show(Activity activity) {
        activity.startActivity(new Intent(activity, WeekAlarmSettings.class));
    }

    @Override protected void onCreate(Bundle state) {
        palette = ThemePalette.resolve(this, new ScheduleStore(this).theme());
        if (palette.light()) setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        prefs = WeekAlarms.prefs(this);
        even = state != null ? state.getBoolean("even") : OfficialTiu.evenWeek(LocalDate.now());
        getWindow().setDecorFitsSystemWindows(false);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("even", even);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] result) {
        super.onRequestPermissionsResult(requestCode, permissions, result);
        if (requestCode == NOTIFICATION_PERMISSION) refresh();
    }

    private void refresh() {
        WeekAlarms.reschedule(this);
        render();
    }

    private void render() {
        int y = scroll != null ? scroll.getScrollY() : 0;
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(palette.background);
        scroll.setFillViewport(true);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            var edge = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(edge.left, edge.top, edge.right, edge.bottom);
            return insets;
        });
        var page = column();
        page.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(page);
        setContentView(scroll);
        var bars = getWindow().getInsetsController();
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        if (bars != null) bars.setSystemBarsAppearance(palette.light() ? mask : 0, mask);
        if (y != 0) {
            ScrollView current = scroll;
            current.post(() -> current.scrollTo(0, y));
        }

        var top = row();
        var back = action("‹", false, this::finish);
        back.setContentDescription("Назад");
        top.addView(back);
        var title = label("Будильники", 27, palette.text, true);
        DisplayFonts.heading(title, palette);
        title.setAccessibilityHeading(true);
        var titleSpace = new LinearLayout.LayoutParams(0, -2, 1);
        titleSpace.setMarginStart(dp(12));
        top.addView(title, titleSpace);
        add(page, top, 0);

        boolean enabled = prefs.getBoolean("enabled", false);
        boolean exact = ScheduleEvents.exact(this);
        boolean notifications = WeekAlarms.notifications(this);
        var zone = ZoneId.systemDefault();
        Instant next = WeekAlarms.next(prefs, Instant.now(), zone);
        var summary = panel();
        var master = row();
        master.addView(label("Повторять по учебным неделям", 16, palette.text, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        var switcher = toggle("Повторять по учебным неделям", enabled);
        switcher.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean("enabled", checked).apply();
            if (!checked) stopService(new Intent(this, WeekAlarmService.class));
            refresh();
        });
        master.addView(switcher);
        add(summary, master, 0);
        if (!enabled || next == null || !exact || !notifications) {
            String status = !enabled ? "Будильники на паузе" : !exact || !notifications
                    ? "Нужно разрешение Android" : "Задай время хотя бы для одного дня";
            add(summary, label(status, 14, palette.muted, false), 7);
        } else {
            var local = next.atZone(zone);
            add(summary, label("Следующий будильник", 13, palette.muted, false), 9);
            var time = label(local.format(Schedule.TIME), 37, palette.accent, true);
            DisplayFonts.time(time, palette);
            add(summary, time, 2);
            String date = local.format(DATE);
            add(summary, label(capitalize(date), 15, palette.text, false), 1);
        }
        if (!exact) add(summary, action("Разрешить точные будильники", false, () ->
                launch(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())))), 9);
        if (!notifications) add(summary, action("Разрешить уведомления", false, this::requestNotifications), 7);
        add(page, summary, 14);

        var tabs = row();
        weekTab(tabs, false, "Нечётная");
        weekTab(tabs, true, "Чётная");
        add(page, tabs, 16);
        add(page, label("Время телефона · чётность недели по ТИУ", 13, palette.muted, false), 8);
        var days = panel();
        for (DayOfWeek day : DayOfWeek.values()) add(days, dayRow(day), 0);
        add(page, days, 11);
    }

    private void weekTab(LinearLayout tabs, boolean value, String name) {
        var tab = action(name, even == value, () -> {
            if (even == value) return;
            even = value;
            render();
        });
        var size = new LinearLayout.LayoutParams(0, -2, 1);
        if (value) size.setMarginStart(dp(7));
        tabs.addView(tab, size);
    }

    private View dayRow(DayOfWeek day) {
        String name = capitalize(day.getDisplayName(TextStyle.FULL_STANDALONE, Schedule.RU));
        int minute = WeekAlarms.dayTime(prefs, even, day);
        boolean on = WeekAlarms.dayEnabled(prefs, even, day);
        var dayRow = row();
        dayRow.setMinimumHeight(dp(52));
        dayRow.addView(label(name, 15, on ? palette.text : palette.muted, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        String hours = minute >= 0 ? LocalTime.of(minute / 60, minute % 60).format(Schedule.TIME) : "Задать";
        var time = action(hours, false, () -> edit(day, minute));
        time.setContentDescription(name + " · " + (minute >= 0 ? hours : "задать время"));
        if (minute >= 0) DisplayFonts.time(time, palette);
        dayRow.addView(time);
        var toggle = toggle(name + " · будильник", on);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked && minute < 0) {
                render();
                edit(day, minute);
            } else {
                WeekAlarms.saveDay(prefs, even, day, minute, checked);
                refresh();
            }
        });
        dayRow.addView(toggle);
        return dayRow;
    }

    private void edit(DayOfWeek day, int saved) {
        int minute = saved >= 0 ? saved : 420;
        boolean profile = even;
        var picker = new TimePickerDialog(this, (view, hour, mins) -> {
            WeekAlarms.saveDay(prefs, profile, day, hour * 60 + mins, true);
            refresh();
        }, minute / 60, minute % 60, true);
        picker.setTitle(capitalize(day.getDisplayName(TextStyle.FULL_STANDALONE, Schedule.RU)));
        picker.show();
        picker.getButton(TimePickerDialog.BUTTON_POSITIVE).setText("Сохранить");
        picker.getButton(TimePickerDialog.BUTTON_NEGATIVE).setText("Отмена");
    }

    private void requestNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED
                && !prefs.getBoolean("notifications_requested", false)) {
            prefs.edit().putBoolean("notifications_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            return;
        }
        launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    private void launch(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException missing) {
            android.widget.Toast.makeText(this, "Открой разрешения в настройках Android", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private Switch toggle(String name, boolean checked) {
        var value = new Switch(this);
        value.setContentDescription(name);
        value.setChecked(checked);
        value.setPadding(dp(10), 0, 0, 0);
        value.setMinimumHeight(dp(48));
        value.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{palette.accent, palette.muted}));
        return value;
    }

    private TextView action(String name, boolean selected, Runnable click) {
        var button = label(name, 14, selected ? palette.onAccent : palette.text, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(shape(selected ? palette.accent : palette.control));
        button.setPadding(dp(10), dp(7), dp(10), dp(7));
        button.setMinimumHeight(dp(44));
        button.setSelected(selected);
        button.setFocusable(true);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private LinearLayout panel() {
        var card = column();
        card.setBackground(shape(palette.card));
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        return card;
    }

    private GradientDrawable shape(int color) {
        var drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private LinearLayout column() {
        var result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout row() {
        var result = new LinearLayout(this);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        var view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void add(LinearLayout parent, View child, int margin) {
        var size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(margin);
        parent.addView(child, size);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String capitalize(String name) {
        return name.substring(0, 1).toUpperCase(Schedule.RU) + name.substring(1);
    }
}
