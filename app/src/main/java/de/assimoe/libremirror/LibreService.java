package de.assimoe.libremirror;

import android.app.KeyguardManager;
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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LibreService extends Service {
    public static final String ACTION_REFRESH = "de.assimoe.libremirror.REFRESH";
    public static final String ACTION_STOP = "de.assimoe.libremirror.STOP";

    private static final String CHANNEL_LIVE = "libremirror_live";
    private static final String CHANNEL_ALERTS = "libremirror_alerts";

    private static final int NOTIFICATION_LIVE = 1001;
    private static final int NOTIFICATION_ALERT = 1002;
    private static final int NOTIFICATION_SYSTEM = 1003;

    private static final long MIN_INTERVAL_MS = 60_000L;
    private static final long MAX_BACKOFF_MS = 15L * 60L * 1000L;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> nextPollFuture;
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private LibreApiClient client;
    private int consecutiveFailures = 0;
    private TextToSpeech tts;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannels();
        startForeground(
                NOTIFICATION_LIVE,
                buildLiveNotification("LibreMirror", "Live-Dienst wird gestartet …", false)
        );

        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduleNext(0L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences prefs = SecurePrefs.prefs(this);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            prefs.edit().putBoolean("enabled", false).apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!prefs.getBoolean("enabled", false)) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            scheduleNext(0L);
        }

        return START_STICKY;
    }

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) return;

        long started = System.currentTimeMillis();
        PowerManager.WakeLock wakeLock = null;
        SharedPreferences prefs = SecurePrefs.prefs(this);
        long nextDelay = getBaseIntervalMs(prefs);

        incrementAttemptCounters(prefs);

        try {
            if (!prefs.getBoolean("enabled", false)) {
                stopSelf();
                return;
            }

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LibreMirror:sync");
            wakeLock.acquire(30_000L);

            if (!hasInternet()) {
                throw new OfflineException("Keine Internetverbindung");
            }

            String email = SecurePrefs.getSecret(this, "email");
            String password = SecurePrefs.getSecret(this, "password");

            if (email.isEmpty() || password.isEmpty()) {
                throw new LibreApiClient.UserVisibleException(
                        "Bitte den LibreLinkUp-Follower-Account einrichten."
                );
            }

            ensureClient(prefs);

            LibreApiClient.FetchResult result = client.fetch(email, password);
            saveSession(client.sessionState());
            saveReading(result);

            consecutiveFailures = 0;

            prefs.edit()
                    .putString("last_error", "")
                    .putString("cloud_status", "ONLINE")
                    .putString("cloud_status_text", "Abbott Cloud erreichbar")
                    .putLong("last_success_ms", System.currentTimeMillis())
                    .putLong("last_poll_duration_ms", System.currentTimeMillis() - started)
                    .putInt("sync_successes_total", prefs.getInt("sync_successes_total", 0) + 1)
                    .apply();

            boolean stale = System.currentTimeMillis() - result.current.timestampMs
                    > getStaleMinutes(prefs) * 60_000L;

            updateLiveNotification(result.current, stale, stale ? "Sensorwert veraltet" : "");
            checkAlerts(result, prefs, stale);

            nextDelay = getAdaptiveIntervalMs(prefs, result.current);
        } catch (OfflineException e) {
            consecutiveFailures++;
            prefs.edit()
                    .putString("cloud_status", "OFFLINE")
                    .putString("cloud_status_text", "Kein Internet")
                    .apply();
            saveError("Offline – letzter gespeicherter Wert wird weiter angezeigt.");
            nextDelay = calculateBackoff();
            updateNotificationFromCache("Offline");
            maybeSystemAlert("Offline", "Keine Internetverbindung", prefs);
            WearSync.pushEmpty(this, "Offline");
        } catch (LibreApiClient.RateLimitException e) {
            consecutiveFailures++;
            prefs.edit()
                    .putString("cloud_status", "RATE_LIMIT")
                    .putString("cloud_status_text", "Abbott begrenzt Anfragen")
                    .apply();
            nextDelay = Math.max(e.retryAfterMs, calculateBackoff());
            saveError(e.getMessage());
            updateNotificationFromCache("Rate-Limit");
            WearSync.pushEmpty(this, "Rate-Limit");
        } catch (LibreApiClient.UserVisibleException e) {
            consecutiveFailures++;
            prefs.edit()
                    .putString("cloud_status", "ACCOUNT")
                    .putString("cloud_status_text", "Konto/Freigabe prüfen")
                    .apply();
            nextDelay = calculateBackoff();
            saveError(e.getMessage());
            updateNotificationFromCache("Konto/Freigabe prüfen");
            WearSync.pushEmpty(this, "Konto prüfen");
        } catch (Exception e) {
            consecutiveFailures++;
            prefs.edit()
                    .putString("cloud_status", "CLOUD_ERROR")
                    .putString("cloud_status_text", "Cloud nicht erreichbar")
                    .apply();
            nextDelay = calculateBackoff();
            saveError("Abbott Cloud derzeit nicht erreichbar – Offline-Modus aktiv.");
            updateNotificationFromCache("Cloud nicht erreichbar");
            maybeSystemAlert("Cloud-Ausfall", "LibreMirror nutzt den letzten gespeicherten Wert.", prefs);
            WearSync.pushEmpty(this, "Cloud offline");
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();

            prefs.edit()
                    .putLong("last_poll_duration_ms", System.currentTimeMillis() - started)
                    .apply();

            polling.set(false);

            if (scheduler != null
                    && !scheduler.isShutdown()
                    && SecurePrefs.prefs(this).getBoolean("enabled", false)) {
                scheduleNext(nextDelay);
            }
        }
    }

    private void ensureClient(SharedPreferences prefs) {
        if (client != null) return;

        client = new LibreApiClient(
                prefs.getString("region", "AUTO"),
                prefs.getString("session_base_url", ""),
                SecurePrefs.getSecret(this, "session_token"),
                prefs.getLong("session_expires_ms", 0L),
                prefs.getString("session_account_hash", ""),
                prefs.getString("session_patient_id", "")
        );
    }

    private void saveSession(LibreApiClient.SessionState session) {
        try {
            SecurePrefs.putSecret(this, "session_token", session.token);
        } catch (Exception ignored) {
        }

        SecurePrefs.prefs(this).edit()
                .putString("session_base_url", session.baseUrl)
                .putLong("session_expires_ms", session.expiresMs)
                .putString("session_account_hash", session.accountHash)
                .putString("session_patient_id", session.patientId)
                .apply();
    }

    private void saveReading(LibreApiClient.FetchResult result) {
        SharedPreferences prefs = SecurePrefs.prefs(this);

        StringBuilder values = new StringBuilder();
        StringBuilder points = new StringBuilder();

        for (LibreApiClient.Reading reading : result.history) {
            if (values.length() > 0) values.append(';');
            values.append(String.format(Locale.US, "%.1f", reading.mgdl));

            if (points.length() > 0) points.append(';');
            points.append(reading.timestampMs)
                    .append(',')
                    .append(String.format(Locale.US, "%.1f", reading.mgdl));
        }

        prefs.edit()
                .putString("last_value", result.current.displayValue())
                .putInt("last_trend", result.current.trend)
                .putLong("last_sensor_ms", result.current.timestampMs)
                .putString("patient_name", result.patientName)
                .putString("history_values", values.toString())
                .putString("history_points", points.toString())
                .apply();

        HistoryDatabase historyDb = new HistoryDatabase(this);
        historyDb.insertAll(result.history);
        historyDb.insert(
                result.current.timestampMs,
                result.current.mgdl,
                result.current.trend
        );

        LibreMirrorWidgetProvider.updateAll(this);

        WearSync.push(
                this,
                result.current.displayValue(),
                result.current.trend,
                result.current.timestampMs,
                "ONLINE",
                prefs.getBoolean("private_mode", false)
        );
    }

    private void checkAlerts(
            LibreApiClient.FetchResult result,
            SharedPreferences prefs,
            boolean stale
    ) {
        LibreApiClient.Reading reading = result.current;
        double low = parseDouble(prefs.getString("low", "70"), 70.0);
        double high = parseDouble(prefs.getString("high", "180"), 180.0);
        double criticalLow = parseDouble(prefs.getString("critical_low", "55"), 55.0);

        String state = reading.mgdl <= criticalLow
                ? "CRITICAL_LOW"
                : reading.mgdl < low
                ? "LOW"
                : reading.mgdl > high
                ? "HIGH"
                : "NORMAL";

        String previous = prefs.getString("alert_state", "NORMAL");
        long lastAlert = prefs.getLong("last_alert_ms", 0L);
        long repeatMs = Math.max(5, prefs.getInt("alert_repeat_min", 30)) * 60_000L;
        long now = System.currentTimeMillis();

        boolean quiet = isQuietHours(prefs);
        boolean critical = "CRITICAL_LOW".equals(state);
        boolean repeat = !"NORMAL".equals(state)
                && state.equals(previous)
                && now - lastAlert >= repeatMs;
        boolean changed = !"NORMAL".equals(state) && !state.equals(previous);

        prefs.edit().putString("alert_state", state).apply();

        if ((changed || repeat) && (!quiet || critical)) {
            String title;
            if ("CRITICAL_LOW".equals(state)) title = "Glukose sehr niedrig";
            else if ("LOW".equals(state)) title = "Glukose niedrig";
            else title = "Glukose hoch";

            String body = reading.displayValue()
                    + " mg/dL "
                    + LibreApiClient.arrow(reading.trend)
                    + " • "
                    + LibreApiClient.trendLabel(reading.trend);

            sendAlertNotification(title, body, NOTIFICATION_ALERT);
            prefs.edit().putLong("last_alert_ms", now).apply();

            if (shouldSpeakInCar(prefs)) {
                speak(title + ". " + reading.displayValue() + " Milligramm pro Deziliter.");
            }
        }

        if (prefs.getBoolean("trend_alerts", true) && !quiet) {
            double rate = ratePerMinute(result.history);
            long lastTrendAlert = prefs.getLong("last_trend_alert_ms", 0L);

            if (Math.abs(rate) >= 2.5 && now - lastTrendAlert >= repeatMs) {
                String title = rate < 0 ? "Glukose fällt schnell" : "Glukose steigt schnell";
                String body = reading.displayValue()
                        + " mg/dL • etwa "
                        + String.format(Locale.getDefault(), "%.1f", Math.abs(rate))
                        + " mg/dL/min";

                sendAlertNotification(title, body, NOTIFICATION_SYSTEM);
                prefs.edit().putLong("last_trend_alert_ms", now).apply();

                if (shouldSpeakInCar(prefs)) speak(title + ".");
            }
        }

        if (stale && prefs.getBoolean("stale_alerts", true) && !quiet) {
            long lastStaleAlert = prefs.getLong("last_stale_alert_ms", 0L);

            if (now - lastStaleAlert >= repeatMs) {
                sendAlertNotification(
                        "Glukosewert veraltet",
                        "Seit " + ageText(now - reading.timestampMs) + " kein neuer Sensorwert.",
                        NOTIFICATION_SYSTEM
                );
                prefs.edit().putLong("last_stale_alert_ms", now).apply();
            }
        }
    }

    private double ratePerMinute(List<LibreApiClient.Reading> readings) {
        if (readings == null || readings.size() < 2) return 0.0;

        LibreApiClient.Reading latest = readings.get(readings.size() - 1);
        LibreApiClient.Reading previous = readings.get(readings.size() - 2);

        double minutes = (latest.timestampMs - previous.timestampMs) / 60_000.0;
        if (minutes <= 0.0 || minutes > 20.0) return 0.0;

        return (latest.mgdl - previous.mgdl) / minutes;
    }

    private void maybeSystemAlert(String title, String body, SharedPreferences prefs) {
        if (!prefs.getBoolean("cloud_alerts", true)) return;
        if (isQuietHours(prefs)) return;

        long now = System.currentTimeMillis();
        long repeatMs = Math.max(10, prefs.getInt("alert_repeat_min", 30)) * 60_000L;
        long last = prefs.getLong("last_cloud_alert_ms", 0L);

        if (now - last < repeatMs) return;

        sendAlertNotification(title, body, NOTIFICATION_SYSTEM);
        prefs.edit().putLong("last_cloud_alert_ms", now).apply();
    }

    private void sendAlertNotification(String title, String body, int id) {
        SharedPreferences prefs = SecurePrefs.prefs(this);
        boolean privateMode = prefs.getBoolean("private_mode", false);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(privateMode ? "LibreMirror Warnung" : title)
                .setContentText(privateMode ? "Details nach Entsperren anzeigen" : body)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(mainPendingIntent());

        if (privateMode) {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE);
            Notification publicVersion = new Notification.Builder(this, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("LibreMirror")
                    .setContentText("Warnung vorhanden")
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .build();
            builder.setPublicVersion(publicVersion);
        } else {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(id, builder.build());
    }

    private void updateLiveNotification(
            LibreApiClient.Reading reading,
            boolean stale,
            String suffix
    ) {
        SharedPreferences prefs = SecurePrefs.prefs(this);
        boolean privateMode = prefs.getBoolean("private_mode", false);
        boolean locked = isDeviceLocked();

        long ageMs = Math.max(0L, System.currentTimeMillis() - reading.timestampMs);
        stale = stale || ageMs > getStaleMinutes(prefs) * 60_000L;

        String title;
        String text;

        if (privateMode && locked) {
            title = "LibreMirror";
            text = "Wert im Privatmodus ausgeblendet";
        } else {
            title = reading.displayValue()
                    + " mg/dL "
                    + LibreApiClient.arrow(reading.trend);

            text = stale
                    ? "Wert ist nicht mehr aktuell"
                    : "LibreLinkUp • vor " + ageText(ageMs);

            if (suffix != null && !suffix.isEmpty()) text += " • " + suffix;
        }

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                NOTIFICATION_LIVE,
                buildLiveNotification(title, text, true)
        );
    }

    private void updateNotificationFromCache(String suffix) {
        SharedPreferences prefs = SecurePrefs.prefs(this);
        String value = prefs.getString("last_value", "");
        int trend = prefs.getInt("last_trend", 0);
        long sensorMs = prefs.getLong("last_sensor_ms", 0L);

        if (!value.isEmpty() && sensorMs > 0L) {
            LibreApiClient.Reading cached = new LibreApiClient.Reading(
                    parseDouble(value, 0.0),
                    trend,
                    "",
                    sensorMs
            );
            updateLiveNotification(cached, true, suffix);
            return;
        }

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(
                NOTIFICATION_LIVE,
                buildLiveNotification("LibreMirror", suffix, false)
        );
    }

    private Notification buildLiveNotification(String title, String text, boolean ongoing) {
        SharedPreferences prefs = SecurePrefs.prefs(this);
        boolean privateMode = prefs.getBoolean("private_mode", false);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(false)
                .setContentIntent(mainPendingIntent());

        if (privateMode) {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE);
            builder.setPublicVersion(
                    new Notification.Builder(this, CHANNEL_LIVE)
                            .setSmallIcon(R.drawable.ic_notification)
                            .setContentTitle("LibreMirror")
                            .setContentText("Live-Dienst aktiv")
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .build()
            );
        } else {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        }

        return builder.build();
    }

    private PendingIntent mainPendingIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        return PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void createNotificationChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel live = new NotificationChannel(
                CHANNEL_LIVE,
                "Live-Glukose",
                NotificationManager.IMPORTANCE_LOW
        );
        live.setDescription("Laufender Glukosewert für Handy, Sperrbildschirm und Smartwatch.");
        live.setShowBadge(false);
        live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS,
                "Glukose-Warnungen",
                NotificationManager.IMPORTANCE_HIGH
        );
        alerts.setDescription("High/Low-, Trend-, Offline- und Cloud-Warnungen.");
        alerts.enableVibration(true);
        alerts.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        manager.createNotificationChannel(live);
        manager.createNotificationChannel(alerts);
    }

    private boolean shouldSpeakInCar(SharedPreferences prefs) {
        if (!prefs.getBoolean("auto_mode_enabled", false)) return false;
        if (!prefs.getBoolean("car_voice", true)) return false;
        return isCarMode() || prefs.getBoolean("auto_mode_manual", false);
    }

    private boolean isCarMode() {
        UiModeManager manager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return manager != null
                && manager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
    }

    private boolean isDeviceLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isDeviceLocked();
    }

    private void speak(String text) {
        if (tts == null) {
            tts = new TextToSpeech(
                    getApplicationContext(),
                    status -> {
                        if (status == TextToSpeech.SUCCESS) {
                            tts.setLanguage(Locale.GERMAN);
                            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "libremirror-alert");
                        }
                    }
            );
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "libremirror-alert");
        }
    }

    private boolean hasInternet() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private long getAdaptiveIntervalMs(
            SharedPreferences prefs,
            LibreApiClient.Reading reading
    ) {
        long base = getBaseIntervalMs(prefs);

        if (!prefs.getBoolean("adaptive_sync", true)) return base;

        double low = parseDouble(prefs.getString("low", "70"), 70.0);
        double high = parseDouble(prefs.getString("high", "180"), 180.0);
        double margin = 15.0;

        boolean dynamicTrend = reading.trend == 1
                || reading.trend == 2
                || reading.trend == 4
                || reading.trend == 5;

        boolean nearLimit = reading.mgdl <= low + margin
                || reading.mgdl >= high - margin;

        if (dynamicTrend || nearLimit) return MIN_INTERVAL_MS;
        return base;
    }

    private long getBaseIntervalMs(SharedPreferences prefs) {
        int minutes = prefs.getInt("sync_interval_min", 3);
        if (minutes < 1) minutes = 1;
        if (minutes > 30) minutes = 30;
        return minutes * 60_000L;
    }

    private int getStaleMinutes(SharedPreferences prefs) {
        int value = prefs.getInt("stale_alert_min", 10);
        if (value < 5) value = 5;
        if (value > 60) value = 60;
        return value;
    }

    private long calculateBackoff() {
        int exponent = Math.min(Math.max(consecutiveFailures - 1, 0), 4);
        long delay = Math.max(MIN_INTERVAL_MS, getBaseIntervalMs(SecurePrefs.prefs(this)))
                * (1L << exponent);
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private boolean isQuietHours(SharedPreferences prefs) {
        if (!prefs.getBoolean("quiet_hours_enabled", false)) return false;

        int start = parseMinutesOfDay(prefs.getString("quiet_start", "22:00"), 22 * 60);
        int end = parseMinutesOfDay(prefs.getString("quiet_end", "07:00"), 7 * 60);

        Calendar now = Calendar.getInstance();
        int current = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        if (start == end) return true;
        if (start < end) return current >= start && current < end;
        return current >= start || current < end;
    }

    private int parseMinutesOfDay(String text, int fallback) {
        if (text == null) return fallback;
        String[] parts = text.trim().split(":");
        if (parts.length != 2) return fallback;

        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return fallback;
            return hour * 60 + minute;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private synchronized void scheduleNext(long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) return;

        if (nextPollFuture != null && !nextPollFuture.isDone()) {
            nextPollFuture.cancel(false);
        }

        nextPollFuture = scheduler.schedule(
                this::pollSafely,
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
    }

    private void saveError(String message) {
        SecurePrefs.prefs(this).edit()
                .putString("last_error", message == null ? "Unbekannter Fehler." : message)
                .putLong("last_error_ms", System.currentTimeMillis())
                .apply();

        LibreMirrorWidgetProvider.updateAll(this);
    }

    private void incrementAttemptCounters(SharedPreferences prefs) {
        long today = StatsCalculator.startOfDay(System.currentTimeMillis(), 0);
        long savedDay = prefs.getLong("counter_day_ms", 0L);
        int attempts = prefs.getInt("sync_attempts_today", 0);

        if (savedDay != today) {
            savedDay = today;
            attempts = 0;
        }

        prefs.edit()
                .putLong("counter_day_ms", savedDay)
                .putInt("sync_attempts_today", attempts + 1)
                .apply();
    }

    private static String ageText(long ageMs) {
        long seconds = Math.max(0L, ageMs / 1000L);
        if (seconds < 60L) return "weniger als 1 Min.";
        long minutes = seconds / 60L;
        if (minutes == 1L) return "1 Min.";
        return minutes + " Min.";
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(value.replace(',', '.'));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    @Override
    public void onDestroy() {
        if (nextPollFuture != null) {
            nextPollFuture.cancel(false);
            nextPollFuture = null;
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        client = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class OfflineException extends Exception {
        OfflineException(String message) {
            super(message);
        }
    }
}
