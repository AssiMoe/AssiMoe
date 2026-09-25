package de.assimoe.libremirror;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class LibreApiClient {
    private static final String VERSION = "4.17.0";

    private final String configuredRegion;
    private String baseUrl;
    private String userToken;
    private String accountId;
    private String country = "DE";

    public LibreApiClient(String region) {
        configuredRegion = region == null ? "AUTO" : region.trim().toUpperCase(Locale.ROOT);
        baseUrl = hostFor(configuredRegion);
    }

    public Reading fetch(String email, String password) throws Exception {
        if (userToken == null || accountId == null) {
            loginDirect(email, password);
        }

        try {
            return fetchDirectMeasurements();
        } catch (AuthException e) {
            userToken = null;
            accountId = null;
            loginDirect(email, password);
            return fetchDirectMeasurements();
        }
    }

    private void loginDirect(String email, String password) throws Exception {
        if ("AUTO".equals(configuredRegion)) {
            discoverRegion(email, password);
        }

        JSONObject payload = new JSONObject();
        payload.put("Domain", "Libreview");
        payload.put("GatewayType", "LinkUp.Android");
        payload.put("Password", password);
        payload.put("UserName", email);

        JSONObject response = legacyRequest(
                "POST",
                baseUrl + "/lsl/api/nisperson/getauthenticateduser",
                payload,
                false
        );

        if (response.optInt("status", -1) != 0) {
            throw new Exception("LibreView-Login fehlgeschlagen (Status "
                    + response.optInt("status", -1) + ").");
        }

        JSONObject result = response.optJSONObject("result");
        if (result == null) {
            throw new Exception("LibreView hat keine Kontodaten geliefert.");
        }

        userToken = result.optString("UserToken", "");
        accountId = result.optString("AccountId", "");
        country = result.optString("Country", "DE");

        if (userToken.isEmpty() || accountId.isEmpty()) {
            throw new Exception("LibreView-Login unvollständig: UserToken oder Account-ID fehlt.");
        }
    }

    private void discoverRegion(String email, String password) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("email", email);
        payload.put("password", password);

        JSONObject response;
        try {
            response = modernRequest("POST", "https://api.libreview.io/auth/login", payload);
        } catch (Exception e) {
            baseUrl = "https://api-de.libreview.io";
            return;
        }

        JSONObject data = response.optJSONObject("data");
        if (data == null) {
            baseUrl = "https://api-de.libreview.io";
            return;
        }

        String region = data.optString("region", "");
        if (data.optBoolean("redirect", false) && !region.isEmpty()) {
            baseUrl = hostFor(region.toUpperCase(Locale.ROOT));
        } else {
            String userCountry = "";
            JSONObject user = data.optJSONObject("user");
            if (user != null) {
                userCountry = user.optString("country", "");
            }
            baseUrl = "DE".equalsIgnoreCase(userCountry)
                    ? "https://api-de.libreview.io"
                    : "https://api-eu.libreview.io";
        }
    }

    private Reading fetchDirectMeasurements() throws Exception {
        String url = baseUrl
                + "/lsl/api/measurements/GetPatientGlucoseMeasurements?country="
                + URLEncoder.encode(country, "UTF-8")
                + "&patientId="
                + URLEncoder.encode(accountId, "UTF-8");

        JSONObject response = legacyRequest("GET", url, null, true);

        if (response.optInt("status", -1) != 0) {
            throw new Exception("Direkter LibreView-Abruf fehlgeschlagen (Status "
                    + response.optInt("status", -1) + ").");
        }

        JSONArray measurements = response.optJSONArray("result");
        if (measurements == null || measurements.length() == 0) {
            throw new Exception("LibreView liefert für dein eigenes Konto aktuell keine Live-Messwerte.");
        }

        for (int i = 0; i < measurements.length(); i++) {
            JSONObject m = measurements.optJSONObject(i);
            if (m == null || !m.has("Value")) continue;

            double value = m.optDouble("Value", Double.NaN);
            if (Double.isNaN(value)) continue;

            int units = m.optInt("GlucoseUnits", 1);
            String unit = units == 0 ? "mmol/L" : "mg/dL";
            int trend = m.optInt("TrendArrow", 0);
            String timestamp = m.optString("Timestamp", "");

            return new Reading(value, unit, trend, timestamp);
        }

        throw new Exception(
                "LibreView enthält Daten, aber keinen aktuellen Glukosewert. "
                        + "Falls das dauerhaft so bleibt, stellt Abbott diesen direkten Live-Endpunkt "
                        + "für dein Konto nicht zur Verfügung."
        );
    }

    private JSONObject legacyRequest(String method, String url, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Domain", "Libreview");
        c.setRequestProperty("GatewayType", "LinkUp.Android");
        c.setRequestProperty("User-Agent", "LibreMirror/0.3 Android");

        if (auth) {
            c.setRequestProperty("UserToken", userToken);
        }

        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        c.disconnect();

        if (code == 401 || code == 403) {
            throw new AuthException("LibreView-Session abgelaufen.");
        }
        if (code < 200 || code >= 300) {
            throw new Exception("LibreView HTTP " + code + (text.isEmpty() ? "" : ": " + compact(text)));
        }

        return new JSONObject(text);
    }

    private JSONObject modernRequest(String method, String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("product", "llu.android");
        c.setRequestProperty("version", VERSION);
        c.setRequestProperty("User-Agent", "LibreMirror/0.3 Android");

        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("Regionserkennung HTTP " + code);
        }

        return new JSONObject(text);
    }

    private static String hostFor(String region) {
        String r = region == null ? "AUTO" : region.trim().toUpperCase(Locale.ROOT);
        if ("AUTO".equals(r)) return "https://api.libreview.io";
        return "https://api-" + r.toLowerCase(Locale.ROOT) + ".libreview.io";
    }

    private static String compact(String value) {
        String text = value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 180 ? text.substring(0, 180) + "…" : text;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    public static String arrow(int trend) {
        switch (trend) {
            case 1: return "↓";
            case 2: return "↘";
            case 3: return "→";
            case 4: return "↗";
            case 5: return "↑";
            default: return "•";
        }
    }

    public static final class Reading {
        public final double value;
        public final String unit;
        public final int trend;
        public final String timestamp;

        Reading(double value, String unit, int trend, String timestamp) {
            this.value = value;
            this.unit = unit;
            this.trend = trend;
            this.timestamp = timestamp;
        }

        public String displayValue() {
            return "mmol/L".equals(unit)
                    ? String.format(Locale.getDefault(), "%.1f", value)
                    : String.format(Locale.getDefault(), "%.0f", value);
        }
    }

    private static final class AuthException extends Exception {
        AuthException(String message) {
            super(message);
        }
    }
}
