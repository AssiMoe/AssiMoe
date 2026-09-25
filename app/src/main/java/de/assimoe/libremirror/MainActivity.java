package de.assimoe.libremirror;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int BLUE = 0xFF0878E8;
    private static final int BLUE_DARK = 0xFF06366F;
    private static final int GREEN = 0xFF078B5B;
    private static final int RED = 0xFFD64848;
    private static final int PAGE_BG = 0xFFF6FAFF;
    private static final int FIELD_BG = 0xFFF7FAFE;
    private static final int BORDER = 0xFFDCE8F4;

    private Spinner sourceSpinner;
    private EditText email;
    private EditText password;
    private EditText low;
    private EditText high;
    private Spinner region;
    private Switch carVoice;

    private LinearLayout loginCard;
    private LinearLayout jugglucoCard;
    private TextView jugglucoInstallStatus;
    private TextView jugglucoReadingStatus;

    private TextView valueView;
    private TextView unitView;
    private TextView trendArrowView;
    private TextView trendLabelView;
    private TextView connectionChip;
    private TextView updatedView;
    private TextView sourceView;
    private TextView errorView;
    private Button startButton;
    private Button refreshButton;
    private GlucoseChartView chartView;

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

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        setContentView(buildUi());
        loadSettings();
        requestNotificationPermission();
        renderLastReading();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE_BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        scroll.addView(root);

        root.addView(buildHeader());
        root.addView(buildGlucoseCard(), fullTop(18));
        root.addView(buildSourceCard(), fullTop(16));

        jugglucoCard = buildJugglucoCard();
        root.addView(jugglucoCard, fullTop(16));

        loginCard = buildLoginCard();
        root.addView(loginCard, fullTop(16));

        root.addView(buildWarningCard(), fullTop(16));
        root.addView(buildActionArea(), fullTop(18));

        TextView foot = text(
                "Private Testversion • Direktmodus über Juggluco-Bluetooth-Bridge • Für Therapieentscheidungen weiterhin die offizielle Libre-App verwenden.",
                11,
                false,
                0xFF6E7F95
        );
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(0, 1.15f);
        root.addView(foot, fullTop(18));

        return scroll;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        row.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);

        titles.addView(text("LibreMirror", 28, true, 0xFF071B3E));

        TextView sub = text("Libre 3  →  Handy  →  Smartwatch", 14, false, 0xFF5D7596);
        titles.addView(sub, wrapTop(2));

        row.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView version = text("0.3", 12, true, BLUE);
        version.setGravity(Gravity.CENTER);
        version.setBackground(rounded(0xFFE7F3FF, 18));
        version.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.addView(version);

        return row;
    }

    private View buildGlucoseCard() {
        LinearLayout card = card(true);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView overline = text("GLUKOSE", 12, true, 0xFF58759A);
        overline.setLetterSpacing(0.08f);
        titleRow.addView(overline, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        sourceView = text("DIREKT", 10, true, BLUE);
        sourceView.setPadding(dp(10), dp(5), dp(10), dp(5));
        sourceView.setBackground(rounded(0xFFE5F3FF, 15));
        titleRow.addView(sourceView);

        card.addView(titleRow);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(main, fullTop(6));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setGravity(Gravity.BOTTOM);

        valueView = text("—", 58, true, BLUE_DARK);
        valueView.setIncludeFontPadding(false);
        valueRow.addView(valueView);

        unitView = text("mg/dL", 20, true, 0xFF183B6B);
        unitView.setPadding(dp(8), 0, 0, dp(7));
        valueRow.addView(unitView);

        left.addView(valueRow);

        connectionChip = text("Warte auf Sensor", 13, true, BLUE);
        connectionChip.setGravity(Gravity.CENTER);
        connectionChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        connectionChip.setBackground(rounded(0xFFE5F3FF, 18));
        left.addView(connectionChip, wrapTop(8));

        main.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout trend = new LinearLayout(this);
        trend.setOrientation(LinearLayout.VERTICAL);
        trend.setGravity(Gravity.CENTER);

        trendArrowView = text("→", 54, false, GREEN);
        trendArrowView.setGravity(Gravity.CENTER);
        trend.addView(trendArrowView, new LinearLayout.LayoutParams(dp(88), dp(64)));

        trendLabelView = text("Trend", 14, true, GREEN);
        trendLabelView.setGravity(Gravity.CENTER);
        trend.addView(trendLabelView);

        main.addView(trend, new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));

        chartView = new GlucoseChartView(this);
        card.addView(chartView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(86)
        ));

        updatedView = text("Letzte Aktualisierung: —", 12, false, 0xFF607998);
        card.addView(updatedView, wrapTop(2));

        errorView = text("", 12, false, RED);
        errorView.setVisibility(View.GONE);
        card.addView(errorView, wrapTop(5));

        return card;
    }

    private View buildSourceCard() {
        LinearLayout card = card(false);

        card.addView(sectionHeader(
                android.R.drawable.ic_menu_share,
                "Datenquelle",
                "Wähle direkten Sensorbetrieb oder Cloud-Fallback.",
                text("", 1, false, Color.TRANSPARENT)
        ));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(8), dp(10), dp(8));
        box.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));

        TextView label = text("Quelle", 11, true, 0xFF5B7190);
        box.addView(label);

        sourceSpinner = new Spinner(this);
        String[] sources = {
                "Direkt vom Libre 3 (Juggluco)",
                "LibreLinkUp Cloud"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                sources
        ) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(0xFF142847);
                view.setTextSize(16);
                view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                view.setPadding(0, 0, 0, 0);
                return view;
            }
        };

        sourceSpinner.setAdapter(adapter);
        sourceSpinner.setBackgroundColor(Color.TRANSPARENT);
        sourceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateSourceVisibility();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        box.addView(sourceSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        ));

        card.addView(box, fullTop(12));
        return card;
    }

    private LinearLayout buildJugglucoCard() {
        LinearLayout card = card(false);

        card.addView(sectionHeader(
                android.R.drawable.stat_sys_data_bluetooth,
                "Libre 3 Direktmodus",
                "Juggluco empfängt den Sensor per Bluetooth.",
                text("", 1, false, Color.TRANSPARENT)
        ));

        jugglucoInstallStatus = text("Juggluco wird geprüft …", 13, true, BLUE);
        jugglucoInstallStatus.setPadding(dp(12), dp(8), dp(12), dp(8));
        jugglucoInstallStatus.setBackground(rounded(0xFFE5F3FF, 16));
        card.addView(jugglucoInstallStatus, wrapTop(12));

        jugglucoReadingStatus = text("Noch kein Direktwert empfangen.", 12, false, 0xFF607998);
        card.addView(jugglucoReadingStatus, wrapTop(10));

        TextView steps = text(
                "1. Juggluco mit deinem Libre 3 verbinden.\n" +
                "2. Juggluco → Einstellungen → Glucodata broadcast.\n" +
                "3. Als Empfänger de.assimoe.libremirror auswählen und speichern.\n" +
                "4. Danach kommt etwa jede Minute ein neuer Wert in LibreMirror an.",
                12,
                false,
                0xFF445D7E
        );
        steps.setLineSpacing(dp(2), 1.12f);
        card.addView(steps, fullTop(12));

        Button open = secondaryButton("Juggluco öffnen");
        open.setOnClickListener(v -> openJuggluco());
        card.addView(open, fullHeightTop(50, 14));

        Button copy = secondaryButton("App-ID kopieren");
        copy.setOnClickListener(v -> copyPackageName());
        card.addView(copy, fullHeightTop(50, 8));

        return card;
    }

    private LinearLayout buildLoginCard() {
        LinearLayout card = card(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView chevron = text("⌃", 22, true, 0xFF5D7596);
        LinearLayout header = sectionHeader(
                android.R.drawable.ic_menu_myplaces,
                "LibreLinkUp Fallback",
                "Nur nötig, wenn du den Cloud-Modus nutzt.",
                chevron
        );
        card.addView(header);
        card.addView(content, fullTop(12));

        email = field("E-Mail-Adresse", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        content.addView(email);

        LinearLayout passwordWrap = new LinearLayout(this);
        passwordWrap.setOrientation(LinearLayout.VERTICAL);
        passwordWrap.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));
        passwordWrap.setPadding(dp(14), dp(7), dp(10), dp(7));

        passwordWrap.addView(text("Passwort", 11, true, 0xFF5B7190));

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passRow.setGravity(Gravity.CENTER_VERTICAL);

        password = new EditText(this);
        password.setTextSize(17);
        password.setTextColor(0xFF142847);
        password.setSingleLine(true);
        password.setBackgroundColor(Color.TRANSPARENT);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        password.setPadding(0, 0, dp(8), 0);
        passRow.addView(password, new LinearLayout.LayoutParams(0, dp(42), 1f));

        TextView show = text("ANZEIGEN", 10, true, BLUE);
        show.setGravity(Gravity.CENTER);
        show.setPadding(dp(8), 0, dp(8), 0);
        show.setOnClickListener(v -> {
            int pos = password.getSelectionStart();
            if (password.getTransformationMethod() == null) {
                password.setTransformationMethod(PasswordTransformationMethod.getInstance());
                show.setText("ANZEIGEN");
            } else {
                password.setTransformationMethod(null);
                show.setText("AUSBLENDEN");
            }
            password.setSelection(Math.max(0, pos));
        });
        passRow.addView(show, new LinearLayout.LayoutParams(dp(88), dp(42)));

        passwordWrap.addView(passRow);
        content.addView(passwordWrap, fullTop(10));

        LinearLayout regionBox = new LinearLayout(this);
        regionBox.setOrientation(LinearLayout.VERTICAL);
        regionBox.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));
        regionBox.setPadding(dp(14), dp(7), dp(10), dp(7));

        regionBox.addView(text("Region", 11, true, 0xFF5B7190));

        region = new Spinner(this);
        String[] regions = {"AUTO", "EU", "DE", "EU2", "US", "FR", "CA", "AU", "AP", "AE", "JP"};

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                regions
        ) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(0xFF142847);
                view.setTextSize(17);
                view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                view.setPadding(0, 0, 0, 0);
                return view;
            }
        };

        region.setAdapter(adapter);
        region.setBackgroundColor(Color.TRANSPARENT);
        regionBox.addView(region, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
        ));
        content.addView(regionBox, fullTop(10));

        header.setOnClickListener(v -> {
            if (content.getVisibility() == View.VISIBLE) {
                content.setVisibility(View.GONE);
                chevron.setText("⌄");
            } else {
                content.setVisibility(View.VISIBLE);
                chevron.setText("⌃");
            }
        });

        return card;
    }

    private View buildWarningCard() {
        LinearLayout card = card(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView chevron = text("⌃", 22, true, 0xFF5D7596);
        LinearLayout header = sectionHeader(
                android.R.drawable.ic_dialog_alert,
                "Warnschwellen",
                "Benachrichtigung außerhalb deiner Grenzwerte.",
                chevron
        );

        card.addView(header);
        card.addView(content, fullTop(12));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout lowBox = smallNumberBox("Niedrig (mg/dL)");
        low = (EditText) lowBox.getChildAt(1);
        limits.addView(lowBox, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        View gap = new View(this);
        limits.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        LinearLayout highBox = smallNumberBox("Hoch (mg/dL)");
        high = (EditText) highBox.getChildAt(1);
        limits.addView(highBox, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        content.addView(limits);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE8EFF7);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerLp.topMargin = dp(14);
        content.addView(divider, dividerLp);

        LinearLayout carRow = new LinearLayout(this);
        carRow.setOrientation(LinearLayout.HORIZONTAL);
        carRow.setGravity(Gravity.CENTER_VERTICAL);
        carRow.setPadding(0, dp(12), 0, 0);

        ImageView carIcon = circleIcon(android.R.drawable.ic_menu_directions);
        carRow.addView(carIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout carText = new LinearLayout(this);
        carText.setOrientation(LinearLayout.VERTICAL);
        carText.setPadding(dp(10), 0, dp(8), 0);

        carText.addView(text("Android Auto – Sprachwarnungen", 14, true, 0xFF142847));
        carText.addView(text("Warnungen im Car-Modus vorlesen.", 11, false, 0xFF7186A1), wrapTop(2));

        carRow.addView(carText, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        carVoice = new Switch(this);
        carVoice.setShowText(false);
        carRow.addView(carVoice, new LinearLayout.LayoutParams(dp(58), dp(44)));

        content.addView(carRow);

        header.setOnClickListener(v -> {
            if (content.getVisibility() == View.VISIBLE) {
                content.setVisibility(View.GONE);
                chevron.setText("⌄");
            } else {
                content.setVisibility(View.VISIBLE);
                chevron.setText("⌃");
            }
        });

        return card;
    }

    private View buildActionArea() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        startButton = new Button(this);
        startButton.setText("▶   Direktmodus starten");
        startButton.setTextSize(16);
        startButton.setTextColor(Color.WHITE);
        startButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startButton.setAllCaps(false);
        startButton.setGravity(Gravity.CENTER);
        startButton.setBackground(gradientButton());
        startButton.setOnClickListener(v -> saveAndStart());

        box.addView(startButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(58)
        ));

        refreshButton = secondaryButton("↻   Jetzt aktualisieren");
        refreshButton.setOnClickListener(v -> requestRefresh());
        box.addView(refreshButton, fullHeightTop(56, 10));

        TextView stop = text("Live-Anzeige stoppen", 12, true, 0xFF7A8DA8);
        stop.setGravity(Gravity.CENTER);
        stop.setPadding(0, dp(12), 0, dp(4));
        stop.setOnClickListener(v -> {
            SecurePrefs.prefs(this).edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, LibreService.class));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(1002);
            renderLastReading();
            toast("Live-Anzeige gestoppt");
        });

        box.addView(stop);
        return box;
    }

    private void updateSourceVisibility() {
        if (sourceSpinner == null || loginCard == null || jugglucoCard == null) return;

        boolean direct = sourceSpinner.getSelectedItemPosition() == 0;
        jugglucoCard.setVisibility(direct ? View.VISIBLE : View.GONE);
        loginCard.setVisibility(direct ? View.GONE : View.VISIBLE);

        if (startButton != null) {
            startButton.setText(direct ? "▶   Direktmodus starten" : "▶   Cloud-Modus starten");
        }

        if (refreshButton != null) {
            refreshButton.setText(direct ? "↻   Auf nächsten Sensorwert warten" : "↻   Jetzt aktualisieren");
        }

        renderLastReading();
    }

    private void saveAndStart() {
        boolean direct = sourceSpinner.getSelectedItemPosition() == 0;

        if (!direct) {
            String mail = email.getText().toString().trim();
            String pass = password.getText().toString();

            if (mail.isEmpty() || pass.isEmpty()) {
                toast("E-Mail und Passwort fehlen");
                return;
            }

            try {
                SecurePrefs.putSecret(this, "email", mail);
                SecurePrefs.putSecret(this, "password", pass);
            } catch (Exception e) {
                toast("Zugangsdaten konnten nicht gespeichert werden: " + e.getMessage());
                return;
            }
        }

        SecurePrefs.prefs(this).edit()
                .putString("source", direct ? "JUGGLUCO" : "LIBRELINKUP")
                .putString("region", String.valueOf(region.getSelectedItem()))
                .putString("low", low.getText().toString().trim().isEmpty() ? "70" : low.getText().toString().trim())
                .putString("high", high.getText().toString().trim().isEmpty() ? "180" : high.getText().toString().trim())
                .putBoolean("car_voice", carVoice.isChecked())
                .putBoolean("enabled", true)
                .putString("last_error", "")
                .apply();

        startServiceCompat(new Intent(this, LibreService.class));

        if (direct) {
            toast("Direktmodus aktiv – Juggluco sendet den nächsten Wert automatisch.");
        } else {
            toast("LibreLinkUp Cloud-Modus gestartet.");
        }

        renderLastReading();
    }

    private void requestRefresh() {
        boolean direct = sourceSpinner.getSelectedItemPosition() == 0;

        if (direct) {
            toast("Direktwerte kommen automatisch von Juggluco – normalerweise jede Minute.");
            return;
        }

        Intent intent = new Intent(this, LibreService.class);
        intent.setAction(LibreService.ACTION_REFRESH);
        startServiceCompat(intent);
        toast("Aktualisierung angefordert");
    }

    private void loadSettings() {
        SharedPreferences prefs = SecurePrefs.prefs(this);

        String source = prefs.getString("source", "JUGGLUCO");
        sourceSpinner.setSelection("LIBRELINKUP".equals(source) ? 1 : 0);

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

        updateSourceVisibility();

        if (prefs.getBoolean("enabled", false)) {
            startServiceCompat(new Intent(this, LibreService.class));
        }
    }

    private void renderLastReading() {
        if (valueView == null || sourceSpinner == null) return;

        SharedPreferences prefs = SecurePrefs.prefs(this);
        boolean direct = sourceSpinner.getSelectedItemPosition() == 0;
        boolean enabled = prefs.getBoolean("enabled", false);

        String value;
        String unit;
        int trend;
        long fetch;
        String error;

        if (direct) {
            long lastSeen = prefs.getLong("juggluco_last_seen_ms", 0);
            if (lastSeen > 0) {
                value = String.valueOf(prefs.getInt("juggluco_last_mgdl", 0));
                unit = "mg/dL";
                float rate = prefs.getFloat("juggluco_last_rate", Float.NaN);
                trend = trendFromRate(rate);
                fetch = lastSeen;
                error = "";
            } else {
                value = "";
                unit = "mg/dL";
                trend = 0;
                fetch = 0;
                error = "";
            }
        } else {
            value = "LIBRELINKUP".equals(prefs.getString("last_source", ""))
                    ? prefs.getString("last_value", "")
                    : prefs.getString("last_value", "");
            unit = prefs.getString("last_unit", "mg/dL");
            trend = prefs.getInt("last_trend", 0);
            fetch = prefs.getLong("last_fetch_ms", 0);
            error = prefs.getString("last_error", "");
        }

        sourceView.setText(direct ? "DIREKT" : "CLOUD");
        sourceView.setTextColor(direct ? GREEN : BLUE);
        sourceView.setBackground(rounded(direct ? 0xFFE6F8EF : 0xFFE5F3FF, 15));

        if (!value.isEmpty() && !"0".equals(value)) {
            valueView.setText(value);
            unitView.setText(unit);
            trendArrowView.setText(LibreApiClient.arrow(trend));
            trendLabelView.setText(trendLabel(trend));

            long age = fetch <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - fetch;
            boolean fresh = age <= 5 * 60 * 1000L;

            connectionChip.setText(fresh ? "●  Verbunden" : "●  Wert veraltet");
            connectionChip.setTextColor(fresh ? GREEN : 0xFFB57800);
            connectionChip.setBackground(rounded(fresh ? 0xFFE6F8EF : 0xFFFFF3D9, 18));

            if (fetch > 0) {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(fetch));
                updatedView.setText("Letzte Aktualisierung: Heute, " + time);
            } else {
                updatedView.setText("Letzte Aktualisierung: —");
            }
        } else {
            valueView.setText("—");
            unitView.setText("mg/dL");
            trendArrowView.setText("→");
            trendLabelView.setText("Kein Wert");

            connectionChip.setText(direct ? "●  Warte auf Juggluco" : "●  Nicht verbunden");
            connectionChip.setTextColor(direct ? BLUE : RED);
            connectionChip.setBackground(rounded(direct ? 0xFFE5F3FF : 0xFFFFEBEE, 18));
            updatedView.setText("Letzte Aktualisierung: —");
        }

        if (error == null || error.isEmpty()) {
            errorView.setVisibility(View.GONE);
            errorView.setText("");
        } else {
            errorView.setVisibility(View.VISIBLE);
            errorView.setText(error);
        }

        chartView.setValues(readHistory(prefs.getString("history_values", "")));
        startButton.setText(enabled
                ? "●   Live-Anzeige läuft"
                : (direct ? "▶   Direktmodus starten" : "▶   Cloud-Modus starten"));

        updateJugglucoStatus(prefs);
    }

    private void updateJugglucoStatus(SharedPreferences prefs) {
        if (jugglucoInstallStatus == null || jugglucoReadingStatus == null) return;

        boolean installed = isJugglucoInstalled();

        jugglucoInstallStatus.setText(installed ? "●  Juggluco erkannt" : "●  Juggluco nicht erkannt");
        jugglucoInstallStatus.setTextColor(installed ? GREEN : RED);
        jugglucoInstallStatus.setBackground(rounded(installed ? 0xFFE6F8EF : 0xFFFFEBEE, 16));

        long lastSeen = prefs.getLong("juggluco_last_seen_ms", 0);
        if (lastSeen <= 0) {
            jugglucoReadingStatus.setText("Noch kein glucodata.Minute-Broadcast empfangen.");
            return;
        }

        int mgdl = prefs.getInt("juggluco_last_mgdl", 0);
        String serial = prefs.getString("juggluco_last_serial", "");
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(lastSeen));

        jugglucoReadingStatus.setText(
                "Letzter Direktwert: " + mgdl + " mg/dL • " + time +
                (serial.isEmpty() ? "" : " • Sensor " + serial)
        );
    }

    private void openJuggluco() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("tk.glucodata");
        if (launch == null) {
            toast("Juggluco ist auf diesem Handy nicht installiert.");
            return;
        }
        startActivity(launch);
    }

    private void copyPackageName() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("LibreMirror App-ID", "de.assimoe.libremirror"));
        toast("App-ID kopiert: de.assimoe.libremirror");
    }

    private boolean isJugglucoInstalled() {
        try {
            getPackageManager().getPackageInfo("tk.glucodata", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int trendFromRate(float rate) {
        if (Float.isNaN(rate)) return 0;
        if (rate <= -2.0f) return 1;
        if (rate <= -1.0f) return 2;
        if (rate <= 1.0f) return 3;
        if (rate <= 2.0f) return 4;
        return 5;
    }

    private String trendLabel(int trend) {
        switch (trend) {
            case 1:
                return "Stark fallend";
            case 2:
                return "Fallend";
            case 3:
                return "Stabil";
            case 4:
                return "Steigend";
            case 5:
                return "Stark steigend";
            default:
                return "Trend";
        }
    }

    private List<Float> readHistory(String raw) {
        List<Float> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return result;

        String[] parts = raw.contains(";") ? raw.split(";") : raw.split(",");
        for (String part : parts) {
            try {
                result.add(Float.parseFloat(part.replace(',', '.')));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private LinearLayout card(boolean gradient) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setElevation(dp(4));

        GradientDrawable bg;
        if (gradient) {
            bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFFFFFFF, 0xFFF2FCFF}
            );
            bg.setCornerRadius(dp(24));
            bg.setStroke(dp(1), 0xFFE3EDF7);
        } else {
            bg = roundedWithStroke(Color.WHITE, 0xFFE3EDF7, 24);
        }

        card.setBackground(bg);
        return card;
    }

    private LinearLayout sectionHeader(int iconRes, String title, String subtitle, TextView chevron) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = circleIcon(iconRes);
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(8), 0);

        texts.addView(text(title, 18, true, 0xFF0B1D3D));
        texts.addView(text(subtitle, 11, false, 0xFF7186A1), wrapTop(2));

        row.addView(texts, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        ));

        chevron.setGravity(Gravity.CENTER);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(34), dp(40)));

        return row;
    }

    private ImageView circleIcon(int res) {
        ImageView icon = new ImageView(this);
        icon.setPadding(dp(11), dp(11), dp(11), dp(11));
        icon.setBackground(rounded(0xFFE4F2FF, 50));

        Drawable drawable = getDrawable(res);
        if (drawable != null) {
            drawable = drawable.mutate();
            drawable.setTint(BLUE);
            icon.setImageDrawable(drawable);
        }

        return icon;
    }

    private EditText field(String hint, int inputType) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setHintTextColor(0xFF8DA0B8);
        editText.setTextColor(0xFF142847);
        editText.setTextSize(17);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setPadding(dp(14), 0, dp(14), 0);
        editText.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));
        editText.setMinHeight(dp(58));
        return editText;
    }

    private LinearLayout smallNumberBox(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(9), dp(12), dp(8));
        box.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));

        box.addView(text(label, 11, true, 0xFF5B7190));

        EditText value = new EditText(this);
        value.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        value.setTextSize(22);
        value.setTextColor(0xFF142847);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setSingleLine(true);
        value.setBackgroundColor(Color.TRANSPARENT);
        value.setPadding(0, 0, 0, 0);
        value.setMinHeight(dp(40));
        box.addView(value);

        return box;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(BLUE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundedWithStroke(Color.TRANSPARENT, 0xFF84BEF5, 16));
        return button;
    }

    private void startServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
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

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        if (bold) textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return textView;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedWithStroke(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private GradientDrawable gradientButton() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF18A9E8, 0xFF0878E8, 0xFF0752BB}
        );
        drawable.setCornerRadius(dp(18));
        return drawable;
    }

    private LinearLayout.LayoutParams fullTop(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams wrapTop(int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams fullHeightTop(int heightDp, int topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(float value) {
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
