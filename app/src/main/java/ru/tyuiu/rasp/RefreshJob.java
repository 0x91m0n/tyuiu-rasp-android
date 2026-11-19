package ru.tyuiu.rasp;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class RefreshJob extends JobService {
    private static final int JOB = 61;
    private volatile JobParameters running;

    static void configure(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (new ScheduleStore(context).feed().isEmpty()) {
            scheduler.cancel(JOB);
        } else if (scheduler.getPendingJob(JOB) == null) {
            scheduler.schedule(new JobInfo.Builder(JOB, new ComponentName(context, RefreshJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(60 * 60_000L)
                    .setPersisted(true).build());
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        running = params;
        new Thread(() -> {
            boolean retry = false;
            var store = new ScheduleStore(this);
            String feed = store.feed();
            if (!feed.isEmpty()) {
                try {
                    Schedule schedule = Schedule.load(Schedule.https(feed));
                    if (running == params && store.refresh(feed, schedule)) ScheduleEvents.run(this, false);
                } catch (Exception failure) {
                    retry = true;
                }
            }
            boolean reschedule = retry;
            getMainExecutor().execute(() -> {
                if (running == params) {
                    running = null;
                    jobFinished(params, reschedule);
                }
            });
        }, "schedule-refresh").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        running = null;
        return true;
    }
}
