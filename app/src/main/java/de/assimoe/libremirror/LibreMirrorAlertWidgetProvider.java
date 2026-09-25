package de.assimoe.libremirror;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

public class LibreMirrorAlertWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) WidgetRenderer.update(context, manager, id, WidgetRenderer.STYLE_ALERT);
    }
}
