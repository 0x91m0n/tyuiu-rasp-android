package ru.tyuiu.rasp;

import android.content.SharedPreferences;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

record GlyphOptions(Effect effect, int brightness, Cutoff cutoff, boolean reverse, boolean keepScreenOn) {
    enum Effect {
        ELAPSED("Прошедшая часть пары"),
        REMAINING("Оставшаяся часть пары"),
        STEADY("Постоянный свет"),
        PULSE("Мягкая пульсация");

        final String label;
        Effect(String label) { this.label = label; }
    }

    enum Cutoff {
        END("До конца пары", 0),
        FIVE("До 5 минут", 5),
        FIFTEEN("До 15 минут", 15),
        THIRTY("До 30 минут", 30);

        final String label;
        final int minutes;
        Cutoff(String label, int minutes) { this.label = label; this.minutes = minutes; }
    }

    static GlyphOptions load(SharedPreferences prefs) {
        return new GlyphOptions(option(Effect.values(), prefs.getString("glyph.effect", "")),
                Math.max(10, Math.min(100, prefs.getInt("glyph.brightness", 50))),
                option(Cutoff.values(), prefs.getString("glyph.cutoff", "")),
                prefs.getBoolean("glyph.reverse", false), prefs.getBoolean("glyph.keepScreen", false));
    }

    private static <T extends Enum<T>> T option(T[] choices, String value) {
        return Arrays.stream(choices).filter(choice -> choice.name().equals(value)).findFirst().orElse(choices[0]);
    }

    Instant stopAt(Instant started, Instant lessonEnd) {
        if (cutoff == Cutoff.END) return lessonEnd;
        Instant limit = started.plus(cutoff.minutes, ChronoUnit.MINUTES);
        return limit.isBefore(lessonEnd) ? limit : lessonEnd;
    }

    int litSegments(int percent) {
        return switch (effect) {
            case ELAPSED -> Math.max(1, (percent + 4) / 5);
            case REMAINING -> Math.max(1, (104 - percent) / 5);
            case STEADY, PULSE -> 12;
        };
    }

    static Schedule.Lesson activeLesson(ScheduleStore store, Instant now) {
        Schedule schedule = store.read();
        if (schedule.demo) return null;
        Schedule.Lesson lesson = schedule.next(now, store.subgroup());
        return lesson != null && !lesson.starts().isAfter(now) ? lesson : null;
    }
}
