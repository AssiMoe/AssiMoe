package de.assimoe.libremirror;

import android.content.Context;
import android.net.Uri;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

public final class WearSync {
    public static final String PATH = "/libremirror/live";

    private WearSync() {}

    public static void push(
            Context context,
            String value,
            int trend,
            long timestampMs,
            String status,
            boolean privateMode
    ) {
        try {
            PutDataMapRequest request = PutDataMapRequest.create(PATH);
            request.getDataMap().putString("value", value == null ? "" : value);
            request.getDataMap().putInt("trend", trend);
            request.getDataMap().putLong("timestamp_ms", timestampMs);
            request.getDataMap().putString("status", status == null ? "" : status);
            request.getDataMap().putBoolean("private_mode", privateMode);
            request.getDataMap().putLong("nonce", System.currentTimeMillis());

            DataClient client = Wearable.getDataClient(context);
            client.putDataItem(request.asPutDataRequest().setUrgent());
        } catch (Exception ignored) {
        }
    }

    public static void pushEmpty(Context context, String status) {
        push(context, "", 0, 0L, status, false);
    }
}
