package de.assimoe.libremirror.core;

public enum SyncStatus {
    ONLINE_OK,
    NO_INTERNET,
    ABBOTT_UNREACHABLE,
    AUTH_EXPIRED,
    NO_CONNECTION,
    SENSOR_STALE,
    RATE_LIMIT,
    UNKNOWN_ERROR;

    public static SyncStatus fromName(String value) {
        if (value == null || value.isEmpty()) return UNKNOWN_ERROR;

        try {
            return SyncStatus.valueOf(value);
        } catch (Exception ignored) {
            return UNKNOWN_ERROR;
        }
    }

    public String userLabel() {
        switch (this) {
            case ONLINE_OK:
                return "Verbunden";
            case NO_INTERNET:
                return "Kein Internet";
            case ABBOTT_UNREACHABLE:
                return "Abbott Cloud nicht erreichbar";
            case AUTH_EXPIRED:
                return "Anmeldung erforderlich";
            case NO_CONNECTION:
                return "Keine Freigabe";
            case SENSOR_STALE:
                return "Sensorwert veraltet";
            case RATE_LIMIT:
                return "Rate-Limit";
            case UNKNOWN_ERROR:
            default:
                return "Verbindungsfehler";
        }
    }
}
