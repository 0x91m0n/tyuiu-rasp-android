package ru.tyuiu.rasp;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import com.nothing.ketchum.Common;
import com.nothing.ketchum.Glyph;
import com.nothing.ketchum.GlyphFrame;
import com.nothing.ketchum.GlyphManager;

final class GlyphProgress {
    private final Context context;
    private GlyphManager manager;
    private boolean connected;
    private boolean opened;
    private int previous = -1;

    GlyphProgress(Context context) { this.context = context; }
    static boolean supported() { return Build.VERSION.SDK_INT >= 34 && Common.is24111(); }

    void show(int percent) {
        if (percent < 0 || !supported()) {
            stop();
            return;
        }
        if (manager == null) {
            try {
                GlyphManager active = GlyphManager.getInstance(context.getApplicationContext());
                manager = active;
                active.init(new GlyphManager.Callback() {
                    @Override public void onServiceConnected(ComponentName name) {
                        if (manager == active) connected = active.register(Glyph.DEVICE_24111);
                    }
                    @Override public void onServiceDisconnected(ComponentName name) {
                        connected = opened = false;
                        previous = -1;
                    }
                });
            } catch (RuntimeException unavailable) {
                stop();
            }
            return;
        }
        int count = (percent + 4) / 5;
        if (!connected || count == previous) return;
        try {
            if (!opened) {
                manager.openSession();
                opened = true;
            }
            if (count == 0) {
                manager.turnOff();
            } else {
                GlyphFrame.Builder frame = manager.getGlyphFrameBuilder();
                for (int i = 0; i < count; i++) frame.buildChannel(i);
                manager.toggle(frame.build());
            }
            previous = count;
        } catch (Exception unavailable) {
            stop();
        }
    }

    void stop() {
        GlyphManager active = manager;
        if (active == null) return;
        manager = null;
        try {
            if (opened) active.closeSession();
        } catch (Exception ignored) {
            // Nothing OS may have already closed the session.
        } finally {
            connected = opened = false;
            previous = -1;
            try { active.unInit(); } catch (RuntimeException ignored) {}
        }
    }
}
