package ru.tyuiu.rasp;

import android.content.Context;
import android.content.res.Configuration;

enum ThemePalette {
    DARK(0xff131b17, 0xff243329, 0xff304238, 0xfff0f5eb, 0xffb6c5b9, 0xffcef782, 0xff213114),
    LIGHT(0xffeff3ee, 0xffffffff, 0xffdde8dd, 0xff17271c, 0xff4d6455, 0xff315d37, 0xfff6fff1),
    INDIGO(0xff141625, 0xff232640, 0xff333858, 0xfff3f0ff, 0xffc0c2dc, 0xffc6bdff, 0xff282052),
    AMBER(0xff211813, 0xff32251e, 0xff493428, 0xfffff4e8, 0xffdec3ad, 0xffffce8b, 0xff3b2614),
    MONO(0xff080808, 0xff171717, 0xff252525, 0xfffafafa, 0xffbcbcbc, 0xffff665e, 0xff101010),
    PAPER(0xfff2ece0, 0xfffdf8ed, 0xffe9e0d0, 0xff26221a, 0xff655647, 0xff72482f, 0xfffff7ea),
    OCEAN(0xff0a1a23, 0xff152b36, 0xff24424e, 0xffeefbff, 0xffb0c8d1, 0xff89e1dd, 0xff0b3033),
    PLUM(0xff201621, 0xff322237, 0xff46304d, 0xfffff3ff, 0xffdbbfdc, 0xfff1b7dc, 0xff3d1532);

    static final String[] KEYS = {"auto", "dark", "light", "indigo", "amber", "mono", "paper", "ocean", "plum"};
    static final String[] LABELS = {"Как в системе", "Тёмная", "Светлая", "Индиго", "Янтарь", "Монохром",
            "Бумага", "Океан", "Слива"};
    final int background, card, control, text, muted, accent, onAccent;

    ThemePalette(int background, int card, int control, int text, int muted, int accent, int onAccent) {
        this.background = background;
        this.card = card;
        this.control = control;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.onAccent = onAccent;
    }

    boolean light() { return this == LIGHT || this == PAPER; }

    static ThemePalette resolve(Context context, String setting) {
        return switch (setting) {
            case "dark" -> DARK;
            case "light" -> LIGHT;
            case "indigo" -> INDIGO;
            case "amber" -> AMBER;
            case "mono" -> MONO;
            case "paper" -> PAPER;
            case "ocean" -> OCEAN;
            case "plum" -> PLUM;
            default -> (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES ? DARK : LIGHT;
        };
    }
}
