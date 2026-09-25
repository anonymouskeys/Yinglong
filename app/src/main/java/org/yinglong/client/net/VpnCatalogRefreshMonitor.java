package org.yinglong.client.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.yinglong.client.catalog.RelayUpdater;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes the relay catalogue after Android reports an active VPN transport. */
public final class VpnCatalogRefreshMonitor {
    private static final long COOLDOWN_MS = 10L * 60L * 1000L;

    private final Context context;
    private final ConnectivityManager cm;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile long lastAttemptMs = 0L;
    private boolean registered = false;

    public VpnCatalogRefreshMonitor(Context context) {
        this.context = context.getApplicationContext();
        this.cm = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) triggerRefresh();
        }
    };

    public synchronized void start() {
        if (registered || cm == null) return;
        cm.registerDefaultNetworkCallback(callback);
        registered = true;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) triggerRefresh();
    }

    private void triggerRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < COOLDOWN_MS) return;
        if (!refreshing.compareAndSet(false, true)) return;
        lastAttemptMs = now;
        io.execute(() -> {
            try {
                new RelayUpdater(context).refresh();
            } catch (Exception ignored) {
                // Keep last known-good snapshot. The next VPN event can retry.
            } finally {
                refreshing.set(false);
            }
        });
    }
}
