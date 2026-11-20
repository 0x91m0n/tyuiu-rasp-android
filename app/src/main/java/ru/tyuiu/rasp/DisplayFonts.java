package ru.tyuiu.rasp;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.widget.TextView;

import java.io.File;

final class DisplayFonts {
    private static Typeface dot;

    private DisplayFonts() {}

    static synchronized Typeface dot(Context context) {
        if (dot != null) return dot;
        for (String root : new String[]{"/system/fonts/", "/product/fonts/", "/system_ext/fonts/"}) {
            for (String name : new String[]{"Ndot-57.otf", "NDot57Caps.otf", "NDot55Caps.otf", "Ndot-55.otf"}) {
                try {
                    File font = new File(root + name);
                    if (!font.canRead()) continue;
                    dot = new Typeface.Builder(font).setFallback("sans-serif").build();
                    if (dot != null) {
                        Log.i("PairsTheme", "Using system display font " + name);
                        return dot;
                    }
                } catch (RuntimeException unavailable) {
                    // A vendor image can omit a font or make it inaccessible to apps.
                }
            }
        }
        dot = context.getResources().getFont(R.font.doto);
        return dot;
    }

    static void time(TextView text, ThemePalette palette) {
        if (palette == ThemePalette.MONO) text.setTypeface(dot(text.getContext()));
    }

    static void heading(TextView text, ThemePalette palette) {
        if (palette == ThemePalette.PAPER) text.setTypeface(Typeface.SERIF, Typeface.BOLD);
        if (palette == ThemePalette.MONO) {
            Typeface font = dot(text.getContext());
            var paint = new android.graphics.Paint();
            paint.setTypeface(font);
            String title = text.getText().toString().toUpperCase(Schedule.RU);
            if (title.codePoints().allMatch(point ->
                    Character.isWhitespace(point) || paint.hasGlyph(Character.toString(point)))) {
                text.setTypeface(font);
                text.setText(title);
            }
        }
    }
}
