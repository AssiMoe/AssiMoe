package de.assimoe.libremirror;

import android.content.ComponentName;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

public class WearDataListenerService extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer events) {
        for (DataEvent event : events) {
            if (event.getType() != DataEvent.TYPE_CHANGED) continue;
            if (!"/libremirror/live".equals(event.getDataItem().getUri().getPath())) continue;

            WearDataStore.save(
                    this,
                    DataMapItem.fromDataItem(event.getDataItem()).getDataMap()
            );

            android.content.SharedPreferences prefs =
                    getSharedPreferences("libremirror_wear", MODE_PRIVATE);
            long now = System.currentTimeMillis();
            long last = prefs.getLong("last_complication_request_ms", 0L);

            if (now - last >= 5L * 60L * 1000L) {
                try {
                    ComplicationDataSourceUpdateRequester.create(
                            this,
                            new ComponentName(this, GlucoseComplicationService.class)
                    ).requestUpdateAll();

                    prefs.edit()
                            .putLong("last_complication_request_ms", now)
                            .apply();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
