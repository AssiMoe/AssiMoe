package de.assimoe.libremirror;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class LibreService extends Service {
    public static final String ACTION_REFRESH = "de.assimoe.libremirror.REFRESH";
    private static final String CH_SERVICE = "libremirror_service";
    private static final String CH_GLUCOSE = "libremirror_glucose";
    private static final String CH_ALERTS = "libremirror_alerts";
    private static final int ID_SERVICE = 1001;
    private static final int ID_GLUCOSE = 1002;
    private static final int ID_ALERT = 1003;

    private ScheduledExecutorService scheduler;
    private LibreApiClient client;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private volatile String lastAlertState = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(ID_SERVICE, serviceNotification("LibreMirror läuft", "Warte auf ersten Wert …"));
        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction()) && scheduler != null)
            scheduler.execute(this::pollSafely);
        return START_STICKY;
    }

    private void pollSafely() {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreMirror:poll");
            wl.acquire(30000);

            SharedPreferences p = SecurePrefs.prefs(this);
            String email = SecurePrefs.getSecret(this, "email");
            String password = SecurePrefs.getSecret(this, "password");
            if (email.isEmpty() || password.isEmpty()) {
                saveError("Bitte LibreLinkUp-Zugangsdaten speichern.");
                updateService("Login fehlt");
                return;
            }

            if (client == null) client = new LibreApiClient(p.getString("region", "AUTO"));
            LibreApiClient.Reading reading = client.fetch(email, password);

            p.edit()
                    .putString("last_value", reading.displayValue())
                    .putString("last_unit", reading.unit)
                    .putInt("last_trend", reading.trend)
                    .putString("last_sensor_time", reading.timestamp)
                    .putLong("last_fetch_ms", System.currentTimeMillis())
                    .putString("last_error", "")
                    .apply();

            appendHistory(p, reading);
            showGlucoseNotification(reading);
            checkAlert(reading, p);
            updateService("Letzter Abruf " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        } catch (Exception e) {
            saveError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            updateService("Fehler beim Abruf");
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }

    private void appendHistory(SharedPreferences prefs, LibreApiClient.Reading reading) {
        String raw = prefs.getString("history_values", "");
        String next = raw.isEmpty() ? reading.displayValue() : raw + "," + reading.displayValue();
        String[] parts = next.split(",");
        if (parts.length > 24) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = parts.length - 24; i < parts.length; i++) {
                if (trimmed.length() > 0) trimmed.append(',');
                trimmed.append(parts[i]);
            }
            next = trimmed.toString();
        }
        prefs.edit().putString("history_values", next).apply();
    }

    private void saveError(String error) {
        SecurePrefs.prefs(this).edit()
                .putString("last_error", error)
                .putLong("last_error_ms", System.currentTimeMillis())
                .apply();
    }

    private void showGlucoseNotification(LibreApiClient.Reading reading) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String value = reading.displayValue() + " " + reading.unit + "  " + LibreApiClient.arrow(reading.trend);

        Notification notification = new Notification.Builder(this, CH_GLUCOSE)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(value)
                .setContentText("LibreMirror • gerade aktualisiert" +
                        (reading.timestamp.isEmpty() ? "" : " • Sensor " + reading.timestamp))
                .setContentIntent(openAppIntent())
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        nm.notify(ID_GLUCOSE, notification);
    }

    private void checkAlert(LibreApiClient.Reading reading, SharedPreferences prefs) {
        double low = parseDouble(prefs.getString("low", "70"), 70);
        double high = parseDouble(prefs.getString("high", "180"), 180);

        if ("mmol/L".equals(reading.unit)) {
            low /= 18.0;
            high /= 18.0;
        }

        String state = reading.value < low ? "LOW" : (reading.value > high ? "HIGH" : "OK");
        if ("OK".equals(state)) {
            lastAlertState = "OK";
            return;
        }
        if (state.equals(lastAlertState)) return;
        lastAlertState = state;

        String title = "LOW".equals(state) ? "Glukose niedrig" : "Glukose hoch";
        String value = reading.displayValue() + " " + reading.unit + " " + LibreApiClient.arrow(reading.trend);

        Notification notification = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(value)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID_ALERT, notification);

        if (prefs.getBoolean("car_voice", true) && isCarMode() && ttsReady) {
            String spoken = ("LOW".equals(state) ? "Glukose niedrig. " : "Glukose hoch. ")
                    + reading.displayValue() + " "
                    + ("mg/dL".equals(reading.unit)
                    ? "Milligramm pro Deziliter"
                    : "Millimol pro Liter");
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "libremirror-alert");
        }
    }

    private boolean isCarMode() {
        UiModeManager modeManager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return modeManager != null && modeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
    }

    private PendingIntent openAppIntent() {
        Intent intent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private Notification serviceNotification(String title, String text) {
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateService(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(ID_SERVICE, serviceNotification("LibreMirror aktiv", text));
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel service = new NotificationChannel(
                CH_SERVICE, "LibreMirror Hintergrunddienst", NotificationManager.IMPORTANCE_MIN);
        service.setSound(null, null);
        service.enableVibration(false);
        service.setShowBadge(false);
        nm.createNotificationChannel(service);

        NotificationChannel glucose = new NotificationChannel(
                CH_GLUCOSE, "Aktueller Glukosewert", NotificationManager.IMPORTANCE_DEFAULT);
        glucose.setSound(null, null);
        glucose.enableVibration(false);
        glucose.setDescription("Wird auf unterstützte Smartwatches gespiegelt.");
        nm.createNotificationChannel(glucose);

        NotificationChannel alerts = new NotificationChannel(
                CH_ALERTS, "Glukosewarnungen", NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);
        nm.createNotificationChannel(alerts);
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
