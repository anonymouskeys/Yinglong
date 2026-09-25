package org.yinglong.client;

import android.app.Application;

import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.net.VpnCatalogRefreshMonitor;

public final class YinglongApp extends Application {
    private VpnCatalogRefreshMonitor refreshMonitor;

    @Override public void onCreate() {
        super.onCreate();
        try {
            new RelayStore(this).ensureSeeded();
        } catch (Exception ignored) {
        }
        refreshMonitor = new VpnCatalogRefreshMonitor(this);
        refreshMonitor.start();
    }
}
