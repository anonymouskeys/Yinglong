package org.yinglong.client.net;

import android.content.Context;
import android.content.SharedPreferences;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.catalog.RelayUpdater;
import org.yinglong.client.diag.AppLog;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Yinglong v0.3.12: refresh/merge VPN Gate relays -> probe native/catalog SoftEther ports -> DHCP -> VpnService.
 */
public final class VpnSessionManager {
    public enum State { IDLE, SEARCHING, CONNECTING, CONNECTED, STOPPING, ERROR }

    public interface Listener { void onState(State state, String detail); }

    private static volatile VpnSessionManager instance;

    private final Context context;
    private final SoftEtherTunnel tunnel;
    private final SharedPreferences sessionDiag;
    private final ExecutorService sessionWorker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);

    private volatile State state = State.IDLE;
    private volatile String detail = "";

    private static final int FAST_SCAN_HOSTS = 120;
    private static final int FAST_ATTEMPTS = 10;
    private static final int BOOTSTRAP_ROUNDS = 6;
    private static final long SOFTETHER_ATTEMPT_TIMEOUT_MS = 18_000L;

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
        this.sessionDiag = context.getSharedPreferences("session_diag_v1", Context.MODE_PRIVATE);
        if (sessionDiag.getBoolean("active", false)) {
            AppLog.w("session", "previous process ended while VPN session was active; lastState="
                    + sessionDiag.getString("state", "?") + " detail="
                    + sessionDiag.getString("detail", ""));
        }
        sessionDiag.edit().putBoolean("active", false).apply();
        this.tunnel = SoftEtherTunnel.get(context);
        AppLog.i("session", "VpnSessionManager initialized SoftEther healthy=" + tunnel.engineHealthy());
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
        AppLog.i("session", "start() called running=" + running.get()
                + " permission=" + tunnel.isPermissionGranted());

        if (!tunnel.engineHealthy()) {
            setState(State.ERROR, "SoftEther engine self-check failed");
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
        sessionDiag.edit().putBoolean("active", true).apply();
        setState(State.SEARCHING, "SoftEther TLS: читаю локальную базу…");
        AppLog.i("session", "user session START token=" + token + " transport=SoftEther-TLS");
        sessionWorker.execute(() -> runSession(token));
    }

    public void stop() {
        AppLog.i("session", "stop() called state=" + state + " running=" + running.get());
        if (!running.getAndSet(false) && state == State.IDLE) return;
        generation.incrementAndGet();
        setState(State.STOPPING, "Останавливаю SoftEther…");
        tunnel.disconnect();
        setState(State.IDLE, "");
        sessionDiag.edit().putBoolean("active", false).apply();
    }

    private void runSession(long token) {
        String lastFailure = "";
        try {
            setState(State.SEARCHING, "SoftEther TLS: загружаю свежие relay VPN Gate…");
            try {
                int pool = new RelayUpdater(context).bootstrapMerged(BOOTSTRAP_ROUNDS);
                AppLog.i("session", "live relay bootstrap merged; pool=" + pool);
            } catch (Throwable e) {
                AppLog.w("session", "live relay bootstrap unavailable; using local pool: "
                        + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            }

            while (active(token)) {
                List<Relay> relays = new RelayStore(context).read();
                AppLog.i("session", "relay pool size=" + relays.size()
                        + " (live samples merged when reachable)");
                if (relays.isEmpty()) throw new IllegalStateException("локальный пул relay пуст");

                Set<String> tried = new HashSet<>();
                boolean connectedThisRound = false;
                Relay connectedRelay = null;
                int connectedPort = 0;

                int[] hostLimits = {Math.min(FAST_SCAN_HOSTS, relays.size()), relays.size()};
                int[] probeTimeouts = {850, 1300};

                phaseLoop:
                for (int phase = 0; phase < hostLimits.length && active(token); phase++) {
                    int hostLimit = hostLimits[phase];
                    if (hostLimit <= 0) continue;

                    String phaseName = phase == 0 ? "быстрая" : "полная";
                    setState(State.SEARCHING,
                            "SoftEther TLS: " + phaseName + " проверка TCP-портов из локальной базы…");

                    final int phaseIndex = phase;
                    List<SoftEtherProbe.Result> ranked = SoftEtherProbe.rank(
                            relays,
                            hostLimit,
                            phase == 0 ? 24 : 20,
                            probeTimeouts[phase],
                            (done, total, accepted) -> {
                                if (!active(token)) return;
                                if (done == total || done == 1 || done % 5 == 0) {
                                    setState(State.SEARCHING,
                                            "SoftEther TLS: проверено " + done + "/" + total
                                                    + " • открытых endpoint " + accepted
                                                    + (phaseIndex == 0 ? " • fast" : " • full"));
                                }
                            });

                    if (ranked.isEmpty()) {
                        AppLog.w("session", phaseName + " SoftEther scan found no TCP listeners");
                        continue;
                    }

                    AppLog.i("session", phaseName + " SoftEther candidates=" + ranked.size());

                    int remaining = 0;
                    for (SoftEtherProbe.Result candidate : ranked) {
                        if (candidate == null || candidate.relay == null) continue;
                        String candidateKey = candidate.relay.ip + ":" + candidate.port;
                        if (!tried.contains(candidateKey)) remaining++;
                    }
                    int phaseAttemptLimit = phase == 0
                            ? Math.min(FAST_ATTEMPTS, remaining)
                            : remaining;
                    if (phaseAttemptLimit <= 0) continue;

                    int attempted = 0;
                    for (SoftEtherProbe.Result result : ranked) {
                        if (!active(token)) return;
                        if (result == null || result.relay == null) continue;

                        Relay relay = result.relay;
                        String key = relay.ip + ":" + result.port;
                        if (tried.contains(key)) continue;
                        if (attempted >= phaseAttemptLimit) break;
                        tried.add(key);
                        attempted++;

                        String base = "SoftEther " + attempted + "/" + phaseAttemptLimit
                                + " • " + safe(relay.countryShort) + " " + relay.ip
                                + " • tls:" + result.port;
                        setState(State.CONNECTING, base);
                        AppLog.i("session", base);

                        boolean ok;
                        try {
                            ok = tunnel.connectBlocking(
                                    relay,
                                    result.port,
                                    SOFTETHER_ATTEMPT_TIMEOUT_MS,
                                    (stage, message) -> {
                                        if (!active(token)) return;
                                        String m = message == null || message.isEmpty() ? "" : " • " + message;
                                        setState(State.CONNECTING, base + "\n" + stage + m);
                                    });
                        } catch (Throwable e) {
                            lastFailure = e.getClass().getSimpleName() + ": " + safe(e.getMessage());
                            AppLog.e("session", "SoftEther attempt exception relay=" + relay.ip
                                    + " port=" + result.port, e);
                            ok = false;
                        }

                        if (!ok) {
                            if (!tunnel.lastFailure().isEmpty()) lastFailure = tunnel.lastFailure();
                            AppLog.w("session", "SoftEther attempt failed relay=" + relay.ip
                                    + " port=" + result.port + " reason=" + lastFailure);
                            continue;
                        }

                        connectedThisRound = true;
                        connectedRelay = relay;
                        connectedPort = result.port;
                        break phaseLoop;
                    }
                }

                if (!connectedThisRound || connectedRelay == null) {
                    throw new IllegalStateException("SoftEther не подключился; последняя причина: "
                            + (lastFailure.isEmpty() ? "нет подходящего TLS relay" : lastFailure));
                }

                final Relay sessionRelay = connectedRelay;
                setState(State.CONNECTED,
                        safe(sessionRelay.countryShort) + " " + sessionRelay.ip
                                + " • SoftEther TLS:" + connectedPort);
                AppLog.i("session", "session CONNECTED SoftEther relay="
                        + sessionRelay.ip + " port=" + connectedPort);

                tunnel.awaitConnectionLoss();

                if (!active(token)) return;
                AppLog.w("session", "SoftEther tunnel lost; automatic failover begins");
                setState(State.SEARCHING, "SoftEther туннель потерян. Ищу другой relay…");
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
            AppLog.i("session", "runSession exit token=" + token
                    + " state=" + state + " running=" + running.get());
        }
    }

    private boolean active(long token) { return running.get() && generation.get() == token; }

    private void setState(State next, String message) {
        state = next;
        detail = message == null ? "" : message;
        sessionDiag.edit()
                .putString("state", next.name())
                .putString("detail", detail)
                .putLong("updated", System.currentTimeMillis())
                .putBoolean("active", next == State.SEARCHING || next == State.CONNECTING
                        || next == State.CONNECTED || next == State.STOPPING)
                .apply();
        AppLog.i("state", next + (detail.isEmpty() ? "" : " " + detail.replace('\n', ' ')));
        for (Listener listener : listeners) {
            try { listener.onState(next, detail); }
            catch (Throwable e) { AppLog.e("state", "listener failed", e); }
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
