package org.yinglong.client;

import android.app.Application;

import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.diag.AppLog;

public final class YinglongApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.i("app", "Yinglong process started");
        try {
            new RelayStore(this).ensureSeeded();
            AppLog.i("catalog", "relay seed ready");
        } catch (Exception e) {
            AppLog.e("catalog", "failed to seed relay catalogue", e);
        }
    }
}
