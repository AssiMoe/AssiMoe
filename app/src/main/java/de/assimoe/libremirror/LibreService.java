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
    public static final String ACTION_ACCEPT_TERMS = "de.assimoe.libremirror.ACCEPT_TERMS";
    public static final String ACTION_VERIFY_2FA = "de.assimoe.libremirror.VERIFY_2FA";
    public static final String ACTION_RESEND_2FA = "de.assimoe.libremirror.RESEND_2FA";
    public static final String EXTRA_2FA_CODE = "two_factor_code";
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
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, 5, TimeUnit.MINUTES);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && scheduler != null) {
            if (ACTION_REFRESH.equals(intent.getAction())) {
                scheduler.execute(this::pollSafely);
            } else if (ACTION_ACCEPT_TERMS.equals(intent.getAction())) {
                scheduler.execute(this::acceptTermsSafely);
            } else if (ACTION_VERIFY_2FA.equals(intent.getAction())) {
                final String code = intent.getStringExtra(EXTRA_2FA_CODE);
                scheduler.execute(() -> verifyTwoFactorSafely(code));
            } else if (ACTION_RESEND_2FA.equals(intent.getAction())) {
                scheduler.execute(this::resendTwoFactorSafely);
            }
        }
        return START_STICKY;
    }

    private void pollSafely() {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreMirror:poll");
            wl.acquire(90000);

            SharedPreferences p = SecurePrefs.prefs(this);

            if (p.getBoolean("two_factor_required", false)) {
                updateService("Warte auf LibreView-2FA-Code");
                return;
            }

            String email = SecurePrefs.getSecret(this, "email");
            String password = SecurePrefs.getSecret(this, "password");
            if (email.isEmpty() || password.isEmpty()) {
                saveError("Bitte LibreView-Zugangsdaten speichern.");
                updateService("Login fehlt");
                return;
            }

            if (client == null) client = new LibreApiClient(p.getString("region", "AUTO"));
            String previousSensorTime = p.getString("last_sensor_time", "");
            LibreApiClient.Reading reading = client.fetch(email, password);

            p.edit()
                    .putString("last_value", reading.displayValue())
                    .putString("last_unit", reading.unit)
                    .putInt("last_trend", reading.trend)
                    .putString("last_sensor_time", reading.timestamp)
                    .putLong("last_fetch_ms", System.currentTimeMillis())
                    .putString("last_error", "")
                    .putBoolean("terms_required", false)
                    .putBoolean("two_factor_required", false)
                    .remove("terms_step")
                    .remove("pending_2fa_base_url")
                    .remove("pending_2fa_mode")
                    .apply();

            if (!reading.timestamp.equals(previousSensorTime)) {
                appendHistory(p, reading);
            }
            showGlucoseNotification(reading);
            checkAlert(reading, p);
            updateService("LibreView-Bericht aktualisiert " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
        } catch (LibreApiClient.TermsRequiredException e) {
            SecurePrefs.prefs(this).edit()
                    .putBoolean("terms_required", true)
                    .putString("terms_step", e.getStepType())
                    .putBoolean("two_factor_required", false)
                    .putString("last_error", e.getMessage())
                    .putLong("last_error_ms", System.currentTimeMillis())
                    .apply();
            updateService("LibreView-Kontobestätigung erforderlich");
        } catch (LibreApiClient.TwoFactorRequiredException e) {
            beginTwoFactorSafely(e.getMessage());
        } catch (Exception e) {
            saveError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            updateService("Fehler beim Abruf");
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }

    private void acceptTermsSafely() {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreMirror:terms");
            wl.acquire(90000);

            SharedPreferences p = SecurePrefs.prefs(this);
            String email = SecurePrefs.getSecret(this, "email");
            String password = SecurePrefs.getSecret(this, "password");

            if (email.isEmpty() || password.isEmpty()) {
                saveError("Bitte LibreView-Zugangsdaten speichern.");
                return;
            }

            if (client == null) client = new LibreApiClient(p.getString("region", "AUTO"));
            LibreApiClient.Reading reading = client.acceptTermsAndFetch(email, password);

            p.edit()
                    .putBoolean("terms_required", false)
                    .remove("terms_step")
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
            updateService("LibreView-Bedingungen bestätigt");
        } catch (LibreApiClient.TermsRequiredException e) {
            SecurePrefs.prefs(this).edit()
                    .putBoolean("terms_required", true)
                    .putString("terms_step", e.getStepType())
                    .putBoolean("two_factor_required", false)
                    .putString("last_error", e.getMessage())
                    .putLong("last_error_ms", System.currentTimeMillis())
                    .apply();
            updateService("Weiterer LibreView-Kontoschritt erforderlich");
        } catch (LibreApiClient.TwoFactorRequiredException e) {
            beginTwoFactorSafely(e.getMessage());
        } catch (Exception e) {
            saveError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            updateService("Bestätigung fehlgeschlagen");
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }


    private void beginTwoFactorSafely(String reason) {
        try {
            SharedPreferences prefs = SecurePrefs.prefs(this);

            if (client == null) {
                throw new Exception("LibreView-2FA konnte nicht gestartet werden, weil die Login-Sitzung fehlt.");
            }

            String twoFactorMode = client.sendTwoFactorCode();

            SecurePrefs.putSecret(this, "pending_2fa_token", client.getAuthToken());
            prefs.edit()
                    .putString("pending_2fa_base_url", client.getBaseUrl())
                    .putString("pending_2fa_mode", twoFactorMode)
                    .putBoolean("two_factor_required", true)
                    .putBoolean("terms_required", false)
                    .remove("terms_step")
                    .putString("last_error",
                            "LibreView hat einen Bestätigungscode per E-Mail gesendet. Bitte Code unten eingeben.")
                    .putLong("last_error_ms", System.currentTimeMillis())
                    .apply();

            updateService("LibreView-2FA-Code wurde angefordert");
        } catch (Exception e) {
            saveError(
                    "2FA-Code konnte nicht angefordert werden: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
            );
            updateService("2FA-Code konnte nicht angefordert werden");
        }
    }

    private void verifyTwoFactorSafely(String code) {
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreMirror:2fa");
            wl.acquire(90000);

            SharedPreferences prefs = SecurePrefs.prefs(this);

            if (code == null || code.trim().isEmpty()) {
                saveError("Bitte den LibreView-Bestätigungscode eingeben.");
                return;
            }

            if (client == null) {
                client = new LibreApiClient(prefs.getString("region", "AUTO"));
            }

            String pendingToken = SecurePrefs.getSecret(this, "pending_2fa_token");
            String pendingBaseUrl = prefs.getString("pending_2fa_base_url", "");
            String pendingMode = prefs.getString("pending_2fa_mode", "BOOL_TRUE");

            if (pendingToken.isEmpty()) {
                throw new Exception(
                        "Die 2FA-Sitzung ist nicht mehr vorhanden. Bitte über 'Code erneut senden' einen neuen Code anfordern."
                );
            }

            client.restoreTwoFactorSession(pendingToken, pendingBaseUrl, pendingMode);
            LibreApiClient.Reading reading = client.verifyTwoFactorAndFetch(code.trim());

            String previousSensorTime = prefs.getString("last_sensor_time", "");

            prefs.edit()
                    .putBoolean("two_factor_required", false)
                    .putBoolean("terms_required", false)
                    .remove("terms_step")
                    .remove("pending_2fa_base_url")
                    .remove("pending_2fa_mode")
                    .putString("last_value", reading.displayValue())
                    .putString("last_unit", reading.unit)
                    .putInt("last_trend", reading.trend)
                    .putString("last_sensor_time", reading.timestamp)
                    .putLong("last_fetch_ms", System.currentTimeMillis())
                    .putString("last_error", "")
                    .apply();

            SecurePrefs.putSecret(this, "pending_2fa_token", "");

            if (!reading.timestamp.equals(previousSensorTime)) {
                appendHistory(prefs, reading);
            }

            showGlucoseNotification(reading);
            checkAlert(reading, prefs);
            updateService("LibreView-2FA bestätigt");
        } catch (LibreApiClient.TermsRequiredException e) {
            SecurePrefs.prefs(this).edit()
                    .putBoolean("two_factor_required", false)
                    .putBoolean("terms_required", true)
                    .putString("terms_step", e.getStepType())
                    .putString("last_error", e.getMessage())
                    .apply();
            updateService("Weiterer LibreView-Kontoschritt erforderlich");
        } catch (Exception e) {
            saveError(
                    "2FA-Bestätigung fehlgeschlagen: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
            );
            updateService("2FA-Bestätigung fehlgeschlagen");
        } finally {
            if (wl != null && wl.isHeld()) wl.release();
        }
    }

    private void resendTwoFactorSafely() {
        try {
            SharedPreferences prefs = SecurePrefs.prefs(this);
            prefs.edit()
                    .putBoolean("two_factor_required", false)
                    .putString("last_error", "")
                    .remove("pending_2fa_base_url")
                    .apply();

            SecurePrefs.putSecret(this, "pending_2fa_token", "");
            client = null;

            pollSafely();
        } catch (Exception e) {
            saveError(
                    "Neuer 2FA-Code konnte nicht angefordert werden: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
            );
        }
    }

    private void appendHistory(SharedPreferences prefs, LibreApiClient.Reading reading) {
        String raw = prefs.getString("history_values", "");
        String encoded = String.format(Locale.US, "%.3f", reading.value);
        String next = raw.isEmpty() ? encoded : raw + ";" + encoded;
        String[] parts = next.split(";");
        if (parts.length > 24) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = parts.length - 24; i < parts.length; i++) {
                if (trimmed.length() > 0) trimmed.append(';');
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
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(value)
                .setContentText("LibreView Cloud • Bericht aktualisiert" +
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
                .setSmallIcon(R.drawable.ic_launcher)
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
                .setSmallIcon(R.drawable.ic_launcher)
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
