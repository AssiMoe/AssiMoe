package de.assimoe.libremirror;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

public class WidgetConfigActivity extends Activity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Spinner size;
    private Spinner opacity;
    private Switch showTitle;
    private Switch showStatus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        setResult(RESULT_CANCELED);

        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            appWidgetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
            );
        }

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(20));
        root.setBackgroundColor(0xFF07111D);

        root.addView(text("Widget konfigurieren", 24, true, Color.WHITE));
        root.addView(text(
                "Diese Einstellungen gelten nur für dieses Widget.",
                12,
                false,
                0xFF9CB0C2
        ), top(5));

        root.addView(text("Textgröße", 12, true, 0xFFB6C7D9), top(18));
        size = spinner(new String[]{"Kompakt", "Normal", "Groß"});
        root.addView(size, top(6));

        root.addView(text("Transparenz", 12, true, 0xFFB6C7D9), top(16));
        opacity = spinner(new String[]{"60 %", "80 %", "100 %"});
        root.addView(opacity, top(6));

        showTitle = new Switch(this);
        showTitle.setText("LibreMirror-Titel anzeigen");
        showTitle.setTextColor(Color.WHITE);
        showTitle.setChecked(true);
        root.addView(showTitle, top(16));

        showStatus = new Switch(this);
        showStatus.setText("Alter/Status anzeigen");
        showStatus.setTextColor(Color.WHITE);
        showStatus.setChecked(true);
        root.addView(showStatus, top(8));

        load();

        Button save = new Button(this);
        save.setText("Widget speichern");
        save.setTextColor(Color.WHITE);
        save.setTextSize(15);
        save.setAllCaps(false);
        save.setBackgroundColor(0xFF148DFF);
        save.setOnClickListener(v -> saveAndFinish());
        root.addView(save, heightTop(54, 24));

        setContentView(root);
    }

    private void load() {
        android.content.SharedPreferences p = SecurePrefs.prefs(this);
        size.setSelection(p.getInt("widget_size_" + appWidgetId, 1));
        opacity.setSelection(p.getInt("widget_opacity_" + appWidgetId, 1));
        showTitle.setChecked(p.getBoolean("widget_title_" + appWidgetId, true));
        showStatus.setChecked(p.getBoolean("widget_status_" + appWidgetId, true));
    }

    private void saveAndFinish() {
        SecurePrefs.prefs(this).edit()
                .putInt("widget_size_" + appWidgetId, size.getSelectedItemPosition())
                .putInt("widget_opacity_" + appWidgetId, opacity.getSelectedItemPosition())
                .putBoolean("widget_title_" + appWidgetId, showTitle.isChecked())
                .putBoolean("widget_status_" + appWidgetId, showStatus.isChecked())
                .apply();

        LibreMirrorWidgetProvider.updateAll(this);

        Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                values
        ) {
            @Override
            public android.view.View getView(
                    int position,
                    android.view.View convertView,
                    android.view.ViewGroup parent
            ) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Color.WHITE);
                view.setTextSize(16);
                return view;
            }
        };
        spinner.setAdapter(adapter);
        return spinner;
    }

    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams top(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.topMargin = dp(topDp);
        return p;
    }

    private LinearLayout.LayoutParams heightTop(int heightDp, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
        );
        p.topMargin = dp(topDp);
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
