package de.assimoe.libremirror;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class UpdateManager {
    private UpdateManager() {}

    public static Result check(String manifestUrl, int currentVersionCode) throws Exception {
        if (manifestUrl == null || manifestUrl.trim().isEmpty()) {
            return new Result(false, currentVersionCode, "", "", "", "Keine Update-URL konfiguriert.");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(manifestUrl.trim()).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("Update-Manifest HTTP " + code);
        }

        StringBuilder body = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                connection.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = br.readLine()) != null) body.append(line);
        } finally {
            connection.disconnect();
        }

        JSONObject json = new JSONObject(body.toString());
        int versionCode = json.optInt("versionCode", currentVersionCode);
        String versionName = json.optString("versionName", "");
        String apkUrl = json.optString("apkUrl", "");
        String sha256 = json.optString("sha256", "").toLowerCase(Locale.ROOT);
        String notes = json.optString("notes", "");

        return new Result(
                versionCode > currentVersionCode && !apkUrl.isEmpty(),
                versionCode,
                versionName,
                apkUrl,
                sha256,
                notes
        );
    }

    public static File download(Context context, Result result) throws Exception {
        if (result == null || result.apkUrl.isEmpty()) {
            throw new Exception("Keine APK-URL vorhanden.");
        }

        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("Update-Verzeichnis konnte nicht erstellt werden.");
        }

        File apk = new File(dir, "LibreMirror-update.apk");

        HttpURLConnection connection = (HttpURLConnection) new URL(result.apkUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("APK-Download HTTP " + code);
        }

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(apk)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        } finally {
            connection.disconnect();
        }

        if (!result.sha256.isEmpty()) {
            String actual = sha256(apk);
            if (!actual.equalsIgnoreCase(result.sha256)) {
                apk.delete();
                throw new Exception("SHA-256 der Update-APK stimmt nicht.");
            }
        }

        return apk;
    }

    public static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent unknownSourcesSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public static Intent installIntent(Context context, File apk) {
        Uri uri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".files",
                apk
        );

        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        return intent;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    public static final class Result {
        public final boolean updateAvailable;
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final String notes;

        Result(
                boolean updateAvailable,
                int versionCode,
                String versionName,
                String apkUrl,
                String sha256,
                String notes
        ) {
            this.updateAvailable = updateAvailable;
            this.versionCode = versionCode;
            this.versionName = versionName == null ? "" : versionName;
            this.apkUrl = apkUrl == null ? "" : apkUrl;
            this.sha256 = sha256 == null ? "" : sha256;
            this.notes = notes == null ? "" : notes;
        }
    }
}
