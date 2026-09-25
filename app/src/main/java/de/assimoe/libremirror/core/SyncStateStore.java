package de.assimoe.libremirror.core;

import android.content.Context;
import android.content.SharedPreferences;

import de.assimoe.libremirror.SecurePrefs;

public final class SyncStateStore {
    private SyncStateStore() {}

    public static void set(
            Context context,
            SyncStatus status,
            String message
    ) {
        SharedPreferences prefs = SecurePrefs.prefs(context);
        String current = prefs.getString("sync_status", "");

        SharedPreferences.Editor editor = prefs.edit()
                .putString("sync_status", status.name())
                .putString("sync_status_message", message == null ? "" : message)
                .putLong("sync_status_updated_ms", System.currentTimeMillis());

        if (!status.name().equals(current)) {
            editor.putLong("sync_status_since_ms", System.currentTimeMillis());
        }

        if (status == SyncStatus.ONLINE_OK || status == SyncStatus.SENSOR_STALE) {
            editor.putString("last_error", "");
        } else if (message != null && !message.isEmpty()) {
            editor.putString("last_error", message)
                    .putLong("last_error_ms", System.currentTimeMillis());
        }

        editor.apply();
    }

    public static SyncStatus get(Context context) {
        return SyncStatus.fromName(
                SecurePrefs.prefs(context).getString(
                        "sync_status",
                        SyncStatus.UNKNOWN_ERROR.name()
                )
        );
    }
}
