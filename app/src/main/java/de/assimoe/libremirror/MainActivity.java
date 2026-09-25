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
import android.widget.FrameLayout;
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
    private static final int BLUE = 0xFF149CFF;
    private static final int CYAN = 0xFF22D3EE;
    private static final int GREEN = 0xFF19D38A;
    private static final int RED = 0xFFFF6677;
    private static final int ORANGE = 0xFFFFB020;

    private boolean darkMode;

    private int pageBg;
    private int surface;
    private int surface2;
    private int fieldBg;
    private int border;
    private int textPrimary;
    private int textSecondary;
    private int textMuted;
    private int navBg;

    private FrameLayout pageContainer;
    private View nowPage;
    private View historyPage;
    private View settingsPage;

    private LinearLayout navNow;
    private LinearLayout navHistory;
    private LinearLayout navSettings;
    private ImageView navNowIcon;
    private ImageView navHistoryIcon;
    private ImageView navSettingsIcon;
    private TextView navNowText;
    private TextView navHistoryText;
    private TextView navSettingsText;

    private EditText email;
    private EditText password;
    private EditText low;
    private EditText high;
    private Spinner region;
    private Switch carVoice;
    private Switch darkModeSwitch;

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

    private TextView historyValueView;
    private TextView historyTrendView;
    private TextView historyRangeView;

    private Button startButton;
    private Button refreshButton;

    private GlucoseChartView compactChart;
    private FullGlucoseChartView fullChart;

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

        darkMode = SecurePrefs.prefs(this).getBoolean("dark_mode", true);
        applyPalette();
        applySystemBars();

        setContentView(buildUi());
        loadSettings();
        requestNotificationPermission();
        showPage(0);
        renderState();
    }

    private void applyPalette() {
        if (darkMode) {
            pageBg = 0xFF07111D;
            surface = 0xFF0D1B2A;
            surface2 = 0xFF102337;
            fieldBg = 0xFF11263A;
            border = 0xFF203A51;
            textPrimary = 0xFFF4F8FC;
            textSecondary = 0xFFB6C7D9;
            textMuted = 0xFF7890A6;
            navBg = 0xFF091827;
        } else {
            pageBg = 0xFFF4F8FC;
            surface = 0xFFFFFFFF;
            surface2 = 0xFFF7FBFF;
            fieldBg = 0xFFF6F9FD;
            border = 0xFFDCE8F4;
            textPrimary = 0xFF0A1E38;
            textSecondary = 0xFF58708E;
            textMuted = 0xFF8091A7;
            navBg = 0xFFFFFFFF;
        }
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(pageBg);
        getWindow().setNavigationBarColor(navBg);

        if (!darkMode) {
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
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pageBg);

        View header = buildHeader();
        root.addView(
                header,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        pageContainer = new FrameLayout(this);
        nowPage = buildNowPage();
        historyPage = buildHistoryPage();
        settingsPage = buildSettingsPage();

        pageContainer.addView(nowPage);
        pageContainer.addView(historyPage);
        pageContainer.addView(settingsPage);

        root.addView(
                pageContainer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        root.addView(
                buildBottomNav(),
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(78)
                )
        );

        return root;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(14), dp(20), dp(10));
        row.setBackgroundColor(pageBg);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        row.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView title = text("LibreMirror", 27, true, textPrimary);
        title.setPadding(dp(12), 0, 0, 0);
        row.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        TextView version = text(BuildConfig.VERSION_NAME, 11, true, BLUE);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(10), dp(6), dp(10), dp(6));
        version.setBackground(rounded(
                darkMode ? 0xFF0B2A45 : 0xFFE5F3FF,
                18
        ));
        row.addView(version);

        return row;
    }

    private View buildNowPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageRoot();

        root.addView(buildGlucoseCard());

        LinearLayout statusCard = card(false);
        statusCard.addView(sectionHeader(
                R.drawable.ic_nav_now,
                "LibreMirror Status",
                "Live-Dienst und Freigabe auf einen Blick."
        ));

        patientView = text("Freigabe: —", 13, true, textSecondary);
        statusCard.addView(patientView, fullTop(14));

        serviceStatusView = text("Live-Dienst: aus", 13, true, textSecondary);
        statusCard.addView(serviceStatusView, fullTop(7));

        Button quickRefresh = secondaryButton("↻   Jetzt aktualisieren");
        quickRefresh.setOnClickListener(v -> refreshNow());
        statusCard.addView(quickRefresh, fullHeightTop(50, 14));

        root.addView(statusCard, fullTop(14));

        scroll.addView(root);
        return scroll;
    }

    private View buildGlucoseCard() {
        LinearLayout card = card(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = text("GLUKOSE LIVE", 12, true, textSecondary);
        label.setLetterSpacing(0.08f);

        top.addView(
                label,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        TextView live = text("LIVE", 10, true, BLUE);
        live.setPadding(dp(10), dp(5), dp(10), dp(5));
        live.setBackground(rounded(
                darkMode ? 0xFF0B2A45 : 0xFFE5F3FF,
                14
        ));
        top.addView(live);

        card.addView(top);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.HORIZONTAL);
        main.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(main, fullTop(10));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        LinearLayout valueRow = new LinearLayout(this);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setGravity(Gravity.BOTTOM);

        valueView = text("—", 62, true, darkMode ? 0xFFF5FAFF : 0xFF06366F);
        valueView.setIncludeFontPadding(false);
        valueRow.addView(valueView);

        unitView = text("mg/dL", 20, true, textSecondary);
        unitView.setPadding(dp(8), 0, 0, dp(8));
        valueRow.addView(unitView);

        left.addView(valueRow);

        connectionChip = text("●  Nicht verbunden", 13, true, RED);
        connectionChip.setGravity(Gravity.CENTER);
        connectionChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        connectionChip.setBackground(rounded(
                darkMode ? 0xFF321C27 : 0xFFFFEBEE,
                18
        ));
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

        trendArrowView = text("→", 52, false, GREEN);
        trendArrowView.setGravity(Gravity.CENTER);
        trend.addView(
                trendArrowView,
                new LinearLayout.LayoutParams(dp(90), dp(62))
        );

        trendLabelView = text("Kein Wert", 14, true, GREEN);
        trendLabelView.setGravity(Gravity.CENTER);
        trend.addView(trendLabelView);

        main.addView(
                trend,
                new LinearLayout.LayoutParams(
                        dp(104),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        compactChart = new GlucoseChartView(this);
        compactChart.setDarkMode(darkMode);
        card.addView(
                compactChart,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(105)
                )
        );

        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);

        updatedView = text("Sync: —", 12, false, textMuted);
        meta.addView(
                updatedView,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        sensorAgeView = text("Sensor: —", 12, false, textMuted);
        sensorAgeView.setGravity(Gravity.END);
        meta.addView(sensorAgeView);

        card.addView(meta, fullTop(7));

        errorView = text("", 12, false, RED);
        errorView.setVisibility(View.GONE);
        errorView.setLineSpacing(dp(1), 1.08f);
        card.addView(errorView, fullTop(8));

        return card;
    }

    private View buildHistoryPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageRoot();

        TextView heading = text("Verlauf", 24, true, textPrimary);
        root.addView(heading);

        TextView sub = text(
                "Glukoseverlauf aus deiner LibreLinkUp-Freigabe.",
                12,
                false,
                textSecondary
        );
        root.addView(sub, wrapTop(3));

        LinearLayout summary = card(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        historyValueView = text("—", 40, true, textPrimary);
        row.addView(historyValueView);

        TextView unit = text("mg/dL", 16, true, textSecondary);
        unit.setPadding(dp(8), 0, 0, dp(5));
        row.addView(unit);

        historyTrendView = text("→", 34, false, GREEN);
        historyTrendView.setGravity(Gravity.END);
        row.addView(
                historyTrendView,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        summary.addView(row);

        historyRangeView = text(
                "Zielbereich 70–180 mg/dL",
                12,
                true,
                textSecondary
        );
        summary.addView(historyRangeView, fullTop(4));

        root.addView(summary, fullTop(14));

        LinearLayout chartCard = card(false);
        TextView chartTitle = text("Tagesverlauf", 17, true, textPrimary);
        chartCard.addView(chartTitle);

        TextView chartSub = text(
                "Blau = Glukose • Fläche = Zielbereich • gestrichelt = Grenzwerte",
                11,
                false,
                textMuted
        );
        chartCard.addView(chartSub, wrapTop(3));

        fullChart = new FullGlucoseChartView(this);
        fullChart.setDarkMode(darkMode);
        chartCard.addView(
                fullChart,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(390)
                )
        );

        root.addView(chartCard, fullTop(14));

        TextView note = text(
                "Der Verlauf dient der Übersicht. Für Therapie- und Dosierungsentscheidungen weiterhin die offizielle Libre-App verwenden.",
                11,
                false,
                textMuted
        );
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(dp(1), 1.08f);
        root.addView(note, fullTop(14));

        scroll.addView(root);
        return scroll;
    }

    private View buildSettingsPage() {
        ScrollView scroll = pageScroll();
        LinearLayout root = pageRoot();

        TextView heading = text("Einstellungen", 24, true, textPrimary);
        root.addView(heading);

        root.addView(buildAccountSettings(), fullTop(14));
        root.addView(buildAlertSettings(), fullTop(14));
        root.addView(buildAppearanceSettings(), fullTop(14));
        root.addView(buildServiceSettings(), fullTop(14));

        TextView footer = text(
                "Private LibreMirror-Version • Zugangsdaten und Session werden lokal verschlüsselt gespeichert.",
                11,
                false,
                textMuted
        );
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, fullTop(18));

        scroll.addView(root);
        return scroll;
    }

    private View buildAccountSettings() {
        LinearLayout card = card(false);

        card.addView(sectionHeader(
                android.R.drawable.ic_menu_myplaces,
                "LibreLinkUp Konto",
                "Follower-Konto für die bestehende Freigabe."
        ));

        email = field(
                "Follower-E-Mail-Adresse",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        );
        card.addView(email, fullTop(14));

        LinearLayout passwordWrap = new LinearLayout(this);
        passwordWrap.setOrientation(LinearLayout.VERTICAL);
        passwordWrap.setBackground(roundedWithStroke(fieldBg, border, 16));
        passwordWrap.setPadding(dp(14), dp(7), dp(10), dp(7));

        passwordWrap.addView(text("Passwort", 11, true, textSecondary));

        LinearLayout passRow = new LinearLayout(this);
        passRow.setOrientation(LinearLayout.HORIZONTAL);
        passRow.setGravity(Gravity.CENTER_VERTICAL);

        password = new EditText(this);
        password.setTextSize(17);
        password.setTextColor(textPrimary);
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
                new LinearLayout.LayoutParams(dp(92), dp(42))
        );

        passwordWrap.addView(passRow);
        card.addView(passwordWrap, fullTop(10));

        LinearLayout regionBox = new LinearLayout(this);
        regionBox.setOrientation(LinearLayout.VERTICAL);
        regionBox.setBackground(roundedWithStroke(fieldBg, border, 16));
        regionBox.setPadding(dp(14), dp(7), dp(10), dp(7));

        regionBox.addView(text("Region", 11, true, textSecondary));

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
                view.setTextColor(textPrimary);
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

        card.addView(regionBox, fullTop(10));

        return card;
    }

    private View buildAlertSettings() {
        LinearLayout card = card(false);

        card.addView(sectionHeader(
                android.R.drawable.ic_dialog_alert,
                "Warnungen",
                "Eigene Grenzwerte und Auto-Sprachausgabe."
        ));

        LinearLayout limits = new LinearLayout(this);
        limits.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout lowBox = smallNumberBox("Niedrig");
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

        LinearLayout highBox = smallNumberBox("Hoch");
        high = (EditText) highBox.getChildAt(1);
        limits.addView(
                highBox,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        card.addView(limits, fullTop(14));

        carVoice = new Switch(this);
        card.addView(
                settingSwitchRow(
                        R.drawable.ic_car,
                        "Auto-Sprachwarnungen",
                        "Grenzwertwarnungen im Car-Modus vorlesen.",
                        carVoice
                ),
                fullTop(12)
        );

        return card;
    }

    private View buildAppearanceSettings() {
        LinearLayout card = card(false);

        card.addView(sectionHeader(
                R.drawable.ic_theme,
                "Darstellung",
                "LibreMirror nach deinem Geschmack."
        ));

        darkModeSwitch = new Switch(this);
        darkModeSwitch.setChecked(darkMode);

        View row = settingSwitchRow(
                R.drawable.ic_theme,
                "Dark Mode",
                darkMode ? "Dunkles LibreMirror-Design aktiv." : "Helles LibreMirror-Design aktiv.",
                darkModeSwitch
        );

        darkModeSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked == darkMode) return;

            SecurePrefs.prefs(this).edit()
                    .putBoolean("dark_mode", checked)
                    .apply();

            recreate();
        });

        card.addView(row, fullTop(10));
        return card;
    }

    private View buildServiceSettings() {
        LinearLayout card = card(false);

        card.addView(sectionHeader(
                R.drawable.ic_live,
                "Live-Dienst",
                "Synchronisierung, Watch und lokale Daten."
        ));

        startButton = primaryButton("▶   Speichern & Live starten");
        startButton.setOnClickListener(v -> saveAndStart());
        card.addView(startButton, fullHeightTop(54, 14));

        refreshButton = secondaryButton("↻   Jetzt aktualisieren");
        refreshButton.setOnClickListener(v -> refreshNow());
        card.addView(refreshButton, fullHeightTop(50, 10));

        Button logout = secondaryButton("Abmelden & lokale Daten löschen");
        logout.setTextColor(RED);
        logout.setOnClickListener(v -> confirmLogout());
        card.addView(logout, fullHeightTop(50, 10));

        return card;
    }

    private View buildBottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(5), dp(8), dp(5));
        nav.setBackgroundColor(navBg);
        nav.setElevation(dp(10));

        navNow = navItem(R.drawable.ic_nav_now, "Jetzt", 0);
        navHistory = navItem(R.drawable.ic_nav_history, "Verlauf", 1);
        navSettings = navItem(R.drawable.ic_nav_settings, "Einstellungen", 2);

        navNowIcon = (ImageView) navNow.getChildAt(0);
        navNowText = (TextView) navNow.getChildAt(1);

        navHistoryIcon = (ImageView) navHistory.getChildAt(0);
        navHistoryText = (TextView) navHistory.getChildAt(1);

        navSettingsIcon = (ImageView) navSettings.getChildAt(0);
        navSettingsText = (TextView) navSettings.getChildAt(1);

        nav.addView(navNow, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        nav.addView(navHistory, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        nav.addView(navSettings, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        return nav;
    }

    private LinearLayout navItem(int iconRes, String label, int page) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(3), dp(4), dp(3));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(textMuted);
        item.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView text = text(label, 11, true, textMuted);
        text.setGravity(Gravity.CENTER);
        item.addView(text, wrapTop(2));

        item.setOnClickListener(v -> showPage(page));
        return item;
    }

    private void showPage(int page) {
        nowPage.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        historyPage.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(page == 2 ? View.VISIBLE : View.GONE);

        tintNav(navNowIcon, navNowText, page == 0);
        tintNav(navHistoryIcon, navHistoryText, page == 1);
        tintNav(navSettingsIcon, navSettingsText, page == 2);
    }

    private void tintNav(ImageView icon, TextView label, boolean active) {
        int color = active ? BLUE : textMuted;
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    private void saveAndStart() {
        String mail = email.getText().toString().trim();
        String pass = password.getText().toString();

        if (mail.isEmpty() || pass.isEmpty()) {
            toast("Follower-E-Mail und Passwort fehlen");
            return;
        }

        double lowValue = parseDouble(low.getText().toString(), 70.0);
        double highValue = parseDouble(high.getText().toString(), 180.0);

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
            showPage(0);
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

        boolean keepDark = darkMode;
        SecurePrefs.clearAll(this);
        SecurePrefs.prefs(this).edit().putBoolean("dark_mode", keepDark).apply();

        email.setText("");
        password.setText("");
        low.setText("70");
        high.setText("180");
        region.setSelection(0);
        carVoice.setChecked(true);

        toast("Lokale LibreMirror-Daten gelöscht");
        showPage(0);
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

        double lowValue = parseDouble(prefs.getString("low", "70"), 70.0);
        double highValue = parseDouble(prefs.getString("high", "180"), 180.0);

        long now = System.currentTimeMillis();
        boolean stale = sensorMs > 0L && now - sensorMs > 5L * 60L * 1000L;

        if (!value.isEmpty()) {
            valueView.setText(value);
            historyValueView.setText(value);
            trendArrowView.setText(LibreApiClient.arrow(trend));
            trendLabelView.setText(LibreApiClient.trendLabel(trend));
            historyTrendView.setText(LibreApiClient.arrow(trend));

            if (error.isEmpty() && !stale) {
                setConnectionChip("●  Verbunden", GREEN, darkMode ? 0xFF113D31 : 0xFFE6F8EF);
            } else if (stale) {
                setConnectionChip("●  Wert veraltet", ORANGE, darkMode ? 0xFF3C3116 : 0xFFFFF3D9);
            } else {
                setConnectionChip("●  Letzter Wert", ORANGE, darkMode ? 0xFF3C3116 : 0xFFFFF3D9);
            }
        } else {
            valueView.setText("—");
            historyValueView.setText("—");
            trendArrowView.setText("→");
            trendLabelView.setText("Kein Wert");
            historyTrendView.setText("→");
            setConnectionChip("●  Nicht verbunden", RED, darkMode ? 0xFF321C27 : 0xFFFFEBEE);
        }

        updatedView.setText(syncMs > 0L ? "Sync: " + time(syncMs) : "Sync: —");
        sensorAgeView.setText(sensorMs > 0L ? "Sensor: vor " + ageText(now - sensorMs) : "Sensor: —");

        if (error.isEmpty()) {
            errorView.setVisibility(View.GONE);
            errorView.setText("");
        } else {
            errorView.setVisibility(View.VISIBLE);
            errorView.setText(error);
        }

        patientView.setText(patient.isEmpty() ? "Freigabe: —" : "Freigabe: " + patient);

        serviceStatusView.setText(
                enabled
                        ? "Live-Dienst: aktiv • ca. alle 60 Sekunden"
                        : "Live-Dienst: aus"
        );
        serviceStatusView.setTextColor(enabled ? GREEN : textSecondary);

        startButton.setText(
                enabled
                        ? "●   Einstellungen speichern"
                        : "▶   Speichern & Live starten"
        );

        refreshButton.setEnabled(enabled);
        refreshButton.setAlpha(enabled ? 1f : 0.5f);

        historyRangeView.setText(
                "Zielbereich " + formatNumber(lowValue) + "–" + formatNumber(highValue) + " mg/dL"
        );

        List<Float> history = readHistory(prefs.getString("history_values", ""));
        compactChart.setValues(history);

        HistoryData historyData = readHistoryPoints(
                prefs.getString("history_points", ""),
                history
        );
        fullChart.setData(
                historyData.times,
                historyData.values,
                (float) lowValue,
                (float) highValue
        );
    }

    private void setConnectionChip(String label, int color, int background) {
        connectionChip.setText(label);
        connectionChip.setTextColor(color);
        connectionChip.setBackground(rounded(background, 18));
    }

    private HistoryData readHistoryPoints(String raw, List<Float> fallback) {
        HistoryData result = new HistoryData();

        if (raw != null && !raw.trim().isEmpty()) {
            String[] points = raw.split(";");

            for (String point : points) {
                String[] parts = point.split(",");

                if (parts.length != 2) continue;

                try {
                    result.times.add(Long.parseLong(parts[0]));
                    result.values.add(Float.parseFloat(parts[1]));
                } catch (Exception ignored) {
                }
            }
        }

        if (result.values.size() < 2 && fallback != null) {
            result.times.clear();
            result.values.clear();

            long now = System.currentTimeMillis();
            long step = 5L * 60L * 1000L;
            long start = now - Math.max(0, fallback.size() - 1) * step;

            for (int i = 0; i < fallback.size(); i++) {
                result.times.add(start + i * step);
                result.values.add(fallback.get(i));
            }
        }

        return result;
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

    private ScrollView pageScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(pageBg);
        scroll.setClipToPadding(false);
        return scroll;
    }

    private LinearLayout pageRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(24));
        return root;
    }

    private LinearLayout card(boolean gradient) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setElevation(dp(3));

        GradientDrawable background;

        if (gradient) {
            background = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    darkMode
                            ? new int[]{0xFF0B1927, 0xFF0D2435}
                            : new int[]{0xFFFFFFFF, 0xFFF2FCFF}
            );
            background.setCornerRadius(dp(24));
            background.setStroke(dp(1), border);
        } else {
            background = roundedWithStroke(surface, border, 24);
        }

        card.setBackground(background);
        return card;
    }

    private LinearLayout sectionHeader(int iconRes, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = circleIcon(iconRes);
        row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);

        texts.addView(text(title, 17, true, textPrimary));
        texts.addView(
                text(subtitle, 11, false, textSecondary),
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

        return row;
    }

    private View settingSwitchRow(
            int iconRes,
            String title,
            String subtitle,
            Switch control
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = circleIcon(iconRes);
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, dp(8), 0);

        labels.addView(text(title, 14, true, textPrimary));
        labels.addView(text(subtitle, 11, false, textSecondary), wrapTop(2));

        row.addView(
                labels,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                )
        );

        row.addView(control, new LinearLayout.LayoutParams(dp(58), dp(44)));
        return row;
    }

    private ImageView circleIcon(int res) {
        ImageView icon = new ImageView(this);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(rounded(
                darkMode ? 0xFF0B2A45 : 0xFFE4F2FF,
                50
        ));

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
        editText.setHintTextColor(textMuted);
        editText.setTextColor(textPrimary);
        editText.setTextSize(17);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setPadding(dp(14), 0, dp(14), 0);
        editText.setBackground(roundedWithStroke(fieldBg, border, 16));
        editText.setMinHeight(dp(58));
        return editText;
    }

    private LinearLayout smallNumberBox(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(9), dp(12), dp(8));
        box.setBackground(roundedWithStroke(fieldBg, border, 16));

        box.addView(text(label + " (mg/dL)", 11, true, textSecondary));

        EditText value = new EditText(this);
        value.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        value.setTextSize(21);
        value.setTextColor(textPrimary);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setSingleLine(true);
        value.setBackgroundColor(Color.TRANSPARENT);
        value.setPadding(0, 0, 0, 0);
        value.setMinHeight(dp(40));

        box.addView(value);
        return box;
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(gradientButton());
        return button;
    }

    private Button secondaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(BLUE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundedWithStroke(
                Color.TRANSPARENT,
                darkMode ? 0xFF285578 : 0xFF84BEF5,
                16
        ));
        return button;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
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
                new int[]{0xFF19B5E8, 0xFF148DFF, 0xFF0C67D8}
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

    private static final class HistoryData {
        final List<Long> times = new ArrayList<>();
        final List<Float> values = new ArrayList<>();
    }
}
