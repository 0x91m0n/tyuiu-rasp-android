package ru.tyuiu.rasp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;

final class WeekAlarmSettings {
    static final int NOTIFICATION_PERMISSION = 207;
    private WeekAlarmSettings() {}

    static void show(Activity activity) {
        var prefs = WeekAlarms.prefs(activity);
        boolean enabled = prefs.getBoolean("enabled", false);
        boolean exact = ScheduleEvents.exact(activity);
        boolean notifications = WeekAlarms.notifications(activity);
        var zone = ZoneId.systemDefault();
        Instant next = WeekAlarms.next(prefs, Instant.now(), zone);
        String status = !enabled ? "Профили выключены."
                : !exact || !notifications ? "Для звонка разреши точные будильники и уведомления."
                : next == null ? "Выбери хотя бы один день."
                : "Следующий звонок · " + next.atZone(zone).format(Schedule.DATE)
                + " в " + next.atZone(zone).format(Schedule.TIME);
        var names = new ArrayList<String>();
        var actions = new ArrayList<Runnable>();
        names.add(enabled ? "Выключить профили" : "Включить профили");
        actions.add(() -> {
            prefs.edit().putBoolean("enabled", !enabled).apply();
            WeekAlarms.reschedule(activity);
            if (enabled) activity.stopService(new Intent(activity, WeekAlarmService.class));
            show(activity);
        });
        names.add("Нечётная неделя");
        actions.add(() -> days(activity, false));
        names.add("Чётная неделя");
        actions.add(() -> days(activity, true));
        if (!exact) {
            names.add("Разрешить точные будильники");
            actions.add(() -> activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + activity.getPackageName()))));
        }
        if (!notifications) {
            names.add("Разрешить уведомления");
            actions.add(() -> {
                if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_DENIED
                        && !prefs.getBoolean("notifications_requested", false)) {
                    prefs.edit().putBoolean("notifications_requested", true).apply();
                    activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
                } else {
                    Intent settings = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                    activity.startActivity(settings);
                }
            });
        }
        var header = new android.widget.LinearLayout(activity);
        header.setOrientation(android.widget.LinearLayout.VERTICAL);
        int inset = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        header.setPadding(inset, inset, inset, inset / 2);
        var title = new android.widget.TextView(activity);
        title.setText("Будильники по неделям");
        title.setTextAppearance(android.R.style.TextAppearance_Material_Title);
        header.addView(title);
        var statusView = new android.widget.TextView(activity);
        statusView.setPadding(0, inset / 3, 0, 0);
        statusView.setText(status + "\nВремя телефона · чётность по календарю ТИУ");
        statusView.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
        header.addView(statusView);
        new AlertDialog.Builder(activity)
                .setCustomTitle(header)
                .setItems(names.toArray(new String[0]), (view, choice) -> actions.get(choice).run())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private static void days(Activity activity, boolean even) {
        var prefs = WeekAlarms.prefs(activity);
        DayOfWeek[] days = DayOfWeek.values();
        String[] items = new String[days.length];
        for (int index = 0; index < days.length; index++) {
            DayOfWeek day = days[index];
            int time = prefs.getInt(WeekAlarms.key(even, day), -1);
            String name = day.getDisplayName(TextStyle.FULL_STANDALONE, Schedule.RU);
            items[index] = name.substring(0, 1).toUpperCase(Schedule.RU) + name.substring(1) + "  ·  "
                    + (time == -1 ? "выкл." : LocalTime.of(time / 60, time % 60).format(Schedule.TIME));
        }
        new AlertDialog.Builder(activity).setTitle(even ? "Чётная неделя" : "Нечётная неделя")
                .setItems(items, (dialog, index) -> edit(activity, even, days[index]))
                .setNegativeButton("Назад", (dialog, choice) -> show(activity)).show();
    }

    private static void edit(Activity activity, boolean even, DayOfWeek day) {
        var prefs = WeekAlarms.prefs(activity);
        String key = WeekAlarms.key(even, day);
        int value = prefs.getInt(key, 420);
        TimePickerDialog picker = new TimePickerDialog(activity, (view, hour, minute) -> {
            prefs.edit().putInt(key, hour * 60 + minute).apply();
            WeekAlarms.reschedule(activity);
            days(activity, even);
        }, value / 60, value % 60, true);
        picker.setButton(AlertDialog.BUTTON_NEUTRAL, "Выключить день", (dialog, choice) -> {
            prefs.edit().remove(key).apply();
            WeekAlarms.reschedule(activity);
            days(activity, even);
        });
        picker.setButton(AlertDialog.BUTTON_NEGATIVE, "Назад", (dialog, choice) -> days(activity, even));
        picker.show();
    }
}
