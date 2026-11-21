package ru.tyuiu.rasp;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import com.nothing.ketchum.Common;
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphManager;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

final class GlyphProgress {
    private static final String TAG = "PairsGlyph";
    private static final int SEGMENTS = 20;
    private static WeakReference<GlyphProgress> owner = new WeakReference<>(null);
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> fail(GlyphCheck.NO_SERVICE);
    private final Runnable previewEnd = () -> {
        finishPreview(GlyphCheck.LIT);
        sync();
    };
    private final Runnable animate = this::draw;
    private GlyphManager manager;
    private Consumer<GlyphCheck> preview;
    private Consumer<GlyphCheck> progressStatus;
    private boolean connected;
    private boolean opened;
    private int desired = -1;
    private GlyphOptions options;
    private Frame previous;
    private long retryAfter;

    GlyphProgress(Context context) { this.context = context; }

    static boolean supported() { return Build.VERSION.SDK_INT >= 34 && Common.is24111(); }
    static boolean tileRunning() { return GlyphClassService.running(); }

    static void openWindow(Activity activity) {
        if (tileRunning()) {
            Toast.makeText(activity, "Сначала останови Glyph в шторке", Toast.LENGTH_SHORT).show();
            return;
        }
        activity.startActivity(new Intent(activity, GlyphSessionActivity.class));
    }

    static void requestTile(Activity activity) {
        var statusBar = activity.getSystemService(StatusBarManager.class);
        try {
            statusBar.requestAddTileService(new ComponentName(activity, GlyphTile.class), "Glyph пары",
                    Icon.createWithResource(activity, R.drawable.ic_glyph_tile), activity.getMainExecutor(), result -> {
                        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            Toast.makeText(activity, "Плитка Glyph уже в шторке", Toast.LENGTH_SHORT).show();
                        }
                    });
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, "Добавь «Glyph пары» через меню плиток в шторке", Toast.LENGTH_LONG).show();
        }
    }

    void show(int percent, GlyphOptions options) {
        desired = percent;
        this.options = options;
        sync();
    }

    void observeProgress(Consumer<GlyphCheck> status) { progressStatus = status; }

    void preview(Consumer<GlyphCheck> result) {
        if (!supported()) {
            result.accept(GlyphCheck.NO_SERVICE);
            return;
        }
        if (preview != null) return;
        preview = result;
        retryAfter = 0;
        sync();
    }

    private void sync() {
        if (!supported() || (desired < 0 && preview == null)) {
            release();
            return;
        }
        if (manager == null) {
            if (SystemClock.elapsedRealtime() >= retryAfter) connect();
        } else if (connected) {
            draw();
        }
    }

    private void connect() {
        try {
            GlyphProgress current = owner.get();
            if (current != null && current != this) current.stop();
            owner = new WeakReference<>(this);
            GlyphManager active = GlyphManager.getInstance(context);
            manager = active;
            handler.postDelayed(timeout, 4_000);
            active.init(new GlyphManager.Callback() {
                @Override public void onServiceConnected(ComponentName name) {
                    handler.post(() -> register(active));
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    handler.post(() -> {
                        if (manager == active) fail(GlyphCheck.NO_SERVICE);
                    });
                }
            });
        } catch (RuntimeException unavailable) {
            fail(GlyphCheck.NO_SERVICE);
        }
    }

    private void register(GlyphManager active) {
        if (manager != active) return;
        handler.removeCallbacks(timeout);
        try {
            if (!active.register(Glyph.DEVICE_24111)) {
                fail(GlyphCheck.REJECTED);
                return;
            }
            connected = true;
            draw();
        } catch (RuntimeException unavailable) {
            fail(GlyphCheck.ERROR);
        }
    }

    private void draw() {
        handler.removeCallbacks(animate);
        boolean pulse = preview == null && options.effect() == GlyphOptions.Effect.PULSE;
        int level = options == null ? 2_000 : options.brightness() * 40;
        if (pulse) {
            double wave = (1 - Math.cos(SystemClock.elapsedRealtime() % 4_000 * Math.PI / 2_000)) / 2;
            level = (int) (level * (0.2 + 0.8 * wave));
        }
        int count = preview != null ? 12 : options.litSegments(Math.min(100, desired));
        var frameState = new Frame(count, level, preview == null && options.reverse());
        try {
            if (!opened) {
                manager.openSession();
                opened = true;
            }
            if (!frameState.equals(previous)) {
                boolean firstFrame = previous == null;
                var frame = manager.getGlyphFrameBuilder();
                for (int channel = 0; channel < count; channel++) {
                    frame.buildChannel(frameState.reverse() ? SEGMENTS - 1 - channel : channel, level);
                }
                if (context instanceof GlyphClassService) manager.setFrameColors(frame.build().getChannel());
                else manager.toggle(frame.build());
                previous = frameState;
                if (firstFrame && progressStatus != null) progressStatus.accept(GlyphCheck.LIT);
            }
            if (preview != null && !handler.hasCallbacks(previewEnd)) {
                Log.i(TAG, "Preview frame submitted: 12 segments");
                handler.postDelayed(previewEnd, 3_000);
            }
            if (pulse) handler.postDelayed(animate, 200);
        } catch (Exception unavailable) {
            Log.w(TAG, "Frame or session failed: " + unavailable.getClass().getSimpleName());
            fail(GlyphCheck.ERROR);
        }
    }

    private void fail(GlyphCheck reason) {
        Log.w(TAG, "Glyph status: " + reason);
        retryAfter = SystemClock.elapsedRealtime() + 20_000;
        release();
        finishPreview(reason);
        if (progressStatus != null) progressStatus.accept(reason);
    }

    private void finishPreview(GlyphCheck result) {
        handler.removeCallbacks(previewEnd);
        Consumer<GlyphCheck> callback = preview;
        preview = null;
        if (callback != null) callback.accept(result);
    }

    private void release() {
        handler.removeCallbacks(timeout);
        handler.removeCallbacks(animate);
        if (owner.get() == this) owner.clear();
        GlyphManager active = manager;
        manager = null;
        connected = false;
        previous = null;
        if (active == null) return;
        try {
            if (opened) {
                try { active.turnOff(); } finally { active.closeSession(); }
            }
        } catch (Exception ignored) {
            // Nothing OS can close the session when the window loses focus.
        } finally {
            opened = false;
            try { active.unInit(); } catch (RuntimeException ignored) {}
        }
    }

    void stop() {
        desired = -1;
        retryAfter = 0;
        release();
        finishPreview(GlyphCheck.CANCELLED);
    }

    private record Frame(int count, int level, boolean reverse) {}
}
