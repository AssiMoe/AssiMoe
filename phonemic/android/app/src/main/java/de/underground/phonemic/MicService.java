package de.underground.phonemic;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;

public final class MicService extends Service {
    public static final String ACTION_START = "de.underground.phonemic.START";
    public static final String ACTION_STOP = "de.underground.phonemic.STOP";
    public static final String ACTION_GAIN = "de.underground.phonemic.GAIN";
    public static final String ACTION_MUTE = "de.underground.phonemic.MUTE";
    private static final String CHANNEL_ID = "phonemic-stream";
    private static final int NOTIFICATION_ID = 1401;

    public static volatile boolean transmitting = false;
    public static volatile boolean muted = false;
    public static volatile int gain = 100;
    public static volatile long lastAck = 0L;
    public static volatile String error = "";
    public static volatile String destination = "";

    private volatile boolean requestedStop = false;
    private Thread thread;
    private AudioRecord recorder;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "PhoneMic Mikrofonübertragung", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mikrofonübertragung an OBS im lokalen WLAN");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_GAIN.equals(action)) {
            gain = Math.max(0, Math.min(250, intent.getIntExtra("gain", 100)));
            return START_NOT_STICKY;
        }
        if (ACTION_MUTE.equals(action)) {
            muted = intent.getBooleanExtra("mute", false);
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopStreaming();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || transmitting) return START_NOT_STICKY;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error = "Mikrofonberechtigung fehlt";
            stopSelf();
            return START_NOT_STICKY;
        }

        final String host = intent.getStringExtra("host");
        final int port = intent.getIntExtra("port", 49888);
        final int token = intent.getIntExtra("token", 24681357);
        final int audioMode = intent.getIntExtra("mode", 0);
        gain = Math.max(0, Math.min(250, intent.getIntExtra("gain", 100)));
        muted = false;
        requestedStop = false;
        lastAck = 0;
        error = "";
        destination = host + ":" + port;

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("PhoneMic sendet")
                .setContentText("Mikrofon an " + destination)
                .setContentIntent(tap)
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIFICATION_ID, note);
        } catch (RuntimeException exc) {
            error = "Mikrofon-Dienst konnte nicht starten: " + exc.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        transmitting = true;
        thread = new Thread(() -> stream(host, port, token, audioMode), "PhoneMic-Audio");
        thread.start();
        return START_NOT_STICKY;
    }

    private void stream(String host, int port, int token, int mode) {
        PowerManager.WakeLock wakeLock = null;
        DatagramSocket socket = null;
        try {
            InetAddress server = InetAddress.getByName(host);
            if (!(server instanceof Inet4Address)) throw new IllegalArgumentException("Nur IPv4-Adressen");
            socket = new DatagramSocket();
            socket.setSoTimeout(1);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMic:AudioStream");
            wakeLock.acquire(4 * 60 * 60 * 1000L);

            int source = mode == 1 ? MediaRecorder.AudioSource.UNPROCESSED
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            if (source == MediaRecorder.AudioSource.UNPROCESSED) {
                AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (!"true".equals(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)))
                    source = MediaRecorder.AudioSource.MIC;
            }
            int minimum = AudioRecord.getMinBufferSize(MicProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("48 kHz-Mikrofon nicht verfügbar");
            recorder = new AudioRecord(source, MicProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, MicProtocol.PCM_BYTES * 8));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord konnte nicht initialisiert werden");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Mikrofon kann nicht aufgenommen werden");

            int session = new SecureRandom().nextInt();
            byte[] packetBytes = MicProtocol.newFrame(token, session);
            DatagramPacket outgoing = new DatagramPacket(packetBytes, packetBytes.length, server, port);
            byte[] ackBuffer = new byte[64];
            DatagramPacket ack = new DatagramPacket(ackBuffer, ackBuffer.length);
            int sequence = 0;
            while (!requestedStop && !Thread.currentThread().isInterrupted()) {
                int received = 0;
                while (received < MicProtocol.PCM_BYTES && !requestedStop) {
                    int count = recorder.read(packetBytes,
                            MicProtocol.HEADER_BYTES + received,
                            MicProtocol.PCM_BYTES - received, AudioRecord.READ_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Mikrofonfehler: " + count);
                    received += count;
                }
                if (requestedStop) break;
                MicProtocol.setSequence(packetBytes, sequence++);
                MicProtocol.applyGain(packetBytes, gain, muted);
                socket.send(outgoing);
                if (sequence % 5 == 0) {
                    ack.setLength(ackBuffer.length);
                    try {
                        socket.receive(ack);
                        if (server.equals(ack.getAddress()) && MicProtocol.isAck(ack, token, session))
                            lastAck = android.os.SystemClock.elapsedRealtime();
                    } catch (SocketTimeoutException ignored) {
                    }
                }
            }
        } catch (Exception exc) {
            if (!requestedStop) error = exc.getMessage() == null ? "Audiofehler" : exc.getMessage();
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
                recorder = null;
            }
            if (socket != null) socket.close();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            transmitting = false;
            stopSelf();
        }
    }

    private void stopStreaming() {
        requestedStop = true;
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try { t.join(800); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        transmitting = false;
    }

    @Override
    public void onDestroy() {
        stopStreaming();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
