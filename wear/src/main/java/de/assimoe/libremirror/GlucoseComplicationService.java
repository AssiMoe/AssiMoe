package de.assimoe.libremirror;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.NoDataComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

public class GlucoseComplicationService extends ComplicationDataSourceService {
    @Override
    public void onComplicationRequest(
            ComplicationRequest request,
            ComplicationRequestListener listener
    ) {
        try {
            listener.onComplicationData(buildCurrent(request.getComplicationType()));
        } catch (android.os.RemoteException ignored) {
        }
    }

    @Override
    public ComplicationData getPreviewData(ComplicationType type) {
        if (!ComplicationType.SHORT_TEXT.equals(type)) {
            return new NoDataComplicationData();
        }

        return buildShortText("123→", "Glukose 123 stabil");
    }

    private ComplicationData buildCurrent(ComplicationType type) {
        if (!ComplicationType.SHORT_TEXT.equals(type)) {
            return new NoDataComplicationData();
        }

        WearDataStore.Snapshot s = WearDataStore.read(this);

        if (s.privateMode) {
            return buildShortText("•••", "LibreMirror Privatmodus");
        }

        if (s.value.isEmpty()) {
            return buildShortText("—", "Kein Glukosewert");
        }

        String text = s.value + WearDataStore.arrow(s.trend);
        if (text.length() > 7) text = s.value;

        return buildShortText(text, "Glukose " + s.value + " Milligramm pro Deziliter");
    }

    private ComplicationData buildShortText(String value, String description) {
        ComplicationText text = new PlainComplicationText.Builder(value).build();
        ComplicationText desc = new PlainComplicationText.Builder(description).build();

        Intent open = new Intent(this, WearMainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(
                this,
                11,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new ShortTextComplicationData.Builder(text, desc)
                .setTapAction(tap)
                .build();
    }
}
