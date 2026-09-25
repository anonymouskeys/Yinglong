package org.yinglong.client.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.yinglong.client.diag.AppLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot post-connect maintenance, only while a user-started session exists. */
public final class SessionRefreshMonitor {
    private final Context context;
    private final ConnectivityManager cm;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fired = new AtomicBoolean(false);
    private volatile boolean registered;

    public SessionRefreshMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.cm = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            boolean vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
            AppLog.i("network", "capabilities changed vpn=" + vpn);
            if (vpn) fireOnce();
        }
    };

    public synchronized void start() {
        if (registered || cm == null) return;
        cm.registerDefaultNetworkCallback(callback);
        registered = true;
        AppLog.i("network", "session VPN monitor registered");
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        boolean vpn = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        AppLog.i("network", "current default network vpn=" + vpn);
        if (vpn) fireOnce();
    }

    private void fireOnce() {
        if (!fired.compareAndSet(false, true)) return;
        AppLog.i("maintenance", "real VPN transport detected; starting one-shot maintenance");
        io.execute(() -> {
            try {
                new PostConnectMaintenance(context).runOnce();
            } catch (Exception e) {
                AppLog.e("maintenance", "post-connect maintenance failed", e);
            } finally {
                unregister();
            }
        });
    }

    public void stop() {
        AppLog.i("network", "session VPN monitor stop requested");
        unregister();
        io.shutdownNow();
    }

    private synchronized void unregister() {
        if (!registered || cm == null) return;
        try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        registered = false;
        AppLog.i("network", "session VPN monitor unregistered");
    }
}
