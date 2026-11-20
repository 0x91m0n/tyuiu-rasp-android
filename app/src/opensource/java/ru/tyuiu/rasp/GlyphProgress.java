package ru.tyuiu.rasp;

import android.content.Context;
import java.util.function.Consumer;

final class GlyphProgress {
    GlyphProgress(Context context) {}

    static boolean supported() { return false; }

    void show(int percent) {}

    void preview(Consumer<GlyphCheck> result) { result.accept(GlyphCheck.NO_SERVICE); }

    void stop() {}
}
