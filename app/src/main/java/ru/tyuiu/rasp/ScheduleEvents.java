package ru.tyuiu.rasp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;

public final class ScheduleEvents extends BroadcastReceiver {
    private static final String TICK = "ru.tyuiu.rasp.TICK";
    private static final String CHANNEL = "classes";
    static final String OPEN_VIEW = "view";
    static final String OPEN_DATE = "widget_date";
    static final String OPEN_SUBGROUP = "widget_subgroup";

    @Override
    public void onReceive(Context context, Intent intent) {
        run(context, true);
        if (!TICK.equals(intent.getAction())) RefreshJob.configure(context);
    }

    static PendingIntent open(Context context) {
        return open(context, "");
    }

    static PendingIntent open(Context context, String view) {
        int request = switch (view) {
            case "tomorrow" -> 1;
            case "week" -> 2;
            case "week-alarms" -> 3;
            default -> 0;
        };
        return PendingIntent.getActivity(context, request, new Intent(context, MainActivity.class)
                .putExtra(OPEN_VIEW, view).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openWidget(Context context, int widget, String view, LocalDate date, String subgroup) {
        return PendingIntent.getActivity(context, widget, new Intent(context, MainActivity.class)
                .setAction("ru.tyuiu.rasp.OPEN_WIDGET")
                .putExtra(OPEN_VIEW, view).putExtra(OPEN_DATE, date.toString()).putExtra(OPEN_SUBGROUP, subgroup)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent openWidgetDay(Context context, int widget, String subgroup) {
        var intent = new Intent(context, MainActivity.class).setAction("ru.tyuiu.rasp.OPEN_WIDGET_DAY")
                .putExtra(OPEN_SUBGROUP, subgroup)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, widget, intent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static boolean exact(Context context) {
        return context.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static boolean notificationsAvailable(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static synchronized void run(Context context, boolean sendDue) {
        var store = new ScheduleStore(context);
        Schedule schedule = store.read();
        Instant now = Instant.now();
        long timestamp = now.toEpochMilli();
        Widgets.Active widgets = Widgets.updateAll(context, store, schedule, now);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent callback = PendingIntent.getBroadcast(context, 41,
                new Intent(context, ScheduleEvents.class).setAction(TICK), PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(callback);
        var today = now.atZone(Schedule.ZONE).toLocalDate();
        long next = widgets.any() ? today.plusDays(1).atStartOfDay(Schedule.ZONE)
                .toInstant().toEpochMilli() : Long.MAX_VALUE;
        for (String subgroup : widgets.rolling()) {
            Instant last = schedule.lastEnd(today, subgroup);
            if (last != null && last.plusSeconds(7200).isAfter(now)) {
                next = Math.min(next, last.plusSeconds(7200).toEpochMilli());
            }
        }
        boolean reminders = store.notifications() && !schedule.demo && notificationsAvailable(context);
        var sent = new HashSet<>(store.prefs.getStringSet("sent", java.util.Set.of()));
        var keep = new HashSet<String>();
        boolean fresh = store.feed().isEmpty() || timestamp - store.lastSync() <= 48 * 3600_000L;
        for (Schedule.Lesson lesson : schedule.lessons) {
            if (!lesson.ends().isAfter(now)) continue;
            if (widgets.tracks(lesson)) {
                if (lesson.starts().isAfter(now)) next = Math.min(next, lesson.starts().toEpochMilli());
                else if (widgets.tracksProgress(lesson)) next = Math.min(next, timestamp + 5 * 60_000L);
                next = Math.min(next, lesson.ends().toEpochMilli());
            }
            if (!Schedule.matches(lesson, store.subgroup())) continue;
            String key = lesson.key();
            if (sent.contains(key)) keep.add(key);
            if (!reminders || !fresh || sent.contains(key) || !lesson.starts().isAfter(now)) continue;
            long due = lesson.starts().minus(store.lead(), ChronoUnit.MINUTES).toEpochMilli();
            if (due > timestamp) {
                next = Math.min(next, due);
            } else if (timestamp - due <= 10 * 60_000L) {
                if (sendDue) {
                    post(context, lesson);
                    keep.add(key);
                    sent.add(key);
                } else {
                    next = Math.min(next, timestamp + 1000);
                }
            }
        }
        if (!sent.isEmpty() || !keep.isEmpty()) store.prefs.edit().putStringSet("sent", keep).apply();
        if (next == Long.MAX_VALUE) return;
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, callback);
                return;
            } catch (SecurityException revoked) {
                // The user changed alarm access while this update was running.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, callback);
    }

    private static void post(Context context, Schedule.Lesson lesson) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Напоминания о парах",
                NotificationManager.IMPORTANCE_HIGH));
        String details = lesson.time() + (lesson.meta().isEmpty() ? "" : " · " + lesson.meta());
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(lesson.title())
                .setContentText(details)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setContentIntent(open(context))
                .setShowWhen(false)
                .setAutoCancel(true)
                .setTimeoutAfter(Math.max(1000, lesson.ends().toEpochMilli() - System.currentTimeMillis()))
                .build();
        manager.notify(lesson.key(), 1, notice);
    }
}
