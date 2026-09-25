package org.yinglong.client.catalog;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collection;

/** Small health metadata store; relay profiles themselves stay in relays.csv. */
public final class RelayHealthStore {
    private static final String PREFS = "relay_health_v1";
    private final SharedPreferences p;

    public RelayHealthStore(Context context) {
        p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void markSeen(Collection<Relay> relays) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor e = p.edit();
        for (Relay r : relays) {
            if (r == null || r.ip == null || r.ip.isEmpty()) continue;
            e.putLong("seen:" + r.ip, now);
            e.putInt("fail:" + r.ip, 0);
        }
        e.apply();
    }

    public long lastSeen(String ip) { return p.getLong("seen:" + ip, 0L); }
    public int failures(String ip) { return p.getInt("fail:" + ip, 0); }

    public int markFailure(String ip) {
        int n = Math.min(20, failures(ip) + 1);
        p.edit().putInt("fail:" + ip, n).apply();
        return n;
    }

    public void markAlive(String ip) {
        p.edit().putLong("seen:" + ip, System.currentTimeMillis()).putInt("fail:" + ip, 0).apply();
    }

    public void forget(String ip) {
        p.edit().remove("seen:" + ip).remove("fail:" + ip).apply();
    }
}
