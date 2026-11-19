package ru.tyuiu.rasp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

abstract class ScheduleWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        ScheduleEvents.run(context, true);
    }

    @Override public void onDisabled(Context context) {
        ScheduleEvents.run(context, true);
    }
}
