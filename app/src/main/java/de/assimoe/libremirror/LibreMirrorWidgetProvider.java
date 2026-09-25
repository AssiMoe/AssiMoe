package de.assimoe.libremirror;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;

public class LibreMirrorWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            WidgetRenderer.update(context, manager, id, WidgetRenderer.STYLE_COMPACT);
        }
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);

        updateProvider(context, manager, LibreMirrorMiniWidgetProvider.class, WidgetRenderer.STYLE_MINI);
        updateProvider(context, manager, LibreMirrorCleanWidgetProvider.class, WidgetRenderer.STYLE_CLEAN);
        updateProvider(context, manager, LibreMirrorWidgetProvider.class, WidgetRenderer.STYLE_COMPACT);
        updateProvider(context, manager, LibreMirrorLargeWidgetProvider.class, WidgetRenderer.STYLE_LARGE);
        updateProvider(context, manager, LibreMirrorAlertWidgetProvider.class, WidgetRenderer.STYLE_ALERT);
    }

    private static void updateProvider(
            Context context,
            AppWidgetManager manager,
            Class<?> providerClass,
            int style
    ) {
        ComponentName provider = new ComponentName(context, providerClass);
        int[] ids = manager.getAppWidgetIds(provider);

        for (int id : ids) {
            WidgetRenderer.update(context, manager, id, style);
        }
    }
}
