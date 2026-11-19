package ru.tyuiu.rasp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.Instant;

public final class WeekAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean ring = false;
        if (WeekAlarms.ACTION_RING.equals(intent.getAction())) {
            long scheduled = intent.getLongExtra(WeekAlarms.EXTRA_TIME, 0);
            ring = WeekAlarms.fire(context, scheduled, Instant.now());
        }
        WeekAlarms.reschedule(context);
        if (ring) context.startForegroundService(new Intent(context, WeekAlarmService.class));
    }
}
