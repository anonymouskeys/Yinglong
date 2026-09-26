package org.yinglong.client.net;

import android.content.Context;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayHealthStore;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.catalog.RelayUpdater;
import org.yinglong.client.diag.AppLog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs once after a real VPN session comes up, never as a permanent poller. */
public final class PostConnectMaintenance {
    private static final long STALE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_HEALTH_PROBES = 48;
    private static final int FAILURES_BEFORE_PRUNE = 3;

    private final Context context;
    public PostConnectMaintenance(Context context) { this.context = context.getApplicationContext(); }

    public void runOnce() {
        AppLog.i("maintenance", "post-connect maintenance started");
        RelayUpdater updater = new RelayUpdater(context);
        try {
            int pool = updater.refreshMerged(6);
            AppLog.i("maintenance", "CSV merge finished pool=" + pool);
        } catch (Exception e) { AppLog.e("maintenance", "CSV refresh failed", e); }
        try {
            int pool = updater.harvestOfficialHtml(24);
            AppLog.i("maintenance", "HTML harvest finished pool=" + pool);
        } catch (Exception e) { AppLog.e("maintenance", "HTML harvest failed", e); }
        try { pruneConfirmedDead(); } catch (Exception e) { AppLog.e("maintenance", "prune failed", e); }
        AppLog.i("maintenance", "post-connect maintenance finished");
    }

    private void pruneConfirmedDead() throws Exception {
        RelayStore store = new RelayStore(context);
        RelayHealthStore health = new RelayHealthStore(context);
        List<Relay> pool = store.read();
        long now = System.currentTimeMillis();
        int attempted = 0;
        Set<String> remove = new HashSet<>();

        for (Relay r : pool) {
            if (attempted >= MAX_HEALTH_PROBES) break;
            long seen = health.lastSeen(r.ip);
            if (seen != 0L && now - seen < STALE_MS) continue;

            OpenVpnProfileUtil.Endpoint ep;
            try { ep = OpenVpnProfileUtil.endpoint(r); } catch (Exception e) { continue; }
            if (ep == null || !ep.tcp) continue;
            attempted++;

            RelayProbe.Result result = RelayProbe.probe(r, 2200);
            if (result != null && result.liveTcp) {
                health.markAlive(r.ip);
            } else {
                int failures = health.markFailure(r.ip);
                if (failures >= FAILURES_BEFORE_PRUNE && (seen == 0L || now - seen >= STALE_MS)) remove.add(r.ip);
            }
        }

        // Failed reachability from one network is not proof a volunteer relay
        // is dead. Keep accumulated profiles; health data can guide ranking.
        AppLog.i("maintenance", "health probes=" + attempted
                + " unreachable=" + remove.size() + " removed=0 (preserve catalogue)");
    }
}
