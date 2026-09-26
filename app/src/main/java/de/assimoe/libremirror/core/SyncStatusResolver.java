package de.assimoe.libremirror.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import de.assimoe.libremirror.LibreApiClient;

public final class SyncStatusResolver {
    private SyncStatusResolver() {}

    public static SyncStatus resolve(Context context, Exception error) {
        if (error instanceof NoInternetException
                || !hasValidatedInternet(context)) {
            return SyncStatus.NO_INTERNET;
        }

        if (error instanceof LibreApiClient.RateLimitException) {
            return SyncStatus.RATE_LIMIT;
        }

        if (error instanceof LibreApiClient.AuthenticationException) {
            return SyncStatus.AUTH_EXPIRED;
        }

        if (error instanceof LibreApiClient.NoConnectionException) {
            return SyncStatus.NO_CONNECTION;
        }

        if (error instanceof LibreApiClient.CloudUnavailableException) {
            return SyncStatus.ABBOTT_UNREACHABLE;
        }

        if (error instanceof java.io.IOException) {
            return SyncStatus.ABBOTT_UNREACHABLE;
        }

        return SyncStatus.UNKNOWN_ERROR;
    }

    public static boolean hasValidatedInternet(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);

            if (manager == null) return false;

            Network network = manager.getActiveNetwork();
            if (network == null) return false;

            NetworkCapabilities capabilities =
                    manager.getNetworkCapabilities(network);

            return capabilities != null
                    && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
            )
                    && capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
            );
        } catch (Exception ignored) {
            return true;
        }
    }

    public static final class NoInternetException extends Exception {
        public NoInternetException() {
            super("Keine Internetverbindung.");
        }
    }
}
