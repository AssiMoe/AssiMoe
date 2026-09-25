package de.assimoe.libremirror;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class LibreApiClient {
    private static final String VERSION = "4.17.0";
    private String baseUrl;
    private String token;
    private String accountIdHash;
    private String patientId;

    public LibreApiClient(String configuredRegion) {
        String region = configuredRegion == null ? "AUTO" : configuredRegion.trim().toUpperCase(Locale.ROOT);
        baseUrl = region.equals("AUTO")
                ? "https://api.libreview.io"
                : "https://api-" + region.toLowerCase(Locale.ROOT) + ".libreview.io";
    }

    public Reading fetch(String email, String password) throws Exception {
        if (token == null || patientId == null) login(email, password);
        try {
            return fetchGraph();
        } catch (AuthException ex) {
            token = null;
            patientId = null;
            login(email, password);
            return fetchGraph();
        }
    }

    private void login(String email, String password) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("email", email);
        payload.put("password", password);

        JSONObject response = request("POST", baseUrl + "/llu/auth/login", payload, false);
        JSONObject data = response.optJSONObject("data");
        if (response.optInt("status", -1) == 4)
            throw new Exception("Bitte zuerst die aktuellen LibreLinkUp-Bedingungen in der offiziellen App akzeptieren.");
        if (data == null) throw new Exception(apiError(response, "Login fehlgeschlagen"));

        if (data.optBoolean("redirect", false) && !data.optString("region", "").isEmpty()) {
            baseUrl = "https://api-" + data.getString("region").toLowerCase(Locale.ROOT) + ".libreview.io";
            response = request("POST", baseUrl + "/llu/auth/login", payload, false);
            data = response.optJSONObject("data");
            if (response.optInt("status", -1) == 4)
                throw new Exception("Bitte zuerst die aktuellen LibreLinkUp-Bedingungen in der offiziellen App akzeptieren.");
            if (data == null) throw new Exception(apiError(response, "Regionaler Login fehlgeschlagen"));
        }

        JSONObject ticket = data.optJSONObject("authTicket");
        JSONObject user = data.optJSONObject("user");
        if (ticket == null || user == null) throw new Exception("LibreLinkUp hat kein Auth-Ticket geliefert.");
        token = ticket.optString("token", "");
        String userId = user.optString("id", "");
        if (token.isEmpty() || userId.isEmpty()) throw new Exception("LibreLinkUp Login unvollständig.");
        accountIdHash = sha256(userId);

        JSONObject connections = request("GET", baseUrl + "/llu/connections", null, true);
        JSONArray arr = connections.optJSONArray("data");
        if (arr == null || arr.length() == 0)
            throw new Exception("Keine LibreLinkUp-Verbindung gefunden. Einladung in LibreLinkUp annehmen.");
        patientId = arr.getJSONObject(0).optString("patientId", "");
        if (patientId.isEmpty()) throw new Exception("Keine Patient-ID erhalten.");
    }

    private Reading fetchGraph() throws Exception {
        JSONObject graph = request("GET", baseUrl + "/llu/connections/" + patientId + "/graph", null, true);
        JSONObject data = graph.optJSONObject("data");
        JSONObject connection = data == null ? null : data.optJSONObject("connection");
        JSONObject measurement = connection == null ? null : connection.optJSONObject("glucoseMeasurement");
        if (measurement == null) throw new Exception("LibreLinkUp liefert aktuell keinen Glukosewert.");

        double value = measurement.optDouble("Value", Double.NaN);
        int units = measurement.optInt("GlucoseUnits", 1);
        String unit = units == 0 ? "mmol/L" : "mg/dL";
        int trend = measurement.optInt("TrendArrow", 0);
        String timestamp = measurement.optString("Timestamp", "");
        if (Double.isNaN(value)) throw new Exception("Ungültiger Glukosewert.");
        return new Reading(value, unit, trend, timestamp);
    }

    private JSONObject request(String method, String url, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("product", "llu.android");
        c.setRequestProperty("version", VERSION);
        c.setRequestProperty("User-Agent", "LibreMirror/0.1 Android");

        if (auth) {
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Account-Id", accountIdHash);
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

        if (code == 401 || code == 403) throw new AuthException("Session abgelaufen");
        if (code < 200 || code >= 300)
            throw new Exception("LibreLinkUp HTTP " + code + (text.isEmpty() ? "" : ": " + compact(text)));

        JSONObject json = new JSONObject(text);
        if (json.optInt("status", 0) != 0) {
            if (json.optInt("status", 0) == 2) throw new AuthException("Session abgelaufen");
            throw new Exception(apiError(json, "LibreLinkUp API-Fehler"));
        }
        return json;
    }

    private static String apiError(JSONObject json, String fallback) {
        JSONObject error = json.optJSONObject("error");
        if (error != null && !error.optString("message", "").isEmpty()) return error.optString("message");
        return fallback + " (Status " + json.optInt("status", -1) + ")";
    }

    private static String compact(String s) {
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
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

    private static String sha256(String value) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format(Locale.US, "%02x", b));
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
        AuthException(String message) { super(message); }
    }
}
