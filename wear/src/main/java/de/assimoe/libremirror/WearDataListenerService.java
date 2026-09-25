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

            ComplicationDataSourceUpdateRequester.create(
                    this,
                    new ComponentName(this, GlucoseComplicationService.class)
            ).requestUpdateAll();
        }
    }
}
