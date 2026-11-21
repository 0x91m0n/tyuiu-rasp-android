package ru.tyuiu.rasp;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetHostView;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.SizeF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

public final class WidgetConfigurationActivity extends Activity {
    private final LinkedHashMap<Integer, CheckBox> checks = new LinkedHashMap<>();
    private ScheduleStore store;
    private ThemePalette palette;
    private int id;
    private SizeF previewSize;
    private List<String> subgroups;
    private final List<String> themes = new ArrayList<>();
    private Spinner mode, subgroup, theme, textSize;
    private CheckBox compact;
    private EditText title;
    private SeekBar opacity;
    private TextView opacityTitle;
    private FrameLayout preview;
    private LinearLayout form;

    static Intent intent(Context context, int id) {
        return new Intent(context, WidgetConfigurationActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
    }

    @Override protected void onCreate(Bundle state) {
        store = new ScheduleStore(this);
        palette = ThemePalette.resolve(this, store.theme());
        if (palette.light()) setTheme(R.style.AppThemeLight);
        super.onCreate(state);
        id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        var result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        setResult(RESULT_CANCELED, result);
        Widgets.Kind fallback = Widgets.providerFor(this, id);
        if (fallback == null) { finish(); return; }
        var manager = AppWidgetManager.getInstance(this);
        SizeF widgetSize = Widgets.sizes(manager.getAppWidgetOptions(id), fallback).get(0);
        var metrics = getResources().getDisplayMetrics();
        previewSize = new SizeF(Math.min(widgetSize.getWidth(), Math.max(1, metrics.widthPixels / metrics.density - 40)),
                widgetSize.getHeight());
        var initial = state == null ? WidgetOptions.load(this, id, fallback)
                : WidgetOptions.decode(state.getString("draft", "{}"), fallback);
        getWindow().setDecorFitsSystemWindows(false);
        var root = column();
        root.setBackgroundColor(palette.background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var edge = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(edge.left, edge.top, edge.right, edge.bottom);
            return insets;
        });
        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        form = column();
        form.setPadding(dp(20), dp(17), dp(20), dp(24));
        scroll.addView(form);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        var actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(dp(14), dp(5), dp(14), dp(8));
        var cancel = new Button(this);
        cancel.setText("Отмена");
        cancel.setOnClickListener(view -> finish());
        var save = new Button(this);
        save.setText("Сохранить");
        save.setBackgroundTintList(ColorStateList.valueOf(palette.accent));
        save.setTextColor(palette.onAccent);
        save.setOnClickListener(view -> {
            if (Widgets.providerFor(this, id) == null) { finish(); return; }
            WidgetOptions.save(this, id, options());
            ScheduleEvents.run(this, false);
            setResult(RESULT_OK, result);
            finish();
        });
        actions.addView(cancel);
        actions.addView(save);
        root.addView(actions);
        setContentView(root);
        add(label("Настройка виджета", 27, true), 0);
        add(label("Предпросмотр", 14, true), 14);
        preview = new AppWidgetHostView(this);
        preview.setBackground(shape(palette.control));
        var frame = new LinearLayout.LayoutParams(dp(previewSize.getWidth()), dp(previewSize.getHeight()));
        frame.gravity = Gravity.CENTER_HORIZONTAL;
        preview.setLayoutParams(frame);
        form.addView(preview);

        section("Расписание");
        var names = java.util.Arrays.stream(Widgets.Kind.values()).map(kind -> getString(kind.title)).collect(Collectors.toList());
        mode = picker("Формат", names, initial.kind().ordinal());
        var groupNames = new ArrayList<>(List.of("Как в приложении", "Вся группа"));
        subgroups = new ArrayList<>(List.of(WidgetOptions.FOLLOW_GROUP, ""));
        for (String group : store.read().subgroups()) {
            subgroups.add(group);
            groupNames.add("Подгруппа " + group);
        }
        if (!subgroups.contains(initial.subgroup())) {
            subgroups.add(initial.subgroup());
            groupNames.add("Подгруппа " + initial.subgroup() + " · сохранена");
        }
        subgroup = picker("Подгруппа", groupNames, subgroups.indexOf(initial.subgroup()));

        section("Оформление");
        themes.add(WidgetOptions.FOLLOW_THEME);
        themes.addAll(java.util.Arrays.asList(ThemePalette.KEYS));
        var themeNames = new ArrayList<>(List.of("Как в приложении"));
        themeNames.addAll(java.util.Arrays.asList(ThemePalette.LABELS));
        theme = picker("Тема виджета", themeNames, Math.max(0, themes.indexOf(initial.theme())));
        textSize = picker("Размер текста", List.of("Мелкий", "Обычный", "Крупный"), initial.textSize() + 1);
        opacityTitle = label("", 15, false);
        add(opacityTitle, 9);
        opacity = new SeekBar(this);
        opacity.setContentDescription("Непрозрачность фона");
        opacity.setMax(100);
        opacity.setProgress(initial.opacity());
        add(opacity, 0);
        compact = checkbox("Компактные отступы", initial.compact());
        title = new EditText(this);
        title.setSingleLine(true);
        title.setHint("Свой заголовок");
        title.setContentDescription("Свой заголовок");
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        title.setText(initial.title());
        add(title, 4);

        section("Показывать");
        field(initial, WidgetOptions.HEADER, "Заголовок");
        field(initial, WidgetOptions.GROUP, "Название группы");
        field(initial, WidgetOptions.TIME, "Время пар");
        field(initial, WidgetOptions.ROOM, "Аудитории");
        field(initial, WidgetOptions.TEACHER, "Преподавателей");
        field(initial, WidgetOptions.TYPE, "Тип занятия");
        field(initial, WidgetOptions.GAPS, "Окна между парами");
        field(initial, WidgetOptions.COUNTDOWN, "Таймер до начала или конца");
        field(initial, WidgetOptions.WEEK_CLASSES, "Список пар в недельном обзоре");
        connect();
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (mode != null) state.putString("draft", options().encode());
    }

    private WidgetOptions options() {
        int fields = 0;
        for (var pair : checks.entrySet()) if (pair.getValue().isChecked()) fields |= pair.getKey();
        return new WidgetOptions(Widgets.Kind.values()[mode.getSelectedItemPosition()],
                subgroups.get(subgroup.getSelectedItemPosition()), themes.get(theme.getSelectedItemPosition()),
                opacity.getProgress(), textSize.getSelectedItemPosition() - 1,
                compact.isChecked(), fields, title.getText().toString().trim());
    }

    private void refresh() {
        var current = options();
        boolean week = current.kind() == Widgets.Kind.WEEK;
        boolean timer = current.kind() == Widgets.Kind.NEXT || current.kind() == Widgets.Kind.PROGRESS;
        boolean details = !week || current.has(WidgetOptions.WEEK_CLASSES);
        visibility(WidgetOptions.WEEK_CLASSES, week);
        visibility(WidgetOptions.COUNTDOWN, timer);
        visibility(WidgetOptions.GAPS, !timer && details);
        visibility(WidgetOptions.GROUP, !timer);
        visibility(WidgetOptions.ROOM, details);
        visibility(WidgetOptions.TEACHER, details);
        visibility(WidgetOptions.TYPE, details);
        opacityTitle.setText(getString(R.string.widget_opacity, current.opacity()));
        var schedule = store.read();
        if (schedule.group.isEmpty()) schedule = Schedule.example();
        var sample = Widgets.make(this, store, schedule, Instant.now(), AppWidgetManager.INVALID_APPWIDGET_ID, current, previewSize);
        preview.removeAllViews();
        preview.addView(sample.apply(this, preview));
    }

    private void visibility(int field, boolean show) { checks.get(field).setVisibility(show ? View.VISIBLE : View.GONE); }

    private void connect() {
        var changes = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        for (Spinner picker : List.of(mode, subgroup, theme, textSize)) picker.setOnItemSelectedListener(changes);
        compact.setOnCheckedChangeListener((button, checked) -> refresh());
        for (var check : checks.values()) check.setOnCheckedChangeListener((button, checked) -> refresh());
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int percent, boolean fromUser) { refresh(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        title.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable text) {}
        });
    }

    private Spinner picker(String name, List<String> values, int selected) {
        add(label(name, 14, false), 10);
        var input = new Spinner(this, Spinner.MODE_DROPDOWN);
        input.setContentDescription(name);
        var adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View reused, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, reused, parent);
                view.setTextColor(palette.text);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        input.setAdapter(adapter);
        input.setSelection(selected);
        input.setMinimumHeight(dp(46));
        add(input, 2);
        return input;
    }

    private void field(WidgetOptions options, int flag, String name) {
        checks.put(flag, checkbox(name, options.has(flag)));
    }

    private CheckBox checkbox(String name, boolean checked) {
        var check = new CheckBox(this);
        check.setText(name);
        check.setTextColor(palette.text);
        check.setTextSize(15);
        check.setChecked(checked);
        check.setMinimumHeight(dp(44));
        add(check, 0);
        return check;
    }

    private LinearLayout column() {
        var result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private TextView label(String name, int size, boolean heading) {
        var result = new TextView(this);
        result.setText(name);
        result.setTextColor(heading ? palette.text : palette.muted);
        result.setTextSize(size);
        if (heading) {
            result.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            result.setAccessibilityHeading(true);
        }
        return result;
    }

    private void section(String name) { add(label(name, 20, true), 21); }

    private void add(View item, int margin) {
        var size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(margin);
        form.addView(item, size);
    }

    private GradientDrawable shape(int color) {
        var style = new GradientDrawable();
        style.setColor(color);
        style.setCornerRadius(dp(19));
        return style;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
