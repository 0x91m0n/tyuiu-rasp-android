package ru.tyuiu.rasp;

import android.content.Context;

final class GlyphProgress {
    GlyphProgress(Context context) {}

    static boolean supported() { return false; }

    void show(int percent) {}

    void stop() {}
}
