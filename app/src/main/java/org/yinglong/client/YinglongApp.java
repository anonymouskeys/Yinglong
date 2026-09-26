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
        AppLog.i("app", "Yinglong process started v" + BuildConfig.VERSION_NAME + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL);
        try {
            RelayStore store = new RelayStore(this);
            android.content.SharedPreferences seedPrefs =
                    getSharedPreferences("catalog_seed_version_v1", MODE_PRIVATE);
            int installedSeedVersion = seedPrefs.getInt("versionCode", -1);
            int currentVersion = BuildConfig.VERSION_CODE;

            int count;
            if (installedSeedVersion != currentVersion) {
                count = store.replaceWithBundledSeed();
                seedPrefs.edit().putInt("versionCode", currentVersion).apply();
                AppLog.i("catalog",
                        "new APK seed installed fresh-only versionCode="
                                + currentVersion + " active pool=" + count);
            } else {
                count = store.mergeBundledSeed();
                AppLog.i("catalog",
                        "bundled seed merged; active pool=" + count);
            }
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
