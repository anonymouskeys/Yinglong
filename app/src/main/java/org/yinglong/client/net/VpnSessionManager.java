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

/** User-scoped VPN session: rank -> connect -> maintain -> fail over. */
public final class VpnSessionManager {
    public enum State { IDLE, SEARCHING, CONNECTING, CONNECTED, STOPPING, ERROR }

    public interface Listener { void onState(State state, String detail); }

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

    private static final int MAX_RANK_INPUT = 180;
    private static final int MAX_CONNECT_ATTEMPTS_PER_ROUND = 28;
    private static final long CONNECT_TIMEOUT_MS = 11_000L;

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
        AppLog.i("session", "VpnSessionManager initialized engineHealthy=" + tunnel.engineHealthy());
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
        AppLog.i("session", "start() called running=" + running.get() + " permission=" + tunnel.isPermissionGranted());
        if (!tunnel.engineHealthy()) {
            setState(State.ERROR, "OpenVPN engine self-check failed");
            return;
        }
        if (!tunnel.isPermissionGranted()) {
            setState(State.ERROR, "Android VPN permission missing");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            AppLog.w("session", "start ignored: session already running");
            return;
        }
        long token = generation.incrementAndGet();
        setState(State.SEARCHING, "starting");
        AppLog.i("session", "user session START token=" + token);
        sessionWorker.execute(() -> runSession(token));
    }

    public void stop() {
        AppLog.i("session", "stop() called state=" + state + " running=" + running.get());
        if (!running.getAndSet(false) && state == State.IDLE) return;
        generation.incrementAndGet();
        setState(State.STOPPING, "user stop");
        tunnel.disconnect();
        setState(State.IDLE, "");
    }

    private void runSession(long token) {
        boolean maintenanceStarted = false;
        String lastFailure = "";
        try {
            while (active(token)) {
                setState(State.SEARCHING, "ranking relays");
                List<Relay> relays = new RelayStore(context).read();
                AppLog.i("session", "relay pool size=" + relays.size());

                List<RelayProbe.Result> ranked = RelayProbe.rank(relays, MAX_RANK_INPUT, 14, 1600);
                if (ranked.isEmpty()) throw new IllegalStateException("нет достижимых relay");
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
                    } catch (Throwable e) {
                        lastFailure = e.getClass().getSimpleName() + ": " + safe(e.getMessage());
                        AppLog.e("session", "attempt exception relay=" + relay.ip, e);
                        ok = false;
                    }

                    if (!ok) {
                        if (!tunnel.lastFailure().isEmpty()) lastFailure = tunnel.lastFailure();
                        AppLog.w("session", "attempt failed relay=" + relay.ip + " reason=" + lastFailure);
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
                            } catch (Throwable e) {
                                AppLog.e("maintenance", "post-connect relay maintenance failed", e);
                            }
                        });
                    }

                    try { tunnel.awaitConnectionLoss(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

                    if (!active(token)) return;
                    AppLog.w("session", "tunnel lost; automatic failover begins");
                    setState(State.SEARCHING, "failover");
                    break;
                }

                if (!connectedThisRound) {
                    throw new IllegalStateException("не удалось подключиться к " + candidates.size()
                            + " relay; последняя причина: " + (lastFailure.isEmpty() ? "unknown" : lastFailure));
                }
            }
        } catch (Throwable e) {
            AppLog.e("session", "session failed", e);
            if (active(token)) {
                running.set(false);
                String message = safe(e.getMessage());
                if (message.isEmpty()) message = e.getClass().getSimpleName();
                setState(State.ERROR, message);
                tunnel.disconnect();
            }
        } finally {
            if (generation.get() == token && !running.get() && state != State.ERROR) setState(State.IDLE, "");
            AppLog.i("session", "runSession exit token=" + token + " state=" + state + " running=" + running.get());
        }
    }

    private boolean active(long token) { return running.get() && generation.get() == token; }

    private void setState(State next, String message) {
        state = next;
        detail = message == null ? "" : message;
        AppLog.i("state", next + (detail.isEmpty() ? "" : " " + detail));
        for (Listener listener : listeners) {
            try { listener.onState(next, detail); }
            catch (Throwable e) { AppLog.e("state", "listener failed", e); }
        }
    }

    private static String safe(String v) { return v == null ? "" : v; }
}
