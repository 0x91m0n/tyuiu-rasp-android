package ru.tyuiu.rasp;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.PersistableBundle;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.function.BooleanSupplier;

public final class RefreshJob extends JobService {
    private static final int OLD_HOURLY_JOB = 61;
    private static final int JOB = 62;
    private static final String DUE = "due";
    private static final Duration DAY = Duration.ofHours(24);
    private static final long RETRY_MS = 2 * 3600_000L;
    private static final LocalTime NIGHT = LocalTime.of(3, 0);
    private static final Object FETCH_LOCK = new Object();
    private static String attemptedFeed = "";
    private static long attemptedAt;
    private static boolean catchUpRunning;
    private volatile JobParameters running;

    static Instant nextUpdate(long updatedAt, Instant now) {
        if (updatedAt <= 0 || updatedAt > now.toEpochMilli()) return now;
        Instant latest = Instant.ofEpochMilli(updatedAt);
        Instant deadline = latest.plus(DAY);
        var local = latest.atZone(Schedule.ZONE);
        Instant night = local.toLocalDate().atTime(NIGHT).atZone(Schedule.ZONE).toInstant();
        if (!night.isAfter(latest)) night = night.plus(DAY);
        return night.isBefore(deadline) && !night.isBefore(latest.plus(Duration.ofHours(4))) ? night : deadline;
    }

    static synchronized void configure(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(OLD_HOURLY_JOB) != null) scheduler.cancel(OLD_HOURLY_JOB);
        var store = new ScheduleStore(context);
        if (store.feed().isEmpty()) {
            scheduler.cancel(JOB);
            return;
        }
        Instant now = Instant.now();
        long updatedAt = store.lastSync();
        long due = updatedAt <= 0 || updatedAt > now.toEpochMilli() ? 0 : nextUpdate(updatedAt, now).toEpochMilli();
        var pending = scheduler.getPendingJob(JOB);
        if (pending != null && pending.getExtras().getLong(DUE) == due) return;
        var extras = new PersistableBundle();
        extras.putLong(DUE, due);
        scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, RefreshJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(Math.max(0, due - now.toEpochMilli()))
                .setBackoffCriteria(RETRY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).setExtras(extras).build());
    }

    static boolean refresh(Context context, String feed, boolean manual, BooleanSupplier active) throws Exception {
        if (feed.isEmpty()) return false;
        synchronized (FETCH_LOCK) {
            var store = new ScheduleStore(context);
            Instant now = Instant.now();
            long version = store.lastSync();
            if (!active.getAsBoolean() || !feed.equals(store.feed())) return false;
            if (!manual) {
                if (now.isBefore(nextUpdate(version, now))) return false;
                long elapsed = android.os.SystemClock.elapsedRealtime();
                if (feed.equals(attemptedFeed) && elapsed - attemptedAt < RETRY_MS) {
                    throw new IOException("Повтор обновления отложен");
                }
                attemptedFeed = feed;
                attemptedAt = elapsed;
            }
            Schedule data = Schedule.load(Schedule.https(feed));
            if (!active.getAsBoolean() || !feed.equals(store.feed()) || version != store.lastSync()) return false;
            if (!store.refresh(feed, version, data)) throw new IOException("Источник вернул другое расписание");
            attemptedFeed = "";
            ScheduleEvents.run(context, false);
            return true;
        }
    }

    static synchronized void catchUp(Context context) {
        var store = new ScheduleStore(context);
        String feed = store.feed();
        long updatedAt = store.lastSync();
        long now = System.currentTimeMillis();
        if (catchUpRunning || feed.isEmpty() || (updatedAt > 0 && updatedAt <= now
                && now - updatedAt < DAY.toMillis())) return;
        if (context.getSystemService(ConnectivityManager.class).getActiveNetwork() == null) return;
        catchUpRunning = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (refresh(app, feed, false, () -> true)) configure(app);
            } catch (Exception ignored) {
                // The scheduled job retries when Android and the network are available.
            } finally {
                synchronized (RefreshJob.class) { catchUpRunning = false; }
            }
        }, "schedule-catch-up").start();
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (params.getJobId() != JOB) return false;
        running = params;
        new Thread(() -> {
            boolean retry = false;
            try {
                refresh(this, new ScheduleStore(this).feed(), false, () -> running == params);
            } catch (Exception failure) {
                retry = true;
            }
            boolean reschedule = retry;
            getMainExecutor().execute(() -> {
                if (running == params) {
                    running = null;
                    jobFinished(params, reschedule);
                    if (!reschedule) configure(this);
                }
            });
        }, "schedule-refresh").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (running == params) running = null;
        return true;
    }
}
