package org.yinglong.client.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot post-connect maintenance. It exists only for the user-started session.
 * No app-start polling, no periodic background loop.
 */
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
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) fireOnce();
        }
    };

    public synchronized void start() {
        if (registered || cm == null) return;
        cm.registerDefaultNetworkCallback(callback);
        registered = true;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) fireOnce();
    }

    private void fireOnce() {
        if (!fired.compareAndSet(false, true)) return;
        io.execute(() -> {
            try {
                new PostConnectMaintenance(context).runOnce();
            } catch (Exception ignored) {
                // Last known-good accumulated pool remains available.
            } finally {
                unregister();
            }
        });
    }

    public void stop() {
        unregister();
        io.shutdownNow();
    }

    private synchronized void unregister() {
        if (!registered || cm == null) return;
        try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {}
        registered = false;
    }
}
