package de.assimoe.libremirror;

import android.app.Application;

import de.assimoe.libremirror.core.SyncStateStore;
import de.assimoe.libremirror.core.SyncStatus;
import de.assimoe.libremirror.data.GlucoseRepository;

public class LibreMirrorApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        GlucoseRepository.get(this).migrateLegacyDataAsync();

        if (!SecurePrefs.prefs(this).contains("sync_status")) {
            SyncStateStore.set(
                    this,
                    SyncStatus.UNKNOWN_ERROR,
                    ""
            );
        }
    }
}
