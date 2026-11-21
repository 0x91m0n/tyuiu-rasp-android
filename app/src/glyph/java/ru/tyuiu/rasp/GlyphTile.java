package ru.tyuiu.rasp;

import android.content.ComponentName;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public final class GlyphTile extends TileService {
    @Override public void onStartListening() { refreshTile(); }

    @Override public void onTileAdded() {
        requestListeningState(this, new ComponentName(this, GlyphTile.class));
    }

    @Override public void onClick() {
        if (!GlyphProgress.supported()) return;
        if (GlyphClassService.running()) {
            stopService(GlyphClassService.intent(this));
            return;
        }
        if (GlyphSessionActivity.stopRunning()) {
            refreshTile();
            return;
        }
        try {
            startForegroundService(GlyphClassService.intent(this));
        } catch (IllegalStateException | SecurityException blocked) {
            Toast.makeText(this, "Android не разрешил включить Glyph из шторки", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean supported = GlyphProgress.supported();
        boolean running = GlyphClassService.running() || GlyphSessionActivity.isRunning();
        tile.setState(!supported ? Tile.STATE_UNAVAILABLE : running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(running ? "Остановить" : GlyphClassService.tileMessage());
        tile.updateTile();
    }
}
