package org.yinglong.client.net;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.SystemClock;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import kittoku.osc.service.SstpVpnService;

/**
 * VPN Gate MS-SSTP transport.
 *
 * It connects to the relay IP directly (so poisoned DNS cannot redirect the
 * socket), while the patched SSTP module sends the relay's opengw.net name as
 * SNI and verifies that name against the TLS certificate.
 */
public final class SstpTunnel {
    public interface ProgressListener {
        void onStage(String stage, String message);
    }

    private static final String ACTION_CONNECT = "kittoku.osc.connect";
    private static final String ACTION_DISCONNECT = "kittoku.osc.disconnect";

    private static volatile SstpTunnel instance;

    private final Context context;
    private final ConnectivityManager connectivity;
    private final SharedPreferences prefs;
    private final boolean engineHealthy;

    private volatile boolean connected;
    private volatile Relay activeRelay;
    private volatile String lastFailure = "";

    public static SstpTunnel get(Context context) {
        SstpTunnel local = instance;
        if (local == null) {
            synchronized (SstpTunnel.class) {
                local = instance;
                if (local == null) {
                    instance = local = new SstpTunnel(context.getApplicationContext());
                }
            }
        }
        return local;
    }

    private SstpTunnel(Context context) {
        this.context = context;
        this.connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.prefs = context.getSharedPreferences(
                context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE
        );
        this.engineHealthy = validateEngineInstall();
        AppLog.i("sstp", "MS-SSTP engine initialized healthy=" + engineHealthy);
    }

    public boolean engineHealthy() {
        return engineHealthy;
    }

    public boolean isPermissionGranted() {
        return VpnService.prepare(context) == null;
    }

    public boolean isConnected() {
        return connected;
    }

    public Relay activeRelay() {
        return activeRelay;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public boolean connectBlocking(
            Relay relay,
            int port,
            long timeoutMs,
            ProgressListener progress
    ) throws Exception {
        if (relay == null) throw new IllegalArgumentException("relay == null");
        if (port <= 0 || port > 65535) throw new IllegalArgumentException("bad SSTP port");
        if (!engineHealthy) throw new IllegalStateException("SSTP engine self-check failed");
        if (!isPermissionGranted()) throw new IllegalStateException("Android VPN permission missing");

        disconnectBlocking(1200L);

        String tlsName = relayHost(relay);
        Set<String> auth = new HashSet<>();
        auth.add("MSCHAPv2");
        auth.add("PAP");

        prefs.edit()
                .putBoolean("ROOT_STATE", false)
                .putString("HOME_CONNECTED_IP", "")
                .putString("HOME_STATUS", "CONNECTING")
                .putString("HOME_HOSTNAME", safe(relay.ip))
                .putString("HOME_SERVER_NAME", tlsName)
                .putString("HOME_COUNTRY", safe(relay.countryShort).toUpperCase(Locale.US))
                .putString("HOME_USERNAME", "vpn")
                .putString("HOME_PASSWORD", "vpn")
                .putString("SSL_PORT", Integer.toString(port))
                .putString("SSL_VERSION", "DEFAULT")
                .putBoolean("SSL_DO_VERIFY", true)
                .putBoolean("SSL_DO_SPECIFY_CERT", false)
                .putBoolean("SSL_DO_SELECT_SUITES", false)
                .putBoolean("SSL_DO_USE_CUSTOM_SNI", true)
                .putString("SSL_CUSTOM_SNI", tlsName)
                .putBoolean("PROXY_DO_USE_PROXY", false)
                .putString("PPP_MRU", "1400")
                .putString("PPP_MTU", "1400")
                .putString("PPP_AUTH_TIMEOUT", "8")
                .putStringSet("PPP_AUTH_PROTOCOLS", auth)
                .putBoolean("PPP_IPv4_ENABLED", true)
                .putBoolean("PPP_IPv6_ENABLED", false)
                .putBoolean("DNS_DO_REQUEST_ADDRESS", true)
                .putBoolean("DNS_DO_USE_CUSTOM_SERVER", false)
                .putBoolean("ROUTE_DO_ADD_DEFAULT_ROUTE", true)
                .putBoolean("ROUTE_DO_ROUTE_PRIVATE_ADDRESSES", false)
                .putBoolean("ROUTE_DO_ADD_CUSTOM_ROUTES", false)
                .putBoolean("ROUTE_DO_ENABLE_APP_BASED_RULE", false)
                .putBoolean("RECONNECTION_ENABLED", false)
                .putBoolean("LOG_DO_SAVE_LOG", false)
                .apply();

        connected = false;
        activeRelay = null;
        lastFailure = "";

        stage(progress, "SSTP_START", safe(relay.ip) + ":" + port + " SNI=" + tlsName);
        AppLog.i("sstp", "START relay=" + relay.ip + " port=" + port + " sni=" + tlsName);

        Intent intent = new Intent(context, SstpVpnService.class).setAction(ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }

        long started = SystemClock.elapsedRealtime();
        long deadline = started + Math.max(12_000L, timeoutMs);
        boolean sawRoot = false;
        String lastStatus = "";

        while (SystemClock.elapsedRealtime() < deadline) {
            String ip = safe(prefs.getString("HOME_CONNECTED_IP", ""));
            boolean root = prefs.getBoolean("ROOT_STATE", false);
            String status = safe(prefs.getString("HOME_STATUS", ""));

            if (!status.equals(lastStatus) && !status.isEmpty()) {
                lastStatus = status;
                stage(progress, "SSTP_STATUS", status);
                AppLog.i("sstp", "status relay=" + relay.ip + " " + status);
            }

            if (root) sawRoot = true;

            if (!ip.isEmpty()) {
                if (waitForAndroidVpnTransport(3500L)) {
                    connected = true;
                    activeRelay = relay;
                    lastFailure = "";
                    stage(progress, "SSTP_CONNECTED", ip);
                    AppLog.i("sstp", "CONNECTED relay=" + relay.ip
                            + " port=" + port + " assigned=" + ip);
                    return true;
                }

                lastFailure = "SSTP assigned IP but Android TRANSPORT_VPN missing";
                break;
            }

            if (sawRoot && !root && SystemClock.elapsedRealtime() - started > 1000L) {
                lastFailure = status.isEmpty() ? "SSTP service stopped before connect" : status;
                break;
            }

            Thread.sleep(120L);
        }

        if (lastFailure.isEmpty()) {
            String status = safe(prefs.getString("HOME_STATUS", ""));
            lastFailure = status.isEmpty()
                    ? "SSTP timeout after " + Math.max(12_000L, timeoutMs) + " ms"
                    : status;
        }

        AppLog.w("sstp", "FAILED relay=" + relay.ip + " port=" + port
                + " reason=" + lastFailure);
        stage(progress, "SSTP_FAILED", lastFailure);
        disconnectBlocking(1200L);
        return false;
    }

    public void awaitConnectionLoss() throws InterruptedException {
        while (connected) {
            boolean root = prefs.getBoolean("ROOT_STATE", false);
            String ip = safe(prefs.getString("HOME_CONNECTED_IP", ""));
            if (!root || ip.isEmpty()) {
                connected = false;
                activeRelay = null;
                AppLog.w("sstp", "connected SSTP tunnel ended");
                return;
            }
            Thread.sleep(500L);
        }
    }

    public void disconnect() {
        disconnectBlocking(1200L);
    }

    private void disconnectBlocking(long waitMs) {
        boolean root = prefs.getBoolean("ROOT_STATE", false);
        String ip = safe(prefs.getString("HOME_CONNECTED_IP", ""));

        connected = false;
        activeRelay = null;

        if (!root && ip.isEmpty()) {
            return;
        }

        try {
            Intent intent = new Intent(context, SstpVpnService.class).setAction(ACTION_DISCONNECT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable e) {
            AppLog.e("sstp", "disconnect request failed", e);
            return;
        }

        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, waitMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!prefs.getBoolean("ROOT_STATE", false)
                    && safe(prefs.getString("HOME_CONNECTED_IP", "")).isEmpty()) {
                break;
            }
            try {
                Thread.sleep(80L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private boolean waitForAndroidVpnTransport(long timeoutMs) {
        if (connectivity == null) return true;

        long deadline = SystemClock.elapsedRealtime() + Math.max(500L, timeoutMs);
        do {
            try {
                Network active = connectivity.getActiveNetwork();
                NetworkCapabilities caps = active == null
                        ? null : connectivity.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            } catch (Throwable e) {
                AppLog.e("sstp", "TRANSPORT_VPN verification failed", e);
                return true;
            }

            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (SystemClock.elapsedRealtime() < deadline);

        return false;
    }

    private boolean validateEngineInstall() {
        try {
            context.getPackageManager().getServiceInfo(
                    new ComponentName(context, SstpVpnService.class),
                    PackageManager.GET_META_DATA
            );
            return true;
        } catch (Throwable e) {
            AppLog.e("sstp", "SstpVpnService missing", e);
            return false;
        }
    }

    private static String relayHost(Relay relay) {
        String h = safe(relay.hostName).trim();
        if (h.isEmpty()) return "opengw.net";
        String low = h.toLowerCase(Locale.US);
        if (low.endsWith(".opengw.net")) return h;
        return h + ".opengw.net";
    }

    private static void stage(ProgressListener listener, String stage, String message) {
        if (listener == null) return;
        try {
            listener.onStage(stage, message == null ? "" : message);
        } catch (Throwable ignored) {
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
