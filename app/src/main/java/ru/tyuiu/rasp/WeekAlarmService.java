package ru.tyuiu.rasp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class WeekAlarmService extends Service {
    private static final String STOP = "ru.tyuiu.rasp.STOP_WEEK_ALARM";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Ringtone ringtone;
    private ToneGenerator tone;
    private final Runnable beep = new Runnable() {
        @Override public void run() {
            if (tone == null) return;
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 550);
            handler.postDelayed(this, 900);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction()) || !WeekAlarms.notifications(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        var manager = getSystemService(NotificationManager.class);
        var channel = new NotificationChannel(WeekAlarms.CHANNEL, "Будильники по учебным неделям",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
        var stop = PendingIntent.getService(this, 74, new Intent(this, WeekAlarmService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        startForeground(75, new Notification.Builder(this, WeekAlarms.CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Будильник · Пары ТИУ")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(ScheduleEvents.open(this, "week-alarms"))
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .setOngoing(true)
                .build());
        if (ringtone == null && tone == null) play();
        handler.postDelayed(this::stopSelf, 5 * 60_000L);
        return START_NOT_STICKY;
    }

    private void play() {
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                ringtone.setLooping(true);
                ringtone.play();
                return;
            }
        } catch (RuntimeException unavailable) {
            ringtone = null;
        }
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            handler.post(beep);
        } catch (RuntimeException unavailable) {
            stopSelf();
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (ringtone != null) ringtone.stop();
        if (tone != null) tone.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
