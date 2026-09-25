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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class LibreApiClient {
    private final String configuredRegion;
    private String baseUrl;
    private String authToken;
    private String userId;
    private long tokenExpiresAt;

    public LibreApiClient(String region) {
        configuredRegion = region == null ? "AUTO" : region.trim().toUpperCase(Locale.ROOT);
        baseUrl = hostFor(configuredRegion);
    }

    public Reading fetch(String email, String password) throws Exception {
        if (authToken == null || userId == null || System.currentTimeMillis() >= tokenExpiresAt) {
            login(email, password);
        }

        try {
            return fetchFromDailyLogReport();
        } catch (AuthException e) {
            authToken = null;
            userId = null;
            tokenExpiresAt = 0L;
            login(email, password);
            return fetchFromDailyLogReport();
        }
    }

    private void login(String email, String password) throws Exception {
        baseUrl = hostFor(configuredRegion);

        JSONObject payload = new JSONObject();
        payload.put("email", email);
        payload.put("password", password);

        JSONObject response = requestJson("POST", baseUrl + "/auth/login", payload, false);
        JSONObject data = response.optJSONObject("data");

        if (data != null && data.optBoolean("redirect", false)) {
            String region = data.optString("region", "");
            if (region.isEmpty()) {
                throw new Exception("LibreView verlangt eine Regionsumleitung, liefert aber keine Region.");
            }

            baseUrl = hostFor(region.toUpperCase(Locale.ROOT));
            response = requestJson("POST", baseUrl + "/auth/login", payload, false);
            data = response.optJSONObject("data");
        }

        if (data == null) {
            throw new Exception(apiMessage(response, "LibreView-Login fehlgeschlagen."));
        }

        applyAuthTicket(data.optJSONObject("authTicket"));

        JSONObject user = data.optJSONObject("user");
        if (user != null && !user.optString("id", "").isEmpty()) {
            userId = user.optString("id", "");
        }

        throwPendingStepIfNeeded(response, data);

        if (userId == null || userId.isEmpty()) {
            JSONObject userResponse = requestJson("GET", baseUrl + "/user", null, true);
            JSONObject userData = userResponse.optJSONObject("data");

            if (userData != null) {
                applyAuthTicket(userData.optJSONObject("authTicket"));
                JSONObject loadedUser = userData.optJSONObject("user");
                if (loadedUser != null) {
                    userId = loadedUser.optString("id", "");
                }
                throwPendingStepIfNeeded(userResponse, userData);
            }
        }

        if (userId == null || userId.isEmpty()) {
            throw new Exception("LibreView hat keine Benutzer-ID für dein persönliches Konto geliefert.");
        }
    }

    public Reading acceptTermsAndFetch(String email, String password) throws Exception {
        authToken = null;
        userId = null;
        tokenExpiresAt = 0L;

        try {
            login(email, password);
            return fetchFromDailyLogReport();
        } catch (TermsRequiredException expected) {
            if (authToken == null || authToken.isEmpty()) {
                throw new Exception("LibreView hat kein Token für die Bestätigung geliefert.");
            }

            JSONObject response = requestJson(
                    "POST",
                    baseUrl + "/auth/continue/" + expected.getStepType(),
                    new JSONObject(),
                    true
            );

            JSONObject data = response.optJSONObject("data");
            if (data == null) {
                throw new Exception(apiMessage(response, "LibreView konnte den Kontoschritt nicht bestätigen."));
            }

            applyAuthTicket(data.optJSONObject("authTicket"));

            JSONObject user = data.optJSONObject("user");
            if (user != null && !user.optString("id", "").isEmpty()) {
                userId = user.optString("id", "");
            }

            throwPendingStepIfNeeded(response, data);

            ensureUserId();
            return fetchFromDailyLogReport();
        }
    }

    public void sendTwoFactorCode() throws Exception {
        if (authToken == null || authToken.isEmpty()) {
            throw new Exception("LibreView-2FA kann ohne temporären Login-Token nicht gestartet werden.");
        }

        JSONObject payload = new JSONObject();
        payload.put("isPrimaryMethod", true);

        JSONObject response = requestJson(
                "POST",
                baseUrl + "/auth/continue/2fa/sendcode",
                payload,
                true
        );

        JSONObject ticket = response.optJSONObject("ticket");
        if (ticket == null) {
            JSONObject data = response.optJSONObject("data");
            if (data != null) ticket = data.optJSONObject("authTicket");
        }

        applyAuthTicket(ticket);

        if (authToken == null || authToken.isEmpty()) {
            throw new Exception("LibreView hat nach dem Versand des 2FA-Codes keinen temporären Token geliefert.");
        }
    }

    public Reading verifyTwoFactorAndFetch(String code) throws Exception {
        if (code == null || code.trim().isEmpty()) {
            throw new Exception("Bitte den LibreView-Bestätigungscode eingeben.");
        }
        if (authToken == null || authToken.isEmpty()) {
            throw new Exception("Die LibreView-2FA-Sitzung fehlt oder ist abgelaufen. Bitte Login neu starten.");
        }

        JSONObject payload = new JSONObject();
        payload.put("code", code.trim());
        payload.put("isPrimaryMethod", true);

        JSONObject response = requestJson(
                "POST",
                baseUrl + "/auth/continue/2fa/result",
                payload,
                true
        );

        JSONObject data = response.optJSONObject("data");
        if (data == null) {
            throw new Exception(apiMessage(response, "LibreView hat den Bestätigungscode nicht akzeptiert."));
        }

        applyAuthTicket(data.optJSONObject("authTicket"));

        JSONObject user = data.optJSONObject("user");
        if (user != null && !user.optString("id", "").isEmpty()) {
            userId = user.optString("id", "");
        }

        throwPendingStepIfNeeded(response, data);
        ensureUserId();

        return fetchFromDailyLogReport();
    }

    public void restoreTwoFactorSession(String token, String restoredBaseUrl) {
        if (token != null && !token.isEmpty()) {
            authToken = token;
            tokenExpiresAt = System.currentTimeMillis() + (15L * 60L * 1000L);
        }
        if (restoredBaseUrl != null && !restoredBaseUrl.isEmpty()) {
            baseUrl = restoredBaseUrl;
        }
    }

    public String getAuthToken() {
        return authToken == null ? "" : authToken;
    }

    public String getBaseUrl() {
        return baseUrl == null ? "" : baseUrl;
    }

    private void throwPendingStepIfNeeded(JSONObject response, JSONObject data) throws Exception {
        if (data == null) return;

        JSONObject step = data.optJSONObject("step");
        if (step == null || step == JSONObject.NULL) return;

        String stepType = step.optString("type", "").trim().toLowerCase(Locale.ROOT);
        if (stepType.isEmpty()) return;

        if ("tou".equals(stepType) || "pp".equals(stepType)) {
            throw new TermsRequiredException(
                    stepType,
                    "pp".equals(stepType)
                            ? "LibreView verlangt eine Datenschutzbestätigung."
                            : "LibreView-Nutzungsbedingungen müssen bestätigt werden."
            );
        }

        if ("2faverify".equals(stepType)) {
            throw new TwoFactorRequiredException(
                    "LibreView verlangt eine Zwei-Faktor-Bestätigung."
            );
        }

        throw new Exception(
                "LibreView verlangt einen unbekannten Kontoschritt: " + stepType
        );
    }

    private void ensureUserId() throws Exception {
        if (userId != null && !userId.isEmpty()) return;

        JSONObject userResponse = requestJson("GET", baseUrl + "/user", null, true);
        JSONObject userData = userResponse.optJSONObject("data");

        if (userData != null) {
            applyAuthTicket(userData.optJSONObject("authTicket"));
            JSONObject user = userData.optJSONObject("user");
            if (user != null) {
                userId = user.optString("id", "");
            }
            throwPendingStepIfNeeded(userResponse, userData);
        }

        if (userId == null || userId.isEmpty()) {
            throw new Exception("LibreView hat nach der Anmeldung keine Benutzer-ID geliefert.");
        }
    }

    private Reading fetchFromDailyLogReport() throws Exception {
        JSONObject settings = requestJson("GET", baseUrl + "/reportSettings", null, true);
        applyTopLevelTicket(settings);

        JSONObject data = settings.optJSONObject("data");
        JSONObject sources = data == null ? null : data.optJSONObject("dataSources");

        if (sources == null || sources.length() == 0) {
            throw new Exception(
                    "LibreView findet keine Datenquelle in deinem Konto. "
                            + "Prüfe, ob die offizielle Libre-App aktuelle Werte zu LibreView hochlädt."
            );
        }

        DeviceSelection selection = selectDevice(sources);
        if (selection.primaryId == null) {
            throw new Exception("LibreView konnte kein aktives Libre-Gerät für den Bericht bestimmen.");
        }

        long now = System.currentTimeMillis() / 1000L;
        long start = now - (12L * 60L * 60L);

        JSONObject body = new JSONObject();
        body.put("PrimaryDeviceId", selection.primaryId);
        body.put("PrimaryDeviceTypeId", selection.primaryType);
        body.put("SecondaryDeviceIds", new JSONArray(selection.secondaryIds));
        body.put("PrintReportsWithPatientInformation", false);
        body.put("ReportIds", new JSONArray().put(500000 + selection.primaryType));
        body.put("ClientReportIDs", new JSONArray().put(5));
        body.put("StartDates", new JSONArray().put(start));
        body.put("EndDate", now);
        body.put("PatientId", userId);
        body.put("CultureCode", "de-DE");
        body.put("CultureCodeCommunication", "de-DE");

        JSONObject reports = requestJson("POST", baseUrl + "/reports", body, true);
        applyTopLevelTicket(reports);

        JSONObject reportData = reports.optJSONObject("data");
        String channelUrl = reportData == null ? "" : reportData.optString("url", "");
        if (channelUrl.isEmpty()) {
            throw new Exception(apiMessage(reports, "LibreView konnte keinen Daily-Log-Bericht starten."));
        }

        JSONObject channels = requestJson("GET", channelUrl, null, true);
        JSONObject channelData = channels.optJSONObject("data");
        String pollUrl = channelData == null ? "" : channelData.optString("lp", "");

        if (pollUrl.isEmpty()) {
            throw new Exception("LibreView hat keinen Berichtskanal geliefert.");
        }

        String reportUrl = waitForReportUrl(pollUrl);
        String html = requestText(
                "GET",
                reportUrl + (reportUrl.contains("?") ? "&" : "?")
                        + "session=" + URLEncoder.encode(authToken, "UTF-8"),
                null,
                false
        );

        JSONObject report = extractWindowReport(html);
        return newestReading(report);
    }

    private DeviceSelection selectDevice(JSONObject sources) {
        DeviceSelection result = new DeviceSelection();
        JSONArray names = sources.names();
        if (names == null) return result;

        double bestScore = Double.MAX_VALUE;

        for (int i = 0; i < names.length(); i++) {
            String id = names.optString(i, "");
            JSONObject source = sources.optJSONObject(id);
            if (source == null) continue;

            int type = source.optInt("type", -1);
            if (type < 0) continue;

            double score = Double.MAX_VALUE;
            JSONArray daysData = source.optJSONArray("daysData");
            if (daysData != null && daysData.length() > 0) {
                for (int j = 0; j < daysData.length(); j++) {
                    score = Math.min(score, daysData.optDouble(j, Double.MAX_VALUE));
                }
            }

            if (result.primaryId == null || score < bestScore) {
                if (result.primaryId != null) result.secondaryIds.add(result.primaryId);
                result.primaryId = id;
                result.primaryType = type;
                bestScore = score;
            } else {
                result.secondaryIds.add(id);
            }
        }

        return result;
    }

    private String waitForReportUrl(String pollUrl) throws Exception {
        String lastOperation = "";

        for (int attempt = 0; attempt < 8; attempt++) {
            JSONObject response = requestJson("GET", pollUrl, null, true);
            lastOperation = response.optString("operation", "");

            if ("update".equalsIgnoreCase(lastOperation)) {
                JSONObject args = response.optJSONObject("args");
                JSONArray urls = args == null ? null : args.optJSONArray("urls");

                if (urls != null) {
                    if (urls.length() > 5 && !urls.optString(5, "").isEmpty()) {
                        return urls.optString(5, "");
                    }

                    for (int i = urls.length() - 1; i >= 0; i--) {
                        String candidate = urls.optString(i, "");
                        if (!candidate.isEmpty()) return candidate;
                    }
                }
            }

            if (!"started".equalsIgnoreCase(lastOperation) && !lastOperation.isEmpty()) {
                throw new Exception("LibreView-Bericht meldet Status: " + lastOperation);
            }

            Thread.sleep(1800L);
        }

        throw new Exception("LibreView-Bericht ist noch nicht fertig. Bitte in einigen Sekunden erneut aktualisieren.");
    }

    private Reading newestReading(JSONObject report) throws Exception {
        JSONObject data = report.optJSONObject("Data");
        JSONArray days = data == null ? null : data.optJSONArray("Days");

        if (days == null) {
            throw new Exception("LibreView-Bericht enthält keine Tagesdaten.");
        }

        List<ReportPoint> points = new ArrayList<>();

        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            if (day == null) continue;
            collectGlucose(day.opt("Glucose"), points);
        }

        if (points.isEmpty()) {
            throw new Exception("LibreView-Bericht enthält aktuell keine Glukosewerte.");
        }

        ReportPoint latest = null;
        ReportPoint previous = null;

        for (ReportPoint point : points) {
            if (latest == null || point.timestamp > latest.timestamp) {
                previous = latest;
                latest = point;
            } else if (previous == null || point.timestamp > previous.timestamp) {
                previous = point;
            }
        }

        if (latest == null) {
            throw new Exception("Kein verwertbarer Glukosewert im LibreView-Bericht.");
        }

        double mgdl = toMgDl(latest.value);
        int trend = inferTrend(previous, latest);

        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date(latest.timestamp * 1000L));

        return new Reading(mgdl, "mg/dL", trend, timestamp);
    }

    private void collectGlucose(Object node, List<ReportPoint> out) {
        if (node == null || node == JSONObject.NULL) return;

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectGlucose(array.opt(i), out);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;

        JSONObject object = (JSONObject) node;

        if (object.has("Value") && object.has("Timestamp")) {
            double value = object.optDouble("Value", Double.NaN);
            long timestamp = object.optLong("Timestamp", 0L);

            if (!Double.isNaN(value) && timestamp > 0L) {
                out.add(new ReportPoint(timestamp, value));
            }
        }
    }

    private static double toMgDl(double raw) {
        return raw <= 40.0 ? raw * 18.0182 : raw;
    }

    private static int inferTrend(ReportPoint previous, ReportPoint latest) {
        if (previous == null) return 0;

        double minutes = (latest.timestamp - previous.timestamp) / 60.0;
        if (minutes <= 0.0 || minutes > 30.0) return 0;

        double rate = (toMgDl(latest.value) - toMgDl(previous.value)) / minutes;

        if (rate <= -3.0) return 1;
        if (rate <= -1.5) return 2;
        if (rate < 1.5) return 3;
        if (rate < 3.0) return 4;
        return 5;
    }

    private JSONObject requestJson(String method, String url, JSONObject body, boolean authenticated) throws Exception {
        String text = requestText(method, url, body, authenticated);

        if (text.isEmpty()) return new JSONObject();

        try {
            return new JSONObject(text);
        } catch (Exception e) {
            String preview = compact(text);
            throw new Exception(
                    "LibreView-Antwort ist kein gültiges JSON"
                            + (preview.isEmpty() ? "." : ": " + preview),
                    e
            );
        }
    }

    private String requestText(String method, String url, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json,text/html,*/*");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("Connection", "keep-alive");

        if (authenticated) {
            connection.setRequestProperty("Authorization", "Bearer " + authToken);
        }

        if (body != null) {
            connection.setDoOutput(true);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = connection.getResponseCode();

        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        String contentEncoding = connection.getContentEncoding();

        if (stream != null && contentEncoding != null) {
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                stream = new GZIPInputStream(stream);
            } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
                stream = new InflaterInputStream(stream);
            }
        }

        String text = readAll(stream);
        connection.disconnect();

        if (code == 401 || code == 403) {
            throw new AuthException("LibreView hat die Sitzung abgelehnt (HTTP " + code + ").");
        }

        if (code < 200 || code >= 300) {
            throw new Exception(
                    "LibreView HTTP " + code
                            + (text.isEmpty() ? "" : ": " + compact(text))
            );
        }

        return text;
    }

    private void applyTopLevelTicket(JSONObject response) {
        if (response == null) return;
        applyAuthTicket(response.optJSONObject("ticket"));
    }

    private void applyAuthTicket(JSONObject ticket) {
        if (ticket == null) return;

        String token = ticket.optString("token", "");
        if (!token.isEmpty()) authToken = token;

        long duration = ticket.optLong("duration", 0L);

        if (duration > 0L) {
            tokenExpiresAt = System.currentTimeMillis() + duration;
        } else if (authToken != null) {
            tokenExpiresAt = System.currentTimeMillis() + (30L * 60L * 1000L);
        }
    }

    private static JSONObject extractWindowReport(String html) throws Exception {
        int marker = html.indexOf("window.report");

        if (marker < 0) {
            throw new Exception("LibreView-Bericht konnte nicht gelesen werden: window.report fehlt.");
        }

        int start = html.indexOf('{', marker);

        if (start < 0) {
            throw new Exception("LibreView-Bericht enthält kein JSON-Objekt.");
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;

        for (int i = start; i < html.length(); i++) {
            char ch = html.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }

            if (ch == '{') depth++;

            if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return new JSONObject(html.substring(start, i + 1));
                }
            }
        }

        throw new Exception("LibreView-Bericht ist unvollständig.");
    }

    private static String apiMessage(JSONObject json, String fallback) {
        if (json == null) return fallback;

        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "");
            if (!message.isEmpty()) return message;
        }

        String message = json.optString("message", "");
        return message.isEmpty() ? fallback : message;
    }

    private static String hostFor(String region) {
        String value = region == null ? "AUTO" : region.trim().toUpperCase(Locale.ROOT);

        if ("AUTO".equals(value)) {
            return "https://api.libreview.io";
        }

        return "https://api-" + value.toLowerCase(Locale.ROOT) + ".libreview.io";
    }

    private static String compact(String value) {
        String text = value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 220 ? text.substring(0, 220) + "…" : text;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";

        StringBuilder sb = new StringBuilder();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
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

    private static final class ReportPoint {
        final long timestamp;
        final double value;

        ReportPoint(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    private static final class DeviceSelection {
        String primaryId;
        int primaryType;
        final List<String> secondaryIds = new ArrayList<>();
    }

    public static final class TermsRequiredException extends Exception {
        private final String stepType;

        TermsRequiredException(String stepType, String message) {
            super(message);
            this.stepType = stepType;
        }

        public String getStepType() {
            return stepType;
        }
    }

    public static final class TwoFactorRequiredException extends Exception {
        TwoFactorRequiredException(String message) {
            super(message);
        }
    }

    private static final class AuthException extends Exception {
        AuthException(String message) {
            super(message);
        }
    }
}
