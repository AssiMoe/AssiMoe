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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class LibreApiClient {
    public static final String PRODUCT = "llu.android";
    public static final String VERSION = "4.16.0";

    private final String configuredRegion;
    private String baseUrl;
    private String authToken;
    private long authExpiresMs;
    private String accountHash;
    private String patientId;

    public LibreApiClient(
            String region,
            String cachedBaseUrl,
            String cachedToken,
            long cachedExpiresMs,
            String cachedAccountHash,
            String cachedPatientId
    ) {
        configuredRegion = normalizeRegion(region);
        baseUrl = cachedBaseUrl == null || cachedBaseUrl.isEmpty()
                ? hostFor(configuredRegion)
                : cachedBaseUrl;
        authToken = emptyToNull(cachedToken);
        authExpiresMs = cachedExpiresMs;
        accountHash = emptyToNull(cachedAccountHash);
        patientId = emptyToNull(cachedPatientId);
    }

    public FetchResult fetch(String email, String password) throws Exception {
        ensureAuthenticated(email, password);

        try {
            return fetchAuthenticated();
        } catch (AuthorizationException e) {
            clearSession();
            login(email, password);
            return fetchAuthenticated();
        }
    }

    public SessionState sessionState() {
        return new SessionState(
                baseUrl,
                nullToEmpty(authToken),
                authExpiresMs,
                nullToEmpty(accountHash),
                nullToEmpty(patientId)
        );
    }

    public void clearSession() {
        authToken = null;
        authExpiresMs = 0L;
        accountHash = null;
        patientId = null;
        baseUrl = hostFor(configuredRegion);
    }

    private void ensureAuthenticated(String email, String password) throws Exception {
        long oneHour = 60L * 60L * 1000L;
        boolean usable = authToken != null
                && accountHash != null
                && authExpiresMs > System.currentTimeMillis() + oneHour;

        if (!usable) {
            login(email, password);
        }
    }

    private void login(String email, String password) throws Exception {
        if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new UserVisibleException("LibreLinkUp-E-Mail oder Passwort fehlt.");
        }

        String firstHost = hostFor(configuredRegion);
        LoginResult login = loginAt(firstHost, email.trim(), password);

        if (login.redirectRegion != null && !login.redirectRegion.isEmpty()) {
            String redirectedHost = hostFor(login.redirectRegion);
            login = loginAt(redirectedHost, email.trim(), password);
            baseUrl = redirectedHost;
        } else {
            baseUrl = firstHost;
        }

        if (login.status == 2) {
            throw new AuthenticationException(
                    "LibreLinkUp hat E-Mail oder Passwort abgelehnt."
            );
        }

        if (login.status == 4) {
            throw new AuthenticationException(
                    "LibreLinkUp verlangt eine Kontobestätigung. Öffne LibreLinkUp einmal, "
                            + "akzeptiere offene Bedingungen bzw. die Einladung und starte LibreMirror danach erneut."
            );
        }

        if (login.status != 0) {
            String suffix = login.message.isEmpty() ? "" : " (" + login.message + ")";
            throw new UserVisibleException("LibreLinkUp-Login fehlgeschlagen: Status " + login.status + suffix);
        }

        if (login.token.isEmpty() || login.userId.isEmpty()) {
            throw new ApiException("LibreLinkUp-Login lieferte keine vollständige Sitzung.");
        }

        authToken = login.token;
        authExpiresMs = login.expiresMs > 0L
                ? login.expiresMs
                : System.currentTimeMillis() + 6L * 60L * 60L * 1000L;
        accountHash = sha256(login.userId);
        patientId = null;
    }

    private LoginResult loginAt(String host, String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email);
        body.put("password", password);

        Response response = request("POST", host + "/llu/auth/login", body, false);

        if (response.httpCode == 429 || response.httpCode == 476) {
            throw new RateLimitException(
                    "LibreLinkUp begrenzt derzeit Anmeldungen. Bitte später erneut versuchen.",
                    15L * 60L * 1000L
            );
        }

        if (response.httpCode == 401) {
            throw new AuthenticationException(
                    "LibreLinkUp hat die Zugangsdaten abgelehnt."
            );
        }

        if (response.httpCode >= 500) {
            throw new CloudUnavailableException(
                    "Abbott Cloud ist vorübergehend nicht erreichbar."
            );
        }

        if (response.httpCode < 200 || response.httpCode >= 300) {
            throw new ApiException("LibreLinkUp-Login HTTP " + response.httpCode + ".");
        }

        JSONObject root = parseObject(response.body);
        int status = root.optInt("status", 0);
        JSONObject data = root.optJSONObject("data");

        if (status == 920 && data != null) {
            String minimum = data.optString("minimumVersion", "");
            throw new UserVisibleException(
                    "LibreLinkUp verlangt eine neuere App-Version"
                            + (minimum.isEmpty() ? "." : " (mindestens " + minimum + ").")
            );
        }

        String redirect = "";
        String token = "";
        long expiresMs = 0L;
        String userId = "";

        if (data != null) {
            if (data.optBoolean("redirect", false)) {
                redirect = data.optString("region", "");
            }

            JSONObject ticket = data.optJSONObject("authTicket");
            if (ticket != null) {
                token = ticket.optString("token", "");
                long expires = ticket.optLong("expires", 0L);
                if (expires > 0L) expiresMs = expires * 1000L;
            }

            JSONObject user = data.optJSONObject("user");
            if (user != null) {
                userId = user.optString("id", "");
            }
        }

        String message = "";
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            message = error.optString("message", "");
        }

        return new LoginResult(status, redirect, token, expiresMs, userId, message);
    }

    private FetchResult fetchAuthenticated() throws Exception {
        if (patientId != null && !patientId.isEmpty()) {
            try {
                return fetchGraph(patientId, "");
            } catch (MissingConnectionException ignored) {
                patientId = null;
            }
        }

        ConnectionChoice choice = fetchConnections();

        if (choice.patientId == null || choice.patientId.isEmpty()) {
            throw new NoConnectionException(
                    "Keine aktive LibreLinkUp-Freigabe gefunden. "
                            + "Nimm die Einladung einmal in der LibreLinkUp-App an."
            );
        }

        patientId = choice.patientId;
        return fetchGraph(patientId, choice.patientName);
    }

    private FetchResult fetchGraph(String targetPatientId, String fallbackPatientName)
            throws Exception {
        Response response = request(
                "GET",
                baseUrl + "/llu/connections/" + targetPatientId + "/graph",
                null,
                true
        );

        if (response.httpCode == 401 || response.httpCode == 403) {
            throw new AuthorizationException();
        }

        if (response.httpCode == 400 || response.httpCode == 404) {
            throw new MissingConnectionException();
        }

        if (response.httpCode == 429) {
            throw new RateLimitException(
                    "LibreLinkUp begrenzt die Abfragen vorübergehend.",
                    5L * 60L * 1000L
            );
        }

        if (response.httpCode >= 500) {
            throw new CloudUnavailableException(
                    "Abbott Cloud ist vorübergehend nicht erreichbar."
            );
        }

        if (response.httpCode < 200 || response.httpCode >= 300) {
            throw new ApiException("LibreLinkUp-Graph HTTP " + response.httpCode + ".");
        }

        JSONObject root = validateEnvelope(parseObject(response.body));
        JSONObject data = root.optJSONObject("data");

        if (data == null) {
            throw new ApiException("LibreLinkUp lieferte keine Graph-Daten.");
        }

        List<Reading> readings = new ArrayList<>();
        JSONObject connection = data.optJSONObject("connection");
        Reading current = null;

        String patientName = fallbackPatientName == null ? "" : fallbackPatientName;

        if (connection != null) {
            if (patientName.isEmpty()) {
                patientName = connectionName(connection);
            }

            current = parseReading(connection.optJSONObject("glucoseMeasurement"));
            if (current != null) readings.add(current);
        }

        JSONArray graph = data.optJSONArray("graphData");
        if (graph != null) {
            for (int i = 0; i < graph.length(); i++) {
                Reading item = parseReading(graph.optJSONObject(i));
                if (item != null) readings.add(item);
            }
        }

        if (current == null && !readings.isEmpty()) {
            current = Collections.max(
                    readings,
                    Comparator.comparingLong(r -> r.timestampMs)
            );
        }

        if (current == null) {
            throw new UserVisibleException(
                    "LibreLinkUp liefert aktuell keinen Glukosewert."
            );
        }

        readings = deduplicateAndSort(readings);

        return new FetchResult(
                current,
                readings,
                patientName.isEmpty() ? "LibreLinkUp-Freigabe" : patientName
        );
    }

    private ConnectionChoice fetchConnections() throws Exception {
        Response response = request("GET", baseUrl + "/llu/connections", null, true);

        if (response.httpCode == 401 || response.httpCode == 403) {
            throw new AuthorizationException();
        }

        if (response.httpCode == 429) {
            throw new RateLimitException(
                    "LibreLinkUp begrenzt die Abfragen vorübergehend.",
                    5L * 60L * 1000L
            );
        }

        if (response.httpCode >= 500) {
            throw new CloudUnavailableException(
                    "Abbott Cloud ist vorübergehend nicht erreichbar."
            );
        }

        if (response.httpCode < 200 || response.httpCode >= 300) {
            throw new ApiException("LibreLinkUp-Verbindungen HTTP " + response.httpCode + ".");
        }

        JSONObject root = validateEnvelope(parseObject(response.body));
        JSONArray data = root.optJSONArray("data");

        if (data == null || data.length() == 0) {
            return new ConnectionChoice(null, "");
        }

        JSONObject fallback = null;

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;

            String id = item.optString("patientId", "").trim();
            if (id.isEmpty()) continue;

            if (fallback == null) fallback = item;

            if (patientId != null && patientId.equals(id)) {
                return new ConnectionChoice(id, connectionName(item));
            }

            Reading current = parseReading(item.optJSONObject("glucoseMeasurement"));
            if (current != null) {
                return new ConnectionChoice(id, connectionName(item));
            }
        }

        if (fallback != null) {
            return new ConnectionChoice(
                    fallback.optString("patientId", ""),
                    connectionName(fallback)
            );
        }

        return new ConnectionChoice(null, "");
    }

    private Response request(String method, String url, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);

        connection.setRequestProperty("product", PRODUCT);
        connection.setRequestProperty("version", VERSION);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Connection", "Keep-Alive");

        if (authenticated) {
            if (authToken == null || accountHash == null) {
                throw new AuthorizationException();
            }
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
            connection.setRequestProperty("Account-Id", accountHash);
        }

        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = connection.getResponseCode();
        InputStream input = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        String encoding = connection.getContentEncoding();
        if (input != null && encoding != null) {
            if ("gzip".equalsIgnoreCase(encoding)) {
                input = new GZIPInputStream(input);
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                input = new InflaterInputStream(input);
            }
        }

        String text = readAll(input);
        connection.disconnect();

        return new Response(code, text);
    }

    private static JSONObject validateEnvelope(JSONObject root) throws Exception {
        int status = root.optInt("status", 0);
        if (status != 0) {
            if (status == 920) {
                JSONObject data = root.optJSONObject("data");
                String minimum = data == null ? "" : data.optString("minimumVersion", "");
                throw new UserVisibleException(
                        "LibreLinkUp verlangt eine neuere Client-Version"
                                + (minimum.isEmpty() ? "." : " (mindestens " + minimum + ").")
                );
            }
            throw new ApiException("LibreLinkUp meldet Status " + status + ".");
        }
        return root;
    }

    private static JSONObject parseObject(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) {
            throw new ApiException("LibreLinkUp lieferte eine leere Antwort.");
        }

        try {
            return new JSONObject(body);
        } catch (Exception e) {
            throw new ApiException("LibreLinkUp lieferte ungültige Daten.");
        }
    }

    private static Reading parseReading(JSONObject object) {
        if (object == null) return null;

        double mgdl = object.optDouble("ValueInMgPerDl", Double.NaN);

        if (Double.isNaN(mgdl)) {
            double value = object.optDouble("Value", Double.NaN);
            int units = object.optInt("GlucoseUnits", 1);

            if (Double.isNaN(value)) return null;
            mgdl = units == 0 ? value * 18.0182 : value;
        }

        String timestamp = object.optString("Timestamp", "");
        if (timestamp.isEmpty()) {
            timestamp = object.optString("FactoryTimestamp", "");
        }

        long timestampMs = parseTimestamp(timestamp);
        if (timestampMs <= 0L) return null;

        return new Reading(
                mgdl,
                object.optInt("TrendArrow", 0),
                timestamp,
                timestampMs
        );
    }

    private static List<Reading> deduplicateAndSort(List<Reading> source) {
        List<Reading> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        source.sort(Comparator.comparingLong(r -> r.timestampMs));

        for (Reading reading : source) {
            if (seen.add(reading.timestampMs)) {
                result.add(reading);
            }
        }

        if (result.size() > 48) {
            return new ArrayList<>(result.subList(result.size() - 48, result.size()));
        }

        return result;
    }

    private static long parseTimestamp(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;

        try {
            return Instant.parse(raw).toEpochMilli();
        } catch (Exception ignored) {
        }

        String[] formats = new String[]{
                "M/d/yyyy h:mm:ss a",
                "M/d/yyyy h:mm a",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss"
        };

        for (String format : formats) {
            SimpleDateFormat parser = new SimpleDateFormat(format, Locale.US);
            parser.setLenient(true);
            parser.setTimeZone(TimeZone.getDefault());
            try {
                Date date = parser.parse(raw);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) {
            }
        }

        return 0L;
    }

    private static String connectionName(JSONObject item) {
        String first = item.optString("firstName", "").trim();
        String last = item.optString("lastName", "").trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "LibreLinkUp-Freigabe" : name;
    }

    private static String sha256(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(bytes.length * 2);

        for (byte value : bytes) {
            hex.append(String.format(Locale.US, "%02x", value));
        }

        return hex.toString();
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    private static String normalizeRegion(String region) {
        if (region == null || region.trim().isEmpty()) return "AUTO";
        return region.trim().toUpperCase(Locale.ROOT);
    }

    private static String hostFor(String region) {
        String value = normalizeRegion(region);

        switch (value) {
            case "AUTO":
            case "US":
                return "https://api.libreview.io";
            case "EU":
                return "https://api-eu.libreview.io";
            case "EU2":
                return "https://api-eu2.libreview.io";
            case "DE":
                return "https://api-de.libreview.io";
            case "FR":
                return "https://api-fr.libreview.io";
            case "JP":
                return "https://api-jp.libreview.io";
            case "AP":
                return "https://api-ap.libreview.io";
            case "AU":
                return "https://api-au.libreview.io";
            case "AE":
                return "https://api-ae.libreview.io";
            case "CA":
                return "https://api-ca.libreview.io";
            case "IN":
                return "https://api-in.libreview.io";
            case "LA":
                return "https://api-la.libreview.io";
            case "RU":
                return "https://api-ru.libreview.io";
            default:
                return "https://api.libreview.io";
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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

    public static String trendLabel(int trend) {
        switch (trend) {
            case 1: return "Stark fallend";
            case 2: return "Fallend";
            case 3: return "Stabil";
            case 4: return "Steigend";
            case 5: return "Stark steigend";
            default: return "Trend unbekannt";
        }
    }

    public static final class Reading {
        public final double mgdl;
        public final int trend;
        public final String rawTimestamp;
        public final long timestampMs;

        Reading(double mgdl, int trend, String rawTimestamp, long timestampMs) {
            this.mgdl = mgdl;
            this.trend = trend;
            this.rawTimestamp = rawTimestamp;
            this.timestampMs = timestampMs;
        }

        public String displayValue() {
            return String.format(Locale.getDefault(), "%.0f", mgdl);
        }
    }

    public static final class FetchResult {
        public final Reading current;
        public final List<Reading> history;
        public final String patientName;

        FetchResult(Reading current, List<Reading> history, String patientName) {
            this.current = current;
            this.history = history;
            this.patientName = patientName;
        }
    }

    public static final class SessionState {
        public final String baseUrl;
        public final String token;
        public final long expiresMs;
        public final String accountHash;
        public final String patientId;

        SessionState(
                String baseUrl,
                String token,
                long expiresMs,
                String accountHash,
                String patientId
        ) {
            this.baseUrl = baseUrl;
            this.token = token;
            this.expiresMs = expiresMs;
            this.accountHash = accountHash;
            this.patientId = patientId;
        }
    }

    private static final class LoginResult {
        final int status;
        final String redirectRegion;
        final String token;
        final long expiresMs;
        final String userId;
        final String message;

        LoginResult(
                int status,
                String redirectRegion,
                String token,
                long expiresMs,
                String userId,
                String message
        ) {
            this.status = status;
            this.redirectRegion = redirectRegion;
            this.token = token;
            this.expiresMs = expiresMs;
            this.userId = userId;
            this.message = message == null ? "" : message;
        }
    }

    private static final class ConnectionChoice {
        final String patientId;
        final String patientName;

        ConnectionChoice(String patientId, String patientName) {
            this.patientId = patientId;
            this.patientName = patientName;
        }
    }

    private static final class Response {
        final int httpCode;
        final String body;

        Response(int httpCode, String body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }

    public static class UserVisibleException extends Exception {
        public UserVisibleException(String message) {
            super(message);
        }
    }

    public static final class AuthenticationException extends UserVisibleException {
        public AuthenticationException(String message) {
            super(message);
        }
    }

    public static final class NoConnectionException extends UserVisibleException {
        public NoConnectionException(String message) {
            super(message);
        }
    }

    public static final class CloudUnavailableException extends UserVisibleException {
        public CloudUnavailableException(String message) {
            super(message);
        }
    }

    public static final class RateLimitException extends UserVisibleException {
        public final long retryAfterMs;

        public RateLimitException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static final class AuthorizationException extends Exception {
    }

    private static final class MissingConnectionException extends Exception {
    }

    private static final class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }
}
