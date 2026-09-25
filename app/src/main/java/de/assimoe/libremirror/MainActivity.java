package de.assimoe.libremirror;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private EditText email;
    private EditText password;
    private EditText low;
    private EditText high;
    private Spinner region;
    private CheckBox carVoice;
    private TextView valueView;
    private TextView detailView;
    private TextView statusView;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshUi = new Runnable() {
        @Override
        public void run() {
            renderLastReading();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadSettings();
        requestNotificationPermission();
        renderLastReading();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(28));
        scroll.addView(root);

        root.addView(text("LibreMirror", 30, true));

        TextView subtitle = text("Private Test-App • LibreLinkUp → Handy → Smartwatch", 14, false);
        subtitle.setTextColor(Color.DKGRAY);
        root.addView(subtitle, lpTop(6));

        valueView = text("—", 44, true);
        valueView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(valueView, lpTop(26));

        detailView = text("Noch kein Wert", 14, false);
        detailView.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(detailView, lpTop(2));

        statusView = text("Nicht gestartet", 13, false);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setTextColor(Color.DKGRAY);
        root.addView(statusView, lpTop(6));

        root.addView(text("LibreLinkUp", 18, true), lpTop(28));

        email = input("E-Mail", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(email, lpTop(8));

        password = input("Passwort", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password, lpTop(8));

        region = new Spinner(this);
        String[] regions = {"AUTO", "EU", "DE", "EU2", "US", "FR", "CA", "AU", "AP", "AE", "JP"};
        region.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, regions));
        root.addView(region, lpTop(8));

        root.addView(text("Warnungen", 18, true), lpTop(24));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.HORIZONTAL);

        low = input("Niedrig mg/dL", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        high = input("Hoch mg/dL", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        limits.addView(low, new LinearLayout.LayoutParams(0, dp(54), 1));

        View spacer = new View(this);
        limits.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));

        limits.addView(high, new LinearLayout.LayoutParams(0, dp(54), 1));
        root.addView(limits, lpTop(8));

        carVoice = new CheckBox(this);
        carVoice.setText("Im Android-Car-Modus Grenzwertwarnungen vorlesen");
        root.addView(carVoice, lpTop(8));

        Button save = button("Speichern & Live-Anzeige starten");
        save.setOnClickListener(v -> saveAndStart());
        root.addView(save, lpTop(22));

        Button refresh = button("Jetzt aktualisieren");
        refresh.setOnClickListener(v -> {
            Intent intent = new Intent(this, LibreService.class);
            intent.setAction(LibreService.ACTION_REFRESH);
            startServiceCompat(intent);
            toast("Aktualisierung angefordert");
        });
        root.addView(refresh, lpTop(10));

        Button stop = button("Live-Anzeige stoppen");
        stop.setOnClickListener(v -> {
            SecurePrefs.prefs(this).edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, LibreService.class));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(1002);
            statusView.setText("Gestoppt");
        });
        root.addView(stop, lpTop(10));

        TextView note = text(
                "Testversion. Die LibreLinkUp-Schnittstelle ist inoffiziell. Werte nicht als alleinige Grundlage für Therapie- oder Dosierungsentscheidungen verwenden. Android Auto zeigt normale Status-Benachrichtigungen nicht zuverlässig auf dem Fahrzeugdisplay; Grenzwert-Sprachausgabe wird im Car-Modus getestet.",
                12,
                false);
        note.setTextColor(Color.DKGRAY);
        root.addView(note, lpTop(24));

        return scroll;
    }

    private void saveAndStart() {
        String mail = email.getText().toString().trim();
        String pass = password.getText().toString();

        if (mail.isEmpty() || pass.isEmpty()) {
            toast("E-Mail und Passwort fehlen");
            return;
        }

        try {
            SecurePrefs.putSecret(this, "email", mail);
            SecurePrefs.putSecret(this, "password", pass);

            SecurePrefs.prefs(this).edit()
                    .putString("region", String.valueOf(region.getSelectedItem()))
                    .putString("low", low.getText().toString().trim().isEmpty() ? "70" : low.getText().toString().trim())
                    .putString("high", high.getText().toString().trim().isEmpty() ? "180" : high.getText().toString().trim())
                    .putBoolean("car_voice", carVoice.isChecked())
                    .putBoolean("enabled", true)
                    .apply();

            startServiceCompat(new Intent(this, LibreService.class));
            toast("LibreMirror gestartet");
        } catch (Exception e) {
            toast("Speichern fehlgeschlagen: " + e.getMessage());
        }
    }

    private void loadSettings() {
        SharedPreferences prefs = SecurePrefs.prefs(this);

        email.setText(SecurePrefs.getSecret(this, "email"));
        password.setText(SecurePrefs.getSecret(this, "password"));

        String wanted = prefs.getString("region", "AUTO");
        for (int i = 0; i < region.getCount(); i++) {
            if (String.valueOf(region.getItemAtPosition(i)).equals(wanted)) {
                region.setSelection(i);
                break;
            }
        }

        low.setText(prefs.getString("low", "70"));
        high.setText(prefs.getString("high", "180"));
        carVoice.setChecked(prefs.getBoolean("car_voice", true));

        if (prefs.getBoolean("enabled", false)) {
            startServiceCompat(new Intent(this, LibreService.class));
        }
    }

    private void renderLastReading() {
        SharedPreferences prefs = SecurePrefs.prefs(this);

        String value = prefs.getString("last_value", "");
        String unit = prefs.getString("last_unit", "mg/dL");
        int trend = prefs.getInt("last_trend", 0);
        long fetch = prefs.getLong("last_fetch_ms", 0);
        String sensor = prefs.getString("last_sensor_time", "");
        String error = prefs.getString("last_error", "");

        if (!value.isEmpty()) {
            valueView.setText(value + " " + LibreApiClient.arrow(trend));
            detailView.setText(unit + (sensor.isEmpty() ? "" : " • Sensor " + sensor));

            String fetchTime = fetch == 0
                    ? "—"
                    : new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(fetch));

            statusView.setText(
                    "Abruf " + fetchTime +
                    (error.isEmpty() ? " • verbunden" : " • Fehler: " + error));
        } else if (!error.isEmpty()) {
            statusView.setText(error);
        }
    }

    private void startServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            toast("Dienst konnte nicht gestartet werden: " + e.getMessage());
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(Color.rgb(20, 20, 24));
        if (bold) textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return textView;
    }

    private EditText input(String hint, int type) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setInputType(type);
        editText.setSingleLine(true);
        editText.setPadding(dp(12), 0, dp(12), 0);
        editText.setBackgroundColor(Color.rgb(242, 242, 246));
        editText.setMinHeight(dp(54));
        return editText;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setMinHeight(dp(52));
        return button;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshUi);
        handler.post(refreshUi);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshUi);
        super.onPause();
    }
}
