package org.yinglong.client;

import android.app.Application;

import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.diag.AppLog;
import org.yinglong.client.net.VpnSessionManager;

public final class YinglongApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.i("app", "Yinglong process started v0.3");
        try {
            new RelayStore(this).ensureSeeded();
            AppLog.i("catalog", "relay seed ready");
        } catch (Exception e) {
            AppLog.e("catalog", "failed to seed relay catalogue", e);
        }
        // Initializes the embedded OpenVPN engine/listeners, but does not connect or refresh anything.
        VpnSessionManager.get(this);
    }
}
