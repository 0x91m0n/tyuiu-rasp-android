package ru.tyuiu.rasp;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class GlyphTile extends TileService {
    @Override public void onStartListening() { refreshTile(); }

    @Override public void onTileAdded() {
        requestListeningState(this, new ComponentName(this, GlyphTile.class));
    }

    @Override public void onClick() {
        if (!GlyphProgress.supported()) return;
        if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    private void toggle() {
        if (GlyphSessionActivity.stopRunning()) {
            refreshTile();
            return;
        }
        var open = new Intent(this, GlyphSessionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT < 34) return;
        var pending = PendingIntent.getActivity(this, 25, open, PendingIntent.FLAG_IMMUTABLE);
        startActivityAndCollapse(pending);
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean supported = GlyphProgress.supported();
        boolean running = GlyphSessionActivity.isRunning();
        tile.setState(!supported ? Tile.STATE_UNAVAILABLE : running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(running ? "Остановить" : "Включить");
        tile.updateTile();
    }
}
