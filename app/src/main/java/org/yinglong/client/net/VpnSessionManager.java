package org.yinglong.client.net;

import android.content.Context;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.diag.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * User-scoped VPN session: select -> connect -> post-connect maintenance -> automatic failover.
 * No permanent polling is performed while the user has not pressed START.
 */
public final class VpnSessionManager {
    public enum State { IDLE, SEARCHING, CONNECTING, CONNECTED, STOPPING, ERROR }

    public interface Listener {
        void onState(State state, String detail);
    }

    private static volatile VpnSessionManager instance;
    private final Context context;
    private final OpenVpnTunnel tunnel;
    private final ExecutorService sessionWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService maintenanceWorker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);

    private volatile State state = State.IDLE;
    private volatile String detail = "";

    private static final int MAX_RANK_INPUT = 80;
    private static final int MAX_CONNECT_ATTEMPTS_PER_ROUND = 18;
    private static final long CONNECT_TIMEOUT_MS = 18_000L;

    public static VpnSessionManager get(Context context) {
        VpnSessionManager local = instance;
        if (local == null) {
            synchronized (VpnSessionManager.class) {
                local = instance;
                if (local == null) instance = local = new VpnSessionManager(context.getApplicationContext());
            }
        }
        return local;
    }

    private VpnSessionManager(Context context) {
        this.context = context;
        this.tunnel = OpenVpnTunnel.get(context);
    }

    public State state() { return state; }
    public String detail() { return detail; }
    public boolean isRunning() { return running.get(); }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onState(state, detail);
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        long token = generation.incrementAndGet();
        setState(State.SEARCHING, "starting");
        AppLog.i("session", "user session START token=" + token);
        sessionWorker.execute(() -> runSession(token));
    }

    public void stop() {
        if (!running.getAndSet(false) && state == State.IDLE) return;
        generation.incrementAndGet();
        setState(State.STOPPING, "user stop");
        AppLog.i("session", "user session STOP");
        tunnel.disconnect();
        setState(State.IDLE, "");
    }

    private void runSession(long token) {
        boolean maintenanceStarted = false;
        try {
            while (active(token)) {
                setState(State.SEARCHING, "ranking relays");
                List<Relay> relays = new RelayStore(context).read();
                AppLog.i("session", "relay pool size=" + relays.size());

                List<RelayProbe.Result> ranked = RelayProbe.rank(relays, MAX_RANK_INPUT, 10, 1800);
                if (ranked.isEmpty()) throw new IllegalStateException("нет доступных relay");
                AppLog.i("session", "ranked candidates=" + ranked.size());

                List<Relay> candidates = new ArrayList<>();
                for (RelayProbe.Result result : ranked) {
                    if (result == null || result.relay == null) continue;
                    candidates.add(result.relay);
                    if (candidates.size() >= MAX_CONNECT_ATTEMPTS_PER_ROUND) break;
                }

                boolean connectedThisRound = false;
                int attemptNumber = 0;
                for (Relay relay : candidates) {
                    if (!active(token)) return;
                    attemptNumber++;
                    setState(State.CONNECTING, relay.countryShort + " " + relay.ip);
                    AppLog.i("session", "attempt " + attemptNumber + "/" + candidates.size()
                            + " relay=" + relay.ip + " country=" + relay.countryShort);

                    boolean ok;
                    try {
                        ok = tunnel.connectBlocking(relay, CONNECT_TIMEOUT_MS);
                    } catch (Exception e) {
                        AppLog.e("session", "attempt exception relay=" + relay.ip, e);
                        ok = false;
                    }

                    if (!active(token)) {
                        tunnel.disconnect();
                        return;
                    }
                    if (!ok) continue;

                    connectedThisRound = true;
                    setState(State.CONNECTED, relay.countryShort + " " + relay.ip);
                    AppLog.i("session", "session CONNECTED relay=" + relay.ip);

                    if (!maintenanceStarted) {
                        maintenanceStarted = true;
                        maintenanceWorker.execute(() -> {
                            if (!active(token)) return;
                            AppLog.i("maintenance", "post-connect relay maintenance started");
                            try {
                                new PostConnectMaintenance(context).runOnce();
                                int size = new RelayStore(context).read().size();
                                AppLog.i("maintenance", "post-connect relay maintenance finished; pool=" + size);
                            } catch (Exception e) {
                                AppLog.e("maintenance", "post-connect relay maintenance failed", e);
                            }
                        });
                    }

                    try { tunnel.awaitConnectionLoss(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

                    if (!active(token)) return;
                    AppLog.w("session", "tunnel lost; automatic failover begins");
                    setState(State.SEARCHING, "failover");
                    break; // re-read pool: maintenance may have added fresher relays
                }

                if (!connectedThisRound) {
                    throw new IllegalStateException("не удалось подключиться к " + candidates.size() + " relay подряд");
                }
            }
        } catch (Exception e) {
            AppLog.e("session", "session failed", e);
            if (active(token)) {
                running.set(false);
                setState(State.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                tunnel.disconnect();
            }
        } finally {
            if (generation.get() == token && !running.get() && state != State.ERROR) setState(State.IDLE, "");
        }
    }

    private boolean active(long token) {
        return running.get() && generation.get() == token;
    }

    private void setState(State next, String message) {
        state = next;
        detail = message == null ? "" : message;
        AppLog.i("state", next + (detail.isEmpty() ? "" : " " + detail));
        for (Listener listener : listeners) {
            try { listener.onState(next, detail); }
            catch (Exception e) { AppLog.e("state", "listener failed", e); }
        }
    }
}
