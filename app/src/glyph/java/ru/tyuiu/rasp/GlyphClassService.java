package ru.tyuiu.rasp;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public final class GlyphClassService extends Service {
    private static final String STOP = "ru.tyuiu.rasp.STOP_CLASS_GLYPH";
    private static final String CHANNEL = "class-glyph";
    private static final int NOTICE = 91;
    private static boolean running;
    private static String tileMessage = "Включить";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::update;
    private GlyphProgress glyph;
    private GlyphOptions options;
    private Schedule.Lesson lesson;
    private Instant deadline;
    private PowerManager.WakeLock cpu;
    private boolean foreground;

    static Intent intent(Context context) { return new Intent(context, GlyphClassService.class); }
    static boolean running() { return running; }
    static String tileMessage() { return tileMessage; }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction()) || Build.VERSION.SDK_INT < 34) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (foreground) return START_NOT_STICKY;
        var store = new ScheduleStore(this);
        options = GlyphOptions.load(store.prefs);
        Instant now = Instant.now();
        lesson = GlyphOptions.activeLesson(store, now);
        deadline = lesson == null ? now : options.stopAt(now, lesson.ends());
        try {
            startForeground(NOTICE, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            foreground = true;
        } catch (IllegalStateException | SecurityException blocked) {
            unavailable("Android не разрешил запуск Glyph");
            return START_NOT_STICKY;
        }
        if (!GlyphProgress.supported()) {
            unavailable("Glyph недоступен на этом телефоне");
        } else if (lesson == null) {
            unavailable("Сейчас нет пары");
        } else {
            running = true;
            tileMessage = "Включить";
            refreshTile();
            cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PairsTIU:Glyph");
            cpu.acquire(Math.max(1, Duration.between(now, deadline).toMillis()) + 10_000);
            scheduleStop();
            glyph = new GlyphProgress(this);
            glyph.observeProgress(status -> {
                if (status == GlyphCheck.REJECTED) {
                    unavailable("Nothing OS отклонила фоновый Glyph");
                } else if (status == GlyphCheck.ERROR || status == GlyphCheck.NO_SERVICE) {
                    unavailable("Не удалось включить Glyph");
                }
            });
            update();
        }
        return START_NOT_STICKY;
    }

    private Notification notice() {
        var manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Подсветка Glyph",
                NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_glyph_tile)
                .setContentTitle("Glyph пары").setContentText(getString(R.string.glyph_class_until,
                        deadline.atZone(ZoneId.systemDefault()).format(Schedule.TIME)))
                .setOngoing(true).setShowWhen(false).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(new Notification.Action.Builder(null, "Остановить", stopSignal()).build()).build();
    }

    private PendingIntent stopSignal() {
        return PendingIntent.getService(this, NOTICE, intent(this).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleStop() {
        var alarms = getSystemService(AlarmManager.class);
        long stopAt = deadline.toEpochMilli();
        if (alarms.canScheduleExactAlarms()) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, stopSignal());
                return;
            } catch (SecurityException revoked) {
                // Android can revoke exact alarm access during the session.
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAt, stopSignal());
    }

    private void update() {
        Instant now = Instant.now();
        if (!now.isBefore(deadline)) {
            stopSelf();
            return;
        }
        glyph.show(lesson.progress(now), options);
        if (!running) return;
        long remaining = Duration.between(now, deadline).toMillis();
        handler.postDelayed(tick, Math.max(1, Math.min(10_000, remaining)));
    }

    private void unavailable(String message) {
        running = false;
        tileMessage = message;
        refreshTile();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, GlyphTile.class));
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(tick);
        if (glyph != null) glyph.stop();
        if (cpu != null && cpu.isHeld()) cpu.release();
        if (foreground) {
            getSystemService(AlarmManager.class).cancel(stopSignal());
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        refreshTile();
        super.onDestroy();
    }
}
