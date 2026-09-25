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
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.assimoe.libremirror.core.SyncStateStore;
import de.assimoe.libremirror.core.SyncStatus;
import de.assimoe.libremirror.core.SyncStatusResolver;
import de.assimoe.libremirror.data.GlucoseRepository;

public class LibreService extends Service {
    public static final String ACTION_REFRESH = "de.assimoe.libremirror.REFRESH";
    public static final String ACTION_STOP = "de.assimoe.libremirror.STOP";

    private static final String CHANNEL_LIVE = "libremirror_live";
    private static final String CHANNEL_ALERTS = "libremirror_alerts";
    private static final int NOTIFICATION_LIVE = 1001;
    private static final int NOTIFICATION_ALERT = 1002;

    private static final long MIN_INTERVAL_MS = 60_000L;
    private static final long MAX_BACKOFF_MS = 15L * 60L * 1000L;
    private static final long ALERT_REPEAT_MS = 30L * 60L * 1000L;
    private static final long STALE_AFTER_MS = 5L * 60L * 1000L;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> nextPollFuture;
    private final AtomicBoolean polling = new AtomicBoolean(false);

    private LibreApiClient client;
    private GlucoseRepository repository;
    private int consecutiveFailures = 0;
    private TextToSpeech tts;

    @Override
    public void onCreate() {
        super.onCreate();

        repository = GlucoseRepository.get(this);

        createNotificationChannels();
        startForeground(
                NOTIFICATION_LIVE,
                buildLiveNotification(
                        "LibreMirror",
                        "Live-Dienst wird gestartet …",
                        false
                )
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

        if (intent != null
                && ACTION_REFRESH.equals(intent.getAction())
                && scheduler != null) {
            scheduleNext(0L);
        }

        return START_STICKY;
    }

    private void pollSafely() {
        if (!polling.compareAndSet(false, true)) return;

        long startedMs = System.currentTimeMillis();
        PowerManager.WakeLock wakeLock = null;
        SharedPreferences prefs = SecurePrefs.prefs(this);
        long nextDelay = getSyncIntervalMs(prefs);

        try {
            if (!prefs.getBoolean("enabled", false)) {
                stopSelf();
                return;
            }

            repository.migrateLegacyDataIfNeeded();

            PowerManager powerManager =
                    (PowerManager) getSystemService(POWER_SERVICE);

            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LibreMirror:sync"
            );
            wakeLock.acquire(30_000L);

            String email = SecurePrefs.getSecret(this, "email");
            String password = SecurePrefs.getSecret(this, "password");

            if (email.isEmpty() || password.isEmpty()) {
                throw new LibreApiClient.AuthenticationException(
                        "Bitte den LibreLinkUp-Follower-Account einrichten."
                );
            }

            ensureClient(prefs);

            LibreApiClient.FetchResult result =
                    client.fetch(email, password);

            saveSession(client.sessionState());
            repository.persistFetchResult(result);

            long now = System.currentTimeMillis();
            long ageMs = Math.max(
                    0L,
                    now - result.current.timestampMs
            );

            SyncStatus status = ageMs > STALE_AFTER_MS
                    ? SyncStatus.SENSOR_STALE
                    : SyncStatus.ONLINE_OK;

            String statusMessage = status == SyncStatus.SENSOR_STALE
                    ? "Der letzte Sensorwert ist älter als 5 Minuten."
                    : "";

            SyncStateStore.set(this, status, statusMessage);

            prefs.edit()
                    .putLong("last_success_ms", now)
                    .apply();

            repository.recordSyncEvent(
                    status,
                    statusMessage,
                    now - startedMs
            );

            consecutiveFailures = 0;

            updateLiveNotification(
                    result.current,
                    status == SyncStatus.SENSOR_STALE,
                    ""
            );

            if (status == SyncStatus.ONLINE_OK) {
                checkThresholdAlert(result.current, prefs);
            }
        } catch (Exception error) {
            consecutiveFailures++;

            SyncStatus status =
                    SyncStatusResolver.resolve(this, error);

            String message = messageFor(status, error);

            SyncStateStore.set(this, status, message);

            try {
                repository.recordSyncEvent(
                        status,
                        message,
                        System.currentTimeMillis() - startedMs
                );
            } catch (Exception ignored) {
            }

            if (error instanceof LibreApiClient.RateLimitException) {
                long retryAfter =
                        ((LibreApiClient.RateLimitException) error)
                                .retryAfterMs;

                nextDelay = Math.max(
                        retryAfter,
                        calculateBackoff()
                );
            } else {
                nextDelay = calculateBackoff();
            }

            LibreMirrorWidgetProvider.updateAll(this);
            updateNotificationFromCache(
                    status.userLabel() + " – neuer Versuch automatisch"
            );
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }

            polling.set(false);

            if (scheduler != null
                    && !scheduler.isShutdown()
                    && SecurePrefs.prefs(this)
                    .getBoolean("enabled", false)) {
                scheduleNext(nextDelay);
            }
        }
    }

    private String messageFor(
            SyncStatus status,
            Exception error
    ) {
        String original = error == null
                ? ""
                : error.getMessage();

        if (original != null && !original.trim().isEmpty()) {
            return original;
        }

        switch (status) {
            case NO_INTERNET:
                return "Keine Internetverbindung.";
            case ABBOTT_UNREACHABLE:
                return "Abbott Cloud ist vorübergehend nicht erreichbar.";
            case AUTH_EXPIRED:
                return "LibreLinkUp-Anmeldung muss erneuert werden.";
            case NO_CONNECTION:
                return "Keine aktive LibreLinkUp-Freigabe gefunden.";
            case SENSOR_STALE:
                return "Der letzte Sensorwert ist veraltet.";
            case RATE_LIMIT:
                return "LibreLinkUp begrenzt die Abfragen vorübergehend.";
            case UNKNOWN_ERROR:
            default:
                return "Verbindungsfehler. LibreMirror versucht es automatisch erneut.";
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
            SecurePrefs.putSecret(
                    this,
                    "session_token",
                    session.token
            );
        } catch (Exception ignored) {
        }

        SecurePrefs.prefs(this).edit()
                .putString("session_base_url", session.baseUrl)
                .putLong("session_expires_ms", session.expiresMs)
                .putString(
                        "session_account_hash",
                        session.accountHash
                )
                .putString(
                        "session_patient_id",
                        session.patientId
                )
                .apply();
    }

    private long calculateBackoff() {
        int exponent = Math.min(
                Math.max(consecutiveFailures - 1, 0),
                4
        );

        long base = getSyncIntervalMs(
                SecurePrefs.prefs(this)
        );

        long delay =
                Math.max(MIN_INTERVAL_MS, base)
                        * (1L << exponent);

        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private long getSyncIntervalMs(SharedPreferences prefs) {
        int minutes = prefs.getInt(
                "sync_interval_min",
                1
        );

        if (minutes < 1) minutes = 1;
        if (minutes > 30) minutes = 30;

        return minutes * 60_000L;
    }

    private synchronized void scheduleNext(long delayMs) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }

        if (nextPollFuture != null
                && !nextPollFuture.isDone()) {
            nextPollFuture.cancel(false);
        }

        nextPollFuture = scheduler.schedule(
                this::pollSafely,
                Math.max(0L, delayMs),
                TimeUnit.MILLISECONDS
        );
    }

    private void updateLiveNotification(
            LibreApiClient.Reading reading,
            boolean stale,
            String suffix
    ) {
        long ageMs = Math.max(
                0L,
                System.currentTimeMillis()
                        - reading.timestampMs
        );

        stale = stale || ageMs > STALE_AFTER_MS;

        String title = reading.displayValue()
                + " mg/dL "
                + LibreApiClient.arrow(reading.trend);

        String text = stale
                ? "Wert ist nicht mehr aktuell"
                : "LibreLinkUp • vor " + ageText(ageMs);

        if (suffix != null && !suffix.isEmpty()) {
            text += " • " + suffix;
        }

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        manager.notify(
                NOTIFICATION_LIVE,
                buildLiveNotification(title, text, true)
        );
    }

    private void updateNotificationFromCache(String suffix) {
        SharedPreferences prefs =
                SecurePrefs.prefs(this);

        String value =
                prefs.getString("last_value", "");

        int trend =
                prefs.getInt("last_trend", 0);

        long sensorMs =
                prefs.getLong("last_sensor_ms", 0L);

        if (!value.isEmpty() && sensorMs > 0L) {
            LibreApiClient.Reading cached =
                    new LibreApiClient.Reading(
                            parseDouble(value, 0.0),
                            trend,
                            "",
                            sensorMs
                    );

            updateLiveNotification(
                    cached,
                    true,
                    suffix
            );
            return;
        }

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        manager.notify(
                NOTIFICATION_LIVE,
                buildLiveNotification(
                        "LibreMirror",
                        suffix,
                        false
                )
        );
    }

    private void checkThresholdAlert(
            LibreApiClient.Reading reading,
            SharedPreferences prefs
    ) {
        double low = parseDouble(
                prefs.getString("low", "70"),
                70.0
        );

        double high = parseDouble(
                prefs.getString("high", "180"),
                180.0
        );

        String state = reading.mgdl < low
                ? "LOW"
                : reading.mgdl > high
                ? "HIGH"
                : "NORMAL";

        String previous =
                prefs.getString(
                        "alert_state",
                        "NORMAL"
                );

        long lastAlert =
                prefs.getLong(
                        "last_alert_ms",
                        0L
                );

        long now = System.currentTimeMillis();

        boolean repeat =
                !"NORMAL".equals(state)
                        && state.equals(previous)
                        && now - lastAlert
                        >= ALERT_REPEAT_MS;

        boolean newAlert =
                !"NORMAL".equals(state)
                        && !state.equals(previous);

        prefs.edit()
                .putString("alert_state", state)
                .apply();

        if (!newAlert && !repeat) return;

        String title = "LOW".equals(state)
                ? "Glukose niedrig"
                : "Glukose hoch";

        String body =
                reading.displayValue()
                        + " mg/dL "
                        + LibreApiClient.arrow(
                        reading.trend
                );

        Notification notification =
                new Notification.Builder(
                        this,
                        CHANNEL_ALERTS
                )
                        .setSmallIcon(
                                R.drawable.ic_notification
                        )
                        .setContentTitle(title)
                        .setContentText(body)
                        .setCategory(
                                Notification.CATEGORY_ALARM
                        )
                        .setVisibility(
                                Notification.VISIBILITY_PUBLIC
                        )
                        .setPriority(
                                Notification.PRIORITY_HIGH
                        )
                        .setAutoCancel(true)
                        .setContentIntent(
                                mainPendingIntent()
                        )
                        .build();

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        manager.notify(
                NOTIFICATION_ALERT,
                notification
        );

        prefs.edit()
                .putLong("last_alert_ms", now)
                .apply();

        if (prefs.getBoolean("car_voice", true)
                && isCarMode()) {
            speak(
                    title
                            + ". "
                            + reading.displayValue()
                            + " Milligramm pro Deziliter."
            );
        }
    }

    private Notification buildLiveNotification(
            String title,
            String text,
            boolean ongoing
    ) {
        return new Notification.Builder(
                this,
                CHANNEL_LIVE
        )
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(
                        Notification.VISIBILITY_PUBLIC
                )
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(false)
                .setContentIntent(mainPendingIntent())
                .build();
    }

    private PendingIntent mainPendingIntent() {
        Intent open =
                new Intent(this, MainActivity.class);

        open.setFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        return PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void createNotificationChannels() {
        NotificationManager manager =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel live =
                new NotificationChannel(
                        CHANNEL_LIVE,
                        "Live-Glukose",
                        NotificationManager.IMPORTANCE_LOW
                );

        live.setDescription(
                "Laufender Glukosewert für Handy, "
                        + "Sperrbildschirm und Smartwatch."
        );

        live.setShowBadge(false);
        live.setLockscreenVisibility(
                Notification.VISIBILITY_PUBLIC
        );

        NotificationChannel alerts =
                new NotificationChannel(
                        CHANNEL_ALERTS,
                        "Glukose-Warnungen",
                        NotificationManager.IMPORTANCE_HIGH
                );

        alerts.setDescription(
                "Sichtbare Warnungen bei Über- oder "
                        + "Unterschreitung deiner Grenzwerte."
        );

        alerts.enableVibration(true);
        alerts.setLockscreenVisibility(
                Notification.VISIBILITY_PUBLIC
        );

        manager.createNotificationChannel(live);
        manager.createNotificationChannel(alerts);
    }

    private boolean isCarMode() {
        UiModeManager manager =
                (UiModeManager)
                        getSystemService(
                                Context.UI_MODE_SERVICE
                        );

        return manager != null
                && manager.getCurrentModeType()
                == Configuration.UI_MODE_TYPE_CAR;
    }

    private void speak(String text) {
        if (tts == null) {
            tts = new TextToSpeech(
                    getApplicationContext(),
                    status -> {
                        if (status
                                == TextToSpeech.SUCCESS) {
                            tts.setLanguage(Locale.GERMAN);
                            tts.speak(
                                    text,
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "libremirror-alert"
                            );
                        }
                    }
            );
        } else {
            tts.speak(
                    text,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "libremirror-alert"
            );
        }
    }

    private static String ageText(long ageMs) {
        long seconds =
                Math.max(0L, ageMs / 1000L);

        if (seconds < 60L) {
            return "weniger als 1 Min.";
        }

        long minutes = seconds / 60L;

        if (minutes == 1L) {
            return "1 Min.";
        }

        return minutes + " Min.";
    }

    private static double parseDouble(
            String value,
            double fallback
    ) {
        if (value == null) return fallback;

        try {
            return Double.parseDouble(
                    value.replace(',', '.')
            );
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
}
