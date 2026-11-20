package ru.tyuiu.rasp;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.text.TextRunShaper;
import android.util.Log;
import android.widget.TextView;

import java.io.File;

final class DisplayFonts {
    private static Typeface dot;

    private DisplayFonts() {}

    private static synchronized Typeface dot(Context context) {
        if (dot != null) return dot;
        for (String root : new String[]{"/system/fonts", "/product/fonts", "/system_ext/fonts"}) {
            File[] files = new File(root).listFiles((directory, name) -> {
                String normalized = name.toLowerCase(java.util.Locale.ROOT);
                return normalized.startsWith("ndot") && (normalized.endsWith(".otf") || normalized.endsWith(".ttf"));
            });
            if (files == null) continue;
            java.util.Arrays.sort(files, java.util.Comparator.comparing((File file) ->
                    !file.getName().toLowerCase(java.util.Locale.ROOT).contains("57")).thenComparing(File::getName));
            for (File file : files) {
                try {
                    if (!file.canRead()) continue;
                    Typeface candidate = new Typeface.Builder(file).setFallback("sans-serif").build();
                    if (candidate != null && drawsRussian(candidate, file)) {
                        dot = candidate;
                        Log.i("PairsTheme", "Using Russian display font " + file.getName());
                        return dot;
                    }
                } catch (RuntimeException unavailable) {
                    // A vendor image can omit a font or make it inaccessible to apps.
                }
            }
        }
        try {
            var font = new Font.Builder(context.getResources(), R.font.handjet)
                    .setFontVariationSettings("'wght' 620, 'ELSH' 8").build();
            dot = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build()).build();
        } catch (java.io.IOException invalid) {
            dot = context.getResources().getFont(R.font.handjet);
        }
        return dot;
    }

    private static boolean drawsRussian(Typeface typeface, File source) {
        var paint = new Paint();
        paint.setTypeface(typeface);
        String samples = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ0123456789";
        var glyphs = TextRunShaper.shapeTextRun(samples, 0, samples.length(), 0, samples.length(),
                0, 0, false, paint);
        if (glyphs.glyphCount() != samples.length()) return false;
        for (int i = 0; i < glyphs.glyphCount(); i++) {
            if (glyphs.getGlyphId(i) == 0 || !source.equals(glyphs.getFont(i).getFile())) return false;
        }
        return true;
    }

    static void time(TextView text, ThemePalette palette) {
        if (palette == ThemePalette.MONO) text.setTypeface(dot(text.getContext()));
    }

    static void heading(TextView text, ThemePalette palette) {
        if (palette == ThemePalette.PAPER) text.setTypeface(Typeface.SERIF, Typeface.BOLD);
        if (palette == ThemePalette.MONO) {
            text.setTypeface(dot(text.getContext()));
            text.setText(text.getText().toString().toUpperCase(Schedule.RU));
        }
    }
}
