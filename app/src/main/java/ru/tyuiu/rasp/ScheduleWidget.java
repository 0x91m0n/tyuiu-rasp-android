package ru.tyuiu.rasp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

abstract class ScheduleWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        ScheduleEvents.run(context, true);
    }

    @Override public void onDisabled(Context context) {
        ScheduleEvents.run(context, true);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        ScheduleEvents.run(context, false);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        WidgetOptions.deleted(context, ids);
        ScheduleEvents.run(context, false);
    }
}
