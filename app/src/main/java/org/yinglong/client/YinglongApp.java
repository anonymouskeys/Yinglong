package org.yinglong.client;

import android.app.Application;
import org.yinglong.client.catalog.RelayStore;

/** App bootstrap only. Network work starts only after the user presses START. */
public final class YinglongApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try {
            new RelayStore(this).ensureSeeded();
        } catch (Exception ignored) {
            // MainActivity will surface a failure when START is pressed.
        }
    }
}
