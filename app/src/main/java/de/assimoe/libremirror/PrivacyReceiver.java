package de.assimoe.libremirror;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PrivacyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LibreMirrorWidgetProvider.updateAll(context);

        if (SecurePrefs.prefs(context).getBoolean("enabled", false)) {
            Intent refresh = new Intent(context, LibreService.class);
            refresh.setAction(LibreService.ACTION_REFRESH);
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(refresh);
                } else {
                    context.startService(refresh);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
