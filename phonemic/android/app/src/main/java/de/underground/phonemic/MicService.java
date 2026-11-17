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
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
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
    public static volatile long packetsSent = 0L;
    public static volatile String error = "";
    public static volatile String destination = "";
    public static volatile int transportMode = 0;

    private volatile boolean requestedStop = false;
    private Thread thread;
    private volatile AudioRecord recorder;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "PhoneMic Mikrofonübertragung", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mikrofonübertragung an OBS über WLAN oder USB-Tethering");
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
        final int transport = intent.getIntExtra("transport", 0);
        transportMode = transport;
        gain = Math.max(0, Math.min(250, intent.getIntExtra("gain", 100)));
        muted = false;
        requestedStop = false;
        lastAck = 0;
        packetsSent = 0;
        error = "";
        destination = host + ":" + port;

        Intent open = new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(transport == 1 ? "PhoneMic sendet über USB" : "PhoneMic sendet über WLAN")
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
        thread = new Thread(() -> stream(host, port, token, audioMode, transport), "PhoneMic-Audio");
        thread.start();
        return START_NOT_STICKY;
    }

    private void stream(String host, int port, int token, int mode, int transport) {
        PowerManager.WakeLock cpuLock = null;
        WifiManager.WifiLock wifiLock = null;
        DatagramSocket socket = null;
        Thread ackThread = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            final InetAddress server = InetAddress.getByName(host);
            if (!(server instanceof Inet4Address))
                throw new IllegalArgumentException("Nur IPv4-Adressen erlaubt");

            socket = new DatagramSocket();
            socket.setSendBufferSize(256 * 1024);
            socket.setReceiveBufferSize(16 * 1024);
            socket.setSoTimeout(300);

            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneMic:AudioStream");
            cpuLock.acquire();

            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi != null && transport != 1) {
                    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                            "PhoneMic:WifiStream");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            } catch (RuntimeException ignored) {
                // Mic capture continues if a device refuses the optional Wi-Fi lock.
            }

            int source = mode == 1 ? MediaRecorder.AudioSource.UNPROCESSED
                    : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            if (source == MediaRecorder.AudioSource.UNPROCESSED) {
                AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
                if (!"true".equals(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)))
                    source = MediaRecorder.AudioSource.MIC;
            }
            int minimum = AudioRecord.getMinBufferSize(MicProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0)
                throw new IllegalStateException("48 kHz Mikrofon wird nicht unterstützt");
            recorder = new AudioRecord(source, MicProtocol.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, MicProtocol.PCM_BYTES * 12));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("AudioRecord konnte nicht initialisiert werden");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Mikrofonaufnahme fehlgeschlagen");

            final int session = new SecureRandom().nextInt();
            final DatagramSocket ackSocket = socket;
            ackThread = new Thread(() -> receiveAcks(ackSocket, server, port, token, session),
                    "PhoneMic-ACK");
            ackThread.setDaemon(true);
            ackThread.start();

            byte[] packetBytes = MicProtocol.newFrame(token, session);
            DatagramPacket outgoing = new DatagramPacket(packetBytes, packetBytes.length, server, port);
            int sequence = 0;

            while (!requestedStop) {
                int received = 0;
                while (received < MicProtocol.PCM_BYTES && !requestedStop) {
                    int count = recorder.read(packetBytes,
                            MicProtocol.HEADER_BYTES + received,
                            MicProtocol.PCM_BYTES - received, AudioRecord.READ_BLOCKING);
                    if (count < 0) {
                        if (requestedStop) break;
                        throw new IllegalStateException("Mikrofon-Lesefehler: " + count);
                    }
                    if (count == 0) continue;
                    received += count;
                }
                if (requestedStop) break;
                MicProtocol.setSequence(packetBytes, sequence++);
                MicProtocol.applyGain(packetBytes, gain, muted);
                socket.send(outgoing);
                packetsSent++;
            }

        } catch (Exception exc) {
            if (!requestedStop)
                error = exc.getMessage() == null ? "Übertragung unterbrochen" : exc.getMessage();
        } finally {
            if (socket != null) socket.close();
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
                recorder = null;
            }
            if (ackThread != null) {
                try { ackThread.join(400); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
            transmitting = false;
            stopSelf();
        }
    }

    private void receiveAcks(DatagramSocket socket, InetAddress server,
                             int port, int token, int session) {
        final byte[] bytes = new byte[64];
        DatagramPacket reply = new DatagramPacket(bytes, bytes.length);
        while (!requestedStop && !socket.isClosed()) {
            reply.setLength(bytes.length);
            try {
                socket.receive(reply);
                if (server.equals(reply.getAddress()) && reply.getPort() == port &&
                        MicProtocol.isAck(reply, token, session)) {
                    lastAck = SystemClock.elapsedRealtime();
                }
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException e) {
                break;
            } catch (Exception ignored) {
                if (socket.isClosed()) break;
            }
        }
    }

    private void stopStreaming() {
        requestedStop = true;
        AudioRecord audio = recorder;
        if (audio != null) {
            try { audio.stop(); } catch (Exception ignored) {}
        }
        Thread active = thread;
        if (active != null && active != Thread.currentThread()) {
            active.interrupt();
            try { active.join(1200); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
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
