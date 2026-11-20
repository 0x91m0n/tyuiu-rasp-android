package ru.tyuiu.rasp;

import android.content.Context;
import android.app.Activity;
import java.util.function.Consumer;

final class GlyphProgress {
    GlyphProgress(Context context) {}

    static boolean supported() { return false; }

    static void requestTile(Activity activity) {}

    void show(int percent, GlyphOptions options) {}

    void preview(Consumer<GlyphCheck> result) { result.accept(GlyphCheck.NO_SERVICE); }

    void stop() {}
}
