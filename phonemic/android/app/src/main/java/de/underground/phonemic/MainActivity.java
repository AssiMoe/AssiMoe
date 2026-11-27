package de.underground.phonemic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16,21,33);
    private static final int PANEL = Color.rgb(27,35,51);
    private static final int TEXT = Color.rgb(237,244,250);
    private static final int MUTED = Color.rgb(161,176,194);
    private static final int ACCENT = Color.rgb(81,212,172);
    private static final int YELLOW = Color.rgb(233,190,104);
    private static final int RED = Color.rgb(248,141,125);
    private EditText host, port, code;
    private TextView status, details, gainLabel, transportGuide, addressLabel, usbDiagnostics;
    private Button startButton, muteButton, usbSettingsButton, usbSearchButton;
    private Spinner mode, transport;
    private int selectedTransport = -1;
    private SeekBar gain;
    private SharedPreferences prefs;
    private boolean permissionPending;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 650); }
    };

    private int dp(float n) {
        return (int)(n * getResources().getDisplayMetrics().density + 0.5f);
    }
    private GradientDrawable background(int color, int corner) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(corner));
        return d;
    }
    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2);
        p.topMargin = dp(top);
        return p;
    }
    private TextView label(String txt, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(txt); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private EditText input(String hint, String value, boolean numeric) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint); e.setText(value);
        e.setTextSize(16); e.setTextColor(TEXT); e.setHintTextColor(MUTED);
        e.setBackground(background(PANEL,12));
        e.setPadding(dp(14),dp(13),dp(14),dp(13));
        e.setSelectAllOnFocus(true);
        e.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER :
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }
    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text); b.setTextSize(16);
        b.setTextColor(BG); b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setBackground(background(color,12));
        b.setPadding(dp(10),dp(12),dp(10),dp(12));
        return b;
    }
    private void addInput(LinearLayout root, String heading, EditText input) {
        root.addView(label(heading,13,MUTED,false),spaced(17));
        root.addView(input,spaced(7));
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("phonemic", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(28),dp(22),dp(32));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(label("PhoneMic 1.3",32,TEXT,true));
        root.addView(label("Handy-Mikrofon direkt in OBS Studio",15,MUTED,false),spaced(4));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18),dp(18),dp(18),dp(18));
        box.setBackground(background(PANEL,15));
        root.addView(box,spaced(22));
        status = label("Bereit",21,ACCENT,true);
        details = label("WLAN oder USB-Kabel zum OBS-PC verwenden.",13,MUTED,false);
        box.addView(status);
        box.addView(details,spaced(7));
        root.addView(label("Verbindung",13,MUTED,false),spaced(20));
        transport = new Spinner(this);
        ArrayAdapter<String> transportAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"WLAN", "USB-Kabel (Tethering)"});
        transport.setAdapter(transportAdapter);
        transport.setSelection(prefs.getInt("transport",0));
        transport.setPadding(dp(12),dp(12),dp(12),dp(12));
        transport.setBackground(background(PANEL,12));
        root.addView(transport,spaced(7));
        transportGuide = label("",13,MUTED,false);
        root.addView(transportGuide,spaced(10));

        usbSettingsButton = button("USB-Tethering Einstellungen öffnen",YELLOW);
        root.addView(usbSettingsButton,spaced(12));
        usbSettingsButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent("android.settings.TETHER_SETTINGS"));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception ignored) {
                    Toast.makeText(this,"Unter Einstellungen → Hotspot und Tethering öffnen.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        usbSearchButton = button("OBS-PC über USB automatisch suchen",ACCENT);
        root.addView(usbSearchButton,spaced(10));
        usbSearchButton.setOnClickListener(v -> searchUsbPc(false));
        usbDiagnostics = label("",12,MUTED,false);
        root.addView(usbDiagnostics,spaced(8));

        host = input("192.168.178.20",prefs.getString("host_wifi",prefs.getString("host","")),false);
        port = input("49888",prefs.getString("port","49888"),true);
        code = input("24681357",prefs.getString("code","24681357"),true);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        addressLabel = label("IPv4-Adresse deines OBS-PCs",13,MUTED,false);
        root.addView(addressLabel,spaced(17));
        root.addView(host,spaced(7));
        addInput(root,"UDP-Port in OBS",port);
        addInput(root,"8-stelliger Verbindungscode in OBS",code);
        transport.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (selectedTransport == pos) return;
                if (selectedTransport >= 0) {
                    prefs.edit().putString(selectedTransport == 1 ? "host_usb" : "host_wifi",
                            host.getText().toString().trim()).apply();
                }
                selectedTransport = pos;
                host.setText(prefs.getString(pos == 1 ? "host_usb" : "host_wifi",
                        pos == 1 ? "" : prefs.getString("host","")));
                updateTransportUI();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateTransportUI();
        root.addView(label("Mikrofonprofil",13,MUTED,false),spaced(18));
        mode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Sprache / Kommentator","Originalton / Halle"});
        mode.setAdapter(adapter);
        mode.setSelection(prefs.getInt("mode",0));
        mode.setPadding(dp(12),dp(12),dp(12),dp(12));
        mode.setBackground(background(PANEL,12));
        root.addView(mode,spaced(7));
        gainLabel = label("Mikrofonverstärkung: 100 %",13,MUTED,false);
        root.addView(gainLabel,spaced(22));
        gain = new SeekBar(this);
        gain.setMax(250);
        gain.setProgress(prefs.getInt("gain",100));
        gain.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        gain.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        root.addView(gain,spaced(6));
        gainLabel.setText(String.format(Locale.GERMAN,"Mikrofonverstärkung: %d %%",gain.getProgress()));
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s,int value,boolean user) {
                gainLabel.setText(String.format(Locale.GERMAN,"Mikrofonverstärkung: %d %%",value));
                if (user && MicService.transmitting) {
                    Intent i = new Intent(MainActivity.this,MicService.class);
                    i.setAction(MicService.ACTION_GAIN); i.putExtra("gain",value);
                    startService(i);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        startButton = button("Mikrofon starten",ACCENT);
        root.addView(startButton,spaced(24));
        startButton.setOnClickListener(v -> {
            if (MicService.transmitting) stopMic();
            else validateAndStart();
        });
        muteButton = button("Mikrofon stummschalten",YELLOW);
        root.addView(muteButton,spaced(10));
        muteButton.setOnClickListener(v -> {
            if (!MicService.transmitting) return;
            Intent i = new Intent(this,MicService.class);
            i.setAction(MicService.ACTION_MUTE);
            i.putExtra("mute",!MicService.muted);
            startService(i);
        });
        root.addView(label("48 kHz · Mono · 16 Bit · WLAN oder USB/UDP\n"+
                "Die Übertragung bleibt auch mit ausgeschaltetem Bildschirm aktiv. "+
                "Keine Cloud oder Internetverbindung erforderlich.",12,MUTED,false),spaced(25));
        refresh();
    }
    private void updateTransportUI() {
        if (transport == null || transportGuide == null || host == null) return;
        boolean usb = transport.getSelectedItemPosition() == 1;
        transportGuide.setText(usb
                ? "1. Handy mit einem Daten-USB-Kabel am PC anschließen.\n"
                + "2. Am Handy USB-Tethering einschalten.\n"
                + "3. OBS öffnen und PC automatisch suchen.\n"
                + "Falls die Suche scheitert: In Windows Einstellungen → Netzwerk & Internet → "
                + "Ethernet die IPv4 des neuen USB-Netzwerkadapters ablesen und unten eintragen."
                : "Handy und OBS-PC müssen über dasselbe lokale Netzwerk erreichbar sein.");
        usbSettingsButton.setVisibility(usb ? View.VISIBLE : View.GONE);
        usbSearchButton.setVisibility(usb ? View.VISIBLE : View.GONE);
        usbDiagnostics.setVisibility(usb ? View.VISIBLE : View.GONE);
        addressLabel.setText(usb ? "IPv4-Adresse des PC-USB-Netzwerkadapters" : "IPv4-Adresse deines OBS-PCs");
        host.setHint(usb ? "USB-PC automatisch suchen oder IPv4 eingeben" : "z. B. 192.168.178.20");
    }

    private void searchUsbPc(boolean startAfterFound) {
        if (MicService.transmitting) return;
        final int p;
        final int pairing;
        try {
            p = Integer.parseInt(port.getText().toString().trim());
            pairing = Integer.parseInt(code.getText().toString().trim());
            if (p < 1024 || p > 65535 || pairing < 10000000 || pairing > 99999999)
                throw new IllegalArgumentException("Port oder Verbindungscode ungültig.");
        } catch (Exception ex) {
            Toast.makeText(this, "Zuerst gültigen UDP-Port und 8-stelligen Code eingeben.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        usbSearchButton.setEnabled(false);
        usbSearchButton.setText("Suche OBS-PC am USB-Kabel ...");
        usbDiagnostics.setText("Prüfe USB-Netzwerk und suche OBS im lokalen Subnetz ...");
        new Thread(() -> {
            UsbDiscovery.Result result = UsbDiscovery.discover(p, pairing);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                usbSearchButton.setText("OBS-PC über USB automatisch suchen");
                usbSearchButton.setEnabled(!MicService.transmitting);
                if (result.ip != null) {
                    host.setText(result.ip);
                    prefs.edit().putString("host_usb", result.ip).apply();
                    usbDiagnostics.setText("Verbundenes USB-Netz: " + result.diagnostics
                            + "\nOBS-PC: " + result.ip);
                    if (startAfterFound) validateAndStart();
                } else {
                    usbDiagnostics.setText(result.message + "\n" + result.diagnostics);
                }
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            });
        }, "PhoneMic-UsbDiscovery").start();
    }

    private void validateAndStart() {
        if (transport.getSelectedItemPosition() == 1
                && host.getText().toString().trim().isEmpty()) {
            searchUsbPc(true);
            return;
        }
        try {
            String ip=host.getText().toString().trim();
            if(!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
                throw new IllegalArgumentException("Bitte die IPv4-Adresse deines OBS-PCs eintragen.");
            InetAddress address=InetAddress.getByName(ip);
            if(!(address instanceof Inet4Address) || address.isAnyLocalAddress() || address.isLoopbackAddress())
                throw new IllegalArgumentException("Ungültige PC-Adresse.");
            int p=Integer.parseInt(port.getText().toString().trim());
            if(p<1024 || p>65535) throw new IllegalArgumentException("Port: 1024 bis 65535.");
            String secret=code.getText().toString().trim();
            if(!secret.matches("[1-9][0-9]{7}")) throw new IllegalArgumentException("Genau 8 Ziffern als Code eingeben.");
            prefs.edit().putString("host",ip).putString("port",String.valueOf(p))
                .putString("code",secret).putInt("mode",mode.getSelectedItemPosition())
                .putInt("transport",transport.getSelectedItemPosition())
                .putString(transport.getSelectedItemPosition() == 1 ? "host_usb" : "host_wifi",ip)
                .putInt("gain",gain.getProgress()).apply();
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
                permissionPending=true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},101);
                return;
            }
            Intent i=new Intent(this,MicService.class);
            i.setAction(MicService.ACTION_START);
            i.putExtra("host",ip);
            i.putExtra("port",p);
            i.putExtra("token",Integer.parseInt(secret));
            i.putExtra("gain",gain.getProgress());
            i.putExtra("mode",mode.getSelectedItemPosition());
            i.putExtra("transport",transport.getSelectedItemPosition());
            startForegroundService(i);
            status.setText("Mikrofon startet ...");
        }catch(Exception ex){
            Toast.makeText(this,ex.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request!=101 || !permissionPending) return;
        permissionPending=false;
        if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) validateAndStart();
        else Toast.makeText(this,"Mikrofonberechtigung erforderlich.",Toast.LENGTH_LONG).show();
    }
    private void stopMic(){
        Intent i=new Intent(this,MicService.class);
        i.setAction(MicService.ACTION_STOP);
        startService(i);
    }
    private void refresh() {
        if(status==null || startButton==null) return;
        boolean on=MicService.transmitting;
        startButton.setText(on ? "Mikrofon stoppen" : "Mikrofon starten");
        startButton.setBackground(background(on?RED:ACCENT,12));
        muteButton.setVisibility(on?View.VISIBLE:View.GONE);
        muteButton.setText(MicService.muted?"Stummschaltung aufheben":"Mikrofon stummschalten");
        host.setEnabled(!on);
        port.setEnabled(!on);
        code.setEnabled(!on);
        mode.setEnabled(!on);
        transport.setEnabled(!on);
        usbSearchButton.setEnabled(!on);
        if(!on) {
            boolean failed=!MicService.error.isEmpty();
            status.setText(failed?"Fehler":"Bereit");
            status.setTextColor(failed?RED:ACCENT);
            details.setText(failed?MicService.error:"IP-Adresse des OBS-PCs eintragen und starten.");
        }else if(MicService.muted) {
            status.setText("Stummgeschaltet");
            status.setTextColor(YELLOW);
            details.setText("Mikrofon an "+MicService.destination+"; Übertragung stumm.");
        }else if(MicService.lastAck!=0 &&
                SystemClock.elapsedRealtime()-MicService.lastAck<2400){
            status.setText("Mit OBS verbunden");
            status.setTextColor(ACCENT);
            details.setText((MicService.transportMode == 1 ? "USB" : "WLAN") + " verbunden • Pakete: "+MicService.packetsSent);
        }else{
            status.setText("Sende ...");
            status.setTextColor(YELLOW);
            details.setText("Warte auf OBS-Bestätigung • Gesendet: "+MicService.packetsSent+" Pakete. IP, Port, Code und Firewall prüfen.");
        }
    }
    @Override protected void onResume(){super.onResume();handler.post(ticker);}
    @Override protected void onPause(){handler.removeCallbacks(ticker);super.onPause();}
}
