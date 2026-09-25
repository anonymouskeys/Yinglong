package org.yinglong.client;

import android.app.Application;
import android.os.Build;

import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.diag.AppLog;
import org.yinglong.client.net.VpnSessionManager;

public final class YinglongApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.installCrashHandler(this);
        AppLog.i("app", "Yinglong process started v0.3.9 sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL);
        try {
            RelayStore store = new RelayStore(this);
            int count = store.mergeBundledSeed();
            AppLog.i("catalog", "bundled seed merged; active pool=" + count);
        } catch (Throwable e) {
            AppLog.e("catalog", "failed to seed/merge relay catalogue", e);
        }
        try {
            VpnSessionManager.get(this);
            AppLog.i("app", "VPN manager initialized");
        } catch (Throwable t) {
            AppLog.e("app", "VPN manager initialization FAILED", t);
        }
    }
}
