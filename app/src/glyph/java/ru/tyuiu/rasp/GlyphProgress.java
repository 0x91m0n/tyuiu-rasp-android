package ru.tyuiu.rasp;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.nothing.ketchum.Common;
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphManager;

import java.util.function.Consumer;

final class GlyphProgress {
    private static final String TAG = "PairsGlyph";
    private static final int SEGMENTS = 20;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> fail(GlyphCheck.NO_SERVICE);
    private final Runnable previewEnd = () -> {
        finishPreview(GlyphCheck.LIT);
        sync();
    };
    private GlyphManager manager;
    private Consumer<GlyphCheck> preview;
    private boolean connected;
    private boolean opened;
    private int desired = -1;
    private int previous = -1;
    private long retryAfter;

    GlyphProgress(Context context) { this.context = context; }

    static boolean supported() { return Build.VERSION.SDK_INT >= 34 && Common.is24111(); }

    void show(int percent) {
        desired = percent;
        sync();
    }

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
        int count = preview != null ? 12 : (Math.min(100, desired) * SEGMENTS + 99) / 100;
        try {
            if (!opened) {
                manager.openSession();
                opened = true;
            }
            if (count != previous) {
                if (count == 0) manager.turnOff();
                else {
                    var frame = manager.getGlyphFrameBuilder();
                    for (int channel = 0; channel < count; channel++) frame.buildChannel(channel);
                    manager.toggle(frame.build());
                }
                previous = count;
            }
            if (preview != null && !handler.hasCallbacks(previewEnd)) {
                Log.i(TAG, "Preview frame submitted: 12 segments");
                handler.postDelayed(previewEnd, 3_000);
            }
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
    }

    private void finishPreview(GlyphCheck result) {
        handler.removeCallbacks(previewEnd);
        Consumer<GlyphCheck> callback = preview;
        preview = null;
        if (callback != null) callback.accept(result);
    }

    private void release() {
        handler.removeCallbacks(timeout);
        GlyphManager active = manager;
        manager = null;
        connected = false;
        previous = -1;
        if (active == null) return;
        try {
            if (opened) active.closeSession();
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
}
