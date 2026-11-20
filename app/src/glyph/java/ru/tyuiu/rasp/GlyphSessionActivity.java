package ru.tyuiu.rasp;

import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

public final class GlyphSessionActivity extends Activity {
    private static WeakReference<GlyphSessionActivity> active = new WeakReference<>(null);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::update;
    private GlyphProgress glyph;
    private GlyphOptions options;
    private Schedule.Lesson lesson;
    private Instant deadline;
    private TextView status;
    private boolean ready;

    static boolean isRunning() { return active.get() != null; }

    static boolean stopRunning() {
        GlyphSessionActivity current = active.get();
        if (current == null) return false;
        current.finish();
        return true;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        var store = new ScheduleStore(this);
        options = GlyphOptions.load(store.prefs);
        glyph = new GlyphProgress(this);
        Instant now = Instant.now();
        lesson = GlyphOptions.activeLesson(store, now);
        if (lesson != null && state != null && !lesson.key().equals(state.getString("lesson"))) lesson = null;
        if (lesson != null) {
            deadline = state == null ? options.stopAt(now, lesson.ends())
                    : Instant.ofEpochMilli(state.getLong("deadline"));
            if (options.keepScreenOn()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        showPanel();
        glyph.observeProgress(result -> {
            if (result == GlyphCheck.LIT) {
                String until = deadline.atZone(ZoneId.systemDefault()).format(Schedule.TIME);
                status.setText(getString(R.string.glyph_session_until, options.effect().label, until));
                return;
            }
            stop();
            status.setText(switch (result) {
                case NO_SERVICE -> "Служба Glyph не ответила";
                case REJECTED -> "Nothing OS отклонила доступ к подсветке";
                default -> "Подсветка остановлена";
            });
        });
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && lesson != null && GlyphProgress.supported() && !ready && !isFinishing()) {
            ready = true;
            active = new WeakReference<>(this);
            refreshTile();
            update();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (lesson != null) {
            state.putString("lesson", lesson.key());
            state.putLong("deadline", deadline.toEpochMilli());
        }
    }

    @Override protected void onPause() {
        boolean hadStarted = active.get() == this;
        stop();
        if (hadStarted && !isChangingConfigurations()) finish();
        super.onPause();
    }

    @Override public void finish() {
        stop();
        super.finish();
    }

    private void stop() {
        handler.removeCallbacks(tick);
        if (glyph != null) glyph.stop();
        if (active.get() == this) {
            active.clear();
            refreshTile();
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void update() {
        Instant now = Instant.now();
        if (!now.isBefore(deadline)) {
            finish();
            return;
        }
        glyph.show(lesson.progress(now), options);
        if (active.get() != this) return;
        handler.postDelayed(tick, Math.max(1, Math.min(1_000, Duration.between(now, deadline).toMillis())));
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, GlyphTile.class));
    }

    private void showPanel() {
        var panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(14), dp(22), dp(12));
        boolean available = lesson != null && GlyphProgress.supported();
        var title = text(available ? lesson.title() : "Glyph пары", 23);
        title.setAccessibilityHeading(true);
        panel.addView(title);
        status = text(available ? "Подключаю подсветку…" : !GlyphProgress.supported()
                ? "Подсветка доступна на Nothing Phone (3a) и (3a) Pro"
                : "Сейчас у твоей группы нет пары", 15);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status);
        if (available) panel.addView(text("Подсветка работает, пока это окно открыто.", 13));
        var button = new Button(this);
        button.setText(available ? "Остановить" : "Закрыть");
        button.setOnClickListener(view -> finish());
        panel.addView(button);
        setContentView(panel);
    }

    private TextView text(String value, int size) {
        var view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
