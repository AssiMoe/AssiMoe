package de.assimoe.libremirror;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
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
    private static final int ORANGE = 0xFFB57800;
    private static final int PAGE_BG = 0xFFF6FAFF;
    private static final int FIELD_BG = 0xFFF7FAFE;
    private static final int BORDER = 0xFFDCE8F4;

    private EditText email;
    private EditText password;
    private EditText low;
    private EditText high;
    private Spinner region;
    private Switch carVoice;

    private TextView valueView;
    private TextView unitView;
    private TextView trendArrowView;
    private TextView trendLabelView;
    private TextView connectionChip;
    private TextView updatedView;
    private TextView sensorAgeView;
    private TextView patientView;
    private TextView errorView;
    private TextView serviceStatusView;
    private Button startButton;
    private Button refreshButton;
    private GlucoseChartView chartView;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refreshUi = new Runnable() {
        @Override
        public void run() {
            renderState();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            );
        }

        setContentView(buildUi());
        loadSettings();
        requestNotificationPermission();
        renderState();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE_BG);
        scroll.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(30));
        scroll.addView(root);

        root.addView(buildHeader());
        root.addView(buildGlucoseCard(), fullTop(18));
        root.addView(buildSourceCard(), fullTop(16));
        root.addView(buildLoginCard(), fullTop(16));
        root.addView(buildWarningCard(), fullTop(16));
        root.addView(buildActionArea(), fullTop(18));

        TextView footer = text(
                "Private LibreMirror-Version • Daten werden ausschließlich zwischen deinem Gerät und Abbott/LibreView übertragen. "
                        + "Nicht als alleinige Grundlage für Therapie- oder Dosierungsentscheidungen verwenden.",
                11,
                false,
                0xFF6E7F95
        );
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(0, 1.15f);
        root.addView(footer, fullTop(20));

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
        titles.addView(
                text("Libre 3  →  Cloud  →  Watch", 14, false, 0xFF5D7596),
                wrapTop(2)
        );

        row.addView(
                titles,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        TextView version = text(BuildConfig.VERSION_NAME, 11, true, BLUE);
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

        TextView overline = text("GLUKOSE LIVE", 12, true, 0xFF58759A);
        overline.setLetterSpacing(0.08f);
        titleRow.addView(
                overline,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        TextView source = text("LIBRELINKUP", 10, true, BLUE);
        source.setPadding(dp(10), dp(5), dp(10), dp(5));
        source.setBackground(rounded(0xFFE5F3FF, 15));
        titleRow.addView(source);

        card.addView(titleRow);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(main, fullTop(8));

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

        connectionChip = text("●  Nicht verbunden", 13, true, RED);
        connectionChip.setGravity(Gravity.CENTER);
        connectionChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        connectionChip.setBackground(rounded(0xFFFFEBEE, 18));
        left.addView(connectionChip, wrapTop(8));

        main.addView(
                left,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        LinearLayout trend = new LinearLayout(this);
        trend.setOrientation(LinearLayout.VERTICAL);
        trend.setGravity(Gravity.CENTER);

        trendArrowView = text("→", 54, false, GREEN);
        trendArrowView.setGravity(Gravity.CENTER);
        trend.addView(
                trendArrowView,
                new LinearLayout.LayoutParams(dp(88), dp(64))
        );

        trendLabelView = text("Kein Wert", 14, true, GREEN);
        trendLabelView.setGravity(Gravity.CENTER);
        trend.addView(trendLabelView);

        main.addView(
                trend,
                new LinearLayout.LayoutParams(
                        dp(100),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        chartView = new GlucoseChartView(this);
        card.addView(
                chartView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(92)
                )
        );

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(statusRow, fullTop(6));

        updatedView = text("Sync: —", 12, false, 0xFF607998);
        statusRow.addView(
                updatedView,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        sensorAgeView = text("Sensor: —", 12, false, 0xFF607998);
        sensorAgeView.setGravity(Gravity.END);
        statusRow.addView(sensorAgeView);

        errorView = text("", 12, false, RED);
        errorView.setVisibility(View.GONE);
        errorView.setLineSpacing(dp(1), 1.08f);
        card.addView(errorView, fullTop(8));

        return card;
    }

    private View buildSourceCard() {
        LinearLayout card = card(false);

        card.addView(
                sectionHeader(
                        android.R.drawable.ic_menu_share,
                        "Datenquelle",
                        "Stabiler LibreLinkUp-Follower-Livezugriff.",
                        text("", 1, false, Color.TRANSPARENT)
                )
        );

        LinearLayout route = new LinearLayout(this);
        route.setOrientation(LinearLayout.VERTICAL);
        route.setPadding(dp(14), dp(12), dp(14), dp(12));
        route.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));

        route.addView(
                text(
                        "Libre 3 Sensor  →  offizielle Libre-App  →  Abbott Cloud",
                        13,
                        true,
                        0xFF183B6B
                )
        );
        route.addView(
                text(
                        "→  LibreLinkUp-Freigabe  →  LibreMirror",
                        13,
                        true,
                        0xFF183B6B
                ),
                wrapTop(4)
        );

        card.addView(route, fullTop(12));

        TextView note = text(
                "LibreLinkUp muss nur einmal für Konto/Freigabe eingerichtet und die Einladung angenommen werden. "
                        + "Danach kann die LibreLinkUp-App wieder deinstalliert werden; LibreMirror fragt die Freigabe direkt ab.",
                12,
                false,
                0xFF607998
        );
        note.setLineSpacing(dp(2), 1.12f);
        card.addView(note, fullTop(10));

        patientView = text("Freigabe: —", 12, true, 0xFF526A8A);
        card.addView(patientView, fullTop(10));

        serviceStatusView = text("Live-Dienst: aus", 12, true, 0xFF526A8A);
        card.addView(serviceStatusView, fullTop(5));

        return card;
    }

    private View buildLoginCard() {
        LinearLayout card = card(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        TextView chevron = text("⌃", 22, true, 0xFF5D7596);

        LinearLayout header = sectionHeader(
                android.R.drawable.ic_menu_myplaces,
                "LibreLinkUp Konto",
                "Follower-Konto für die bestehende Freigabe.",
                chevron
        );

        card.addView(header);
        card.addView(content, fullTop(12));

        email = field(
                "Follower-E-Mail-Adresse",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
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
        password.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );
        password.setTransformationMethod(PasswordTransformationMethod.getInstance());
        password.setPadding(0, 0, dp(8), 0);

        passRow.addView(
                password,
                new LinearLayout.LayoutParams(0, dp(42), 1f)
        );

        TextView show = text("ANZEIGEN", 10, true, BLUE);
        show.setGravity(Gravity.CENTER);
        show.setPadding(dp(8), 0, dp(8), 0);
        show.setOnClickListener(v -> {
            int position = password.getSelectionStart();

            if (password.getTransformationMethod() == null) {
                password.setTransformationMethod(
                        PasswordTransformationMethod.getInstance()
                );
                show.setText("ANZEIGEN");
            } else {
                password.setTransformationMethod(null);
                show.setText("AUSBLENDEN");
            }

            password.setSelection(Math.max(0, position));
        });

        passRow.addView(
                show,
                new LinearLayout.LayoutParams(dp(88), dp(42))
        );

        passwordWrap.addView(passRow);
        content.addView(passwordWrap, fullTop(10));

        LinearLayout regionBox = new LinearLayout(this);
        regionBox.setOrientation(LinearLayout.VERTICAL);
        regionBox.setBackground(roundedWithStroke(FIELD_BG, BORDER, 16));
        regionBox.setPadding(dp(14), dp(7), dp(10), dp(7));

        regionBox.addView(text("Region", 11, true, 0xFF5B7190));

        region = new Spinner(this);
        String[] regions = {
                "AUTO", "DE", "EU", "EU2", "US", "FR",
                "CA", "AU", "AP", "AE", "JP", "IN", "LA", "RU"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                regions
        ) {
            @Override
            public View getView(
                    int position,
                    View convertView,
                    android.view.ViewGroup parent
            ) {
                TextView view = (TextView) super.getView(
                        position,
                        convertView,
                        parent
                );
                view.setTextColor(0xFF142847);
                view.setTextSize(17);
                view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                view.setPadding(0, 0, 0, 0);
                return view;
            }
        };

        region.setAdapter(adapter);
        region.setBackgroundColor(Color.TRANSPARENT);

        regionBox.addView(
                region,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        );

        content.addView(regionBox, fullTop(10));

        TextView hint = text(
                "AUTO erkennt die Abbott-Region automatisch. Nutze hier die Zugangsdaten des Follower-Kontos, "
                        + "nicht zwingend die Zugangsdaten der Person mit dem Sensor.",
                11,
                false,
                0xFF7186A1
        );
        hint.setLineSpacing(dp(1), 1.1f);
        content.addView(hint, fullTop(8));

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
                "Warnungen",
                "Eigene Grenzwerte für Handy und Watch.",
                chevron
        );

        card.addView(header);
        card.addView(content, fullTop(12));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout lowBox = smallNumberBox("Niedrig (mg/dL)");
        low = (EditText) lowBox.getChildAt(1);

        limits.addView(
                lowBox,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        View gap = new View(this);
        limits.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        LinearLayout highBox = smallNumberBox("Hoch (mg/dL)");
        high = (EditText) highBox.getChildAt(1);

        limits.addView(
                highBox,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        content.addView(limits);

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE8EFF7);

        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        dividerParams.topMargin = dp(14);

        content.addView(divider, dividerParams);

        LinearLayout carRow = new LinearLayout(this);
        carRow.setOrientation(LinearLayout.HORIZONTAL);
        carRow.setGravity(Gravity.CENTER_VERTICAL);
        carRow.setPadding(0, dp(12), 0, 0);

        ImageView carIcon = circleIcon(android.R.drawable.ic_menu_directions);
        carRow.addView(
                carIcon,
                new LinearLayout.LayoutParams(dp(44), dp(44))
        );

        LinearLayout carText = new LinearLayout(this);
        carText.setOrientation(LinearLayout.VERTICAL);
        carText.setPadding(dp(10), 0, dp(8), 0);

        carText.addView(
                text(
                        "Auto-Modus – Sprachwarnungen",
                        14,
                        true,
                        0xFF142847
                )
        );
        carText.addView(
                text(
                        "Grenzwertwarnungen im Android-Car-Modus vorlesen.",
                        11,
                        false,
                        0xFF7186A1
                ),
                wrapTop(2)
        );

        carRow.addView(
                carText,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        carVoice = new Switch(this);
        carVoice.setShowText(false);

        carRow.addView(
                carVoice,
                new LinearLayout.LayoutParams(dp(58), dp(44))
        );

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
        startButton.setText("▶   Speichern & Live starten");
        startButton.setTextSize(16);
        startButton.setTextColor(Color.WHITE);
        startButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        startButton.setAllCaps(false);
        startButton.setGravity(Gravity.CENTER);
        startButton.setBackground(gradientButton());
        startButton.setOnClickListener(v -> saveAndStart());

        box.addView(
                startButton,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(58)
                )
        );

        refreshButton = new Button(this);
        refreshButton.setText("↻   Jetzt aktualisieren");
        refreshButton.setTextSize(15);
        refreshButton.setTextColor(BLUE);
        refreshButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        refreshButton.setAllCaps(false);
        refreshButton.setGravity(Gravity.CENTER);
        refreshButton.setBackground(
                roundedWithStroke(Color.TRANSPARENT, 0xFF84BEF5, 18)
        );
        refreshButton.setOnClickListener(v -> refreshNow());

        box.addView(refreshButton, fullHeightTop(56, 10));

        Button logout = new Button(this);
        logout.setText("Abmelden & lokale Daten löschen");
        logout.setTextSize(13);
        logout.setTextColor(RED);
        logout.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logout.setAllCaps(false);
        logout.setBackgroundColor(Color.TRANSPARENT);
        logout.setOnClickListener(v -> confirmLogout());

        box.addView(logout, fullHeightTop(48, 8));

        return box;
    }

    private void saveAndStart() {
        String mail = email.getText().toString().trim();
        String pass = password.getText().toString();

        if (mail.isEmpty() || pass.isEmpty()) {
            toast("Follower-E-Mail und Passwort fehlen");
            return;
        }

        double lowValue = parseDouble(
                low.getText().toString(),
                70.0
        );
        double highValue = parseDouble(
                high.getText().toString(),
                180.0
        );

        if (lowValue >= highValue) {
            toast("Der niedrige Grenzwert muss unter dem hohen liegen");
            return;
        }

        try {
            SecurePrefs.putSecret(this, "email", mail);
            SecurePrefs.putSecret(this, "password", pass);
            SecurePrefs.putSecret(this, "session_token", "");

            SecurePrefs.prefs(this).edit()
                    .putString("region", String.valueOf(region.getSelectedItem()))
                    .putString("low", formatNumber(lowValue))
                    .putString("high", formatNumber(highValue))
                    .putBoolean("car_voice", carVoice.isChecked())
                    .putBoolean("enabled", true)
                    .remove("session_base_url")
                    .remove("session_expires_ms")
                    .remove("session_account_hash")
                    .remove("session_patient_id")
                    .putString("last_error", "")
                    .apply();

            stopService(new Intent(this, LibreService.class));
            startServiceCompat(new Intent(this, LibreService.class));

            toast("LibreMirror Live gestartet");
            renderState();
        } catch (Exception e) {
            toast("Speichern fehlgeschlagen");
        }
    }

    private void refreshNow() {
        if (!SecurePrefs.prefs(this).getBoolean("enabled", false)) {
            toast("Live-Dienst zuerst starten");
            return;
        }

        Intent intent = new Intent(this, LibreService.class);
        intent.setAction(LibreService.ACTION_REFRESH);
        startServiceCompat(intent);
        toast("Aktualisierung angefordert");
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("LibreMirror zurücksetzen")
                .setMessage(
                        "Zugangsdaten, Session, Verlauf und Einstellungen werden nur auf diesem Gerät gelöscht."
                )
                .setNegativeButton("Abbrechen", null)
                .setPositiveButton("Löschen", (dialog, which) -> logoutAndClear())
                .show();
    }

    private void logoutAndClear() {
        Intent stop = new Intent(this, LibreService.class);
        stop.setAction(LibreService.ACTION_STOP);
        startServiceCompat(stop);

        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancelAll();

        SecurePrefs.clearAll(this);

        email.setText("");
        password.setText("");
        low.setText("70");
        high.setText("180");
        region.setSelection(0);
        carVoice.setChecked(true);

        toast("Lokale LibreMirror-Daten gelöscht");
        renderState();
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

    private void renderState() {
        SharedPreferences prefs = SecurePrefs.prefs(this);

        String value = prefs.getString("last_value", "");
        int trend = prefs.getInt("last_trend", 0);
        long sensorMs = prefs.getLong("last_sensor_ms", 0L);
        long syncMs = prefs.getLong("last_success_ms", 0L);
        String patient = prefs.getString("patient_name", "");
        String error = prefs.getString("last_error", "");
        boolean enabled = prefs.getBoolean("enabled", false);

        long now = System.currentTimeMillis();
        boolean stale = sensorMs > 0L && now - sensorMs > 5L * 60L * 1000L;

        if (!value.isEmpty()) {
            valueView.setText(value);
            unitView.setText("mg/dL");
            trendArrowView.setText(LibreApiClient.arrow(trend));
            trendLabelView.setText(LibreApiClient.trendLabel(trend));

            if (error.isEmpty() && !stale) {
                setConnectionChip("●  Verbunden", GREEN, 0xFFE6F8EF);
            } else if (stale) {
                setConnectionChip("●  Wert veraltet", ORANGE, 0xFFFFF3D9);
            } else {
                setConnectionChip("●  Letzter Wert", ORANGE, 0xFFFFF3D9);
            }
        } else {
            valueView.setText("—");
            unitView.setText("mg/dL");
            trendArrowView.setText("→");
            trendLabelView.setText("Kein Wert");
            setConnectionChip("●  Nicht verbunden", RED, 0xFFFFEBEE);
        }

        updatedView.setText(
                syncMs > 0L
                        ? "Sync: " + time(syncMs)
                        : "Sync: —"
        );

        sensorAgeView.setText(
                sensorMs > 0L
                        ? "Sensor: vor " + ageText(now - sensorMs)
                        : "Sensor: —"
        );

        if (error.isEmpty()) {
            errorView.setVisibility(View.GONE);
            errorView.setText("");
        } else {
            errorView.setVisibility(View.VISIBLE);
            errorView.setText(error);
        }

        patientView.setText(
                patient.isEmpty()
                        ? "Freigabe: —"
                        : "Freigabe: " + patient
        );

        serviceStatusView.setText(
                enabled
                        ? "Live-Dienst: aktiv • ca. alle 60 Sekunden"
                        : "Live-Dienst: aus"
        );
        serviceStatusView.setTextColor(enabled ? GREEN : 0xFF526A8A);

        startButton.setText(
                enabled
                        ? "●   Einstellungen speichern"
                        : "▶   Speichern & Live starten"
        );

        refreshButton.setEnabled(enabled);
        refreshButton.setAlpha(enabled ? 1f : 0.5f);

        chartView.setValues(
                readHistory(prefs.getString("history_values", ""))
        );
    }

    private void setConnectionChip(String label, int color, int background) {
        connectionChip.setText(label);
        connectionChip.setTextColor(color);
        connectionChip.setBackground(rounded(background, 18));
    }

    private List<Float> readHistory(String raw) {
        List<Float> result = new ArrayList<>();

        if (raw == null || raw.trim().isEmpty()) return result;

        String[] parts = raw.split(";");

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

        GradientDrawable background;

        if (gradient) {
            background = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFFFFFFF, 0xFFF2FCFF}
            );
            background.setCornerRadius(dp(24));
            background.setStroke(dp(1), 0xFFE3EDF7);
        } else {
            background = roundedWithStroke(
                    Color.WHITE,
                    0xFFE3EDF7,
                    24
            );
        }

        card.setBackground(background);
        return card;
    }

    private LinearLayout sectionHeader(
            int iconRes,
            String title,
            String subtitle,
            TextView chevron
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = circleIcon(iconRes);
        row.addView(
                icon,
                new LinearLayout.LayoutParams(dp(48), dp(48))
        );

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, dp(8), 0);
        texts.addView(text(title, 18, true, 0xFF0B1D3D));
        texts.addView(
                text(subtitle, 11, false, 0xFF7186A1),
                wrapTop(2)
        );

        row.addView(
                texts,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        chevron.setGravity(Gravity.CENTER);
        row.addView(
                chevron,
                new LinearLayout.LayoutParams(dp(34), dp(40))
        );

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
        editText.setBackground(
                roundedWithStroke(FIELD_BG, BORDER, 16)
        );
        editText.setMinHeight(dp(58));
        return editText;
    }

    private LinearLayout smallNumberBox(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(9), dp(12), dp(8));
        box.setBackground(
                roundedWithStroke(FIELD_BG, BORDER, 16)
        );

        box.addView(text(label, 11, true, 0xFF5B7190));

        EditText value = new EditText(this);
        value.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
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

    private TextView text(
            String value,
            int sp,
            boolean bold,
            int color
    ) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);

        if (bold) {
            textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return textView;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedWithStroke(
            int color,
            int strokeColor,
            int radiusDp
    ) {
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

    private LinearLayout.LayoutParams fullHeightTop(
            int heightDp,
            int topDp
    ) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(float value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private void startServiceCompat(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            toast("Live-Dienst konnte nicht gestartet werden");
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    10
            );
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(
                    value == null ? "" : value.replace(',', '.')
            );
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private static String time(long timestamp) {
        return new SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
        ).format(new Date(timestamp));
    }

    private static String ageText(long ageMs) {
        long minutes = Math.max(0L, ageMs / 60_000L);
        if (minutes <= 0L) return "< 1 Min.";
        if (minutes == 1L) return "1 Min.";
        return minutes + " Min.";
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
