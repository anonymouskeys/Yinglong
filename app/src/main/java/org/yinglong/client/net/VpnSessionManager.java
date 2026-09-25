package org.yinglong.client.net;

import android.content.Context;
import android.content.SharedPreferences;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.catalog.RelayStore;
import org.yinglong.client.catalog.RelayUpdater;
import org.yinglong.client.diag.AppLog;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** User-scoped VPN session: fast probe -> connect -> extended probe -> maintain -> fail over. */
public final class VpnSessionManager {
    public enum State { IDLE, SEARCHING, CONNECTING, CONNECTED, STOPPING, ERROR }

    public interface Listener { void onState(State state, String detail); }

    private static volatile VpnSessionManager instance;
    private final Context context;
    private final OpenVpnTunnel tunnel;
    private final SharedPreferences sessionDiag;
    private final ExecutorService sessionWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService maintenanceWorker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);

    private volatile State state = State.IDLE;
    private volatile String detail = "";

    private static final int FAST_SCAN = 80;
    private static final int FULL_SCAN = 320;
    private static final int FAST_ATTEMPTS = 8;
    private static final int FULL_ATTEMPTS = 20;
    // OpenVPN's control-channel handshake can legitimately outlive the raw TCP connect.
    // Give old VPN Gate peers enough time to either finish or emit their own useful TLS/cipher error.
    private static final long TCP_CONNECT_TIMEOUT_MS = 70_000L;
    private static final long UDP_CONNECT_TIMEOUT_MS = 32_000L;

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
        sessionDiag.edit().putBoolean("active", true).apply();
        setState(State.SEARCHING, "Запуск диагностики…");
        AppLog.i("session", "user session START token=" + token);
        sessionWorker.execute(() -> runSession(token));
    }

    public void stop() {
        AppLog.i("session", "stop() called state=" + state + " running=" + running.get());
        if (!running.getAndSet(false) && state == State.IDLE) return;
        generation.incrementAndGet();
        setState(State.STOPPING, "Останавливаю OpenVPN…");
        tunnel.disconnect();
        setState(State.IDLE, "");
        sessionDiag.edit().putBoolean("active", false).apply();
    }

    private void runSession(long token) {
        boolean maintenanceStarted = false;
        String lastFailure = "";
        List<Relay> bootstrapRelays = null;
        try {
            if (active(token)) {
                setState(State.SEARCHING, "Обновляю свежий список VPN Gate…");
                try {
                    bootstrapRelays = new RelayUpdater(context).bootstrapFresh();
                    AppLog.i("session", "bootstrap fresh relay pool=" + bootstrapRelays.size());
                    setState(State.SEARCHING, "Свежий список получен: " + bootstrapRelays.size() + " relay");
                } catch (Throwable e) {
                    AppLog.w("session", "bootstrap refresh unavailable; using local fallback: "
                            + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
                    setState(State.SEARCHING, "Свежий список недоступен — использую локальный резерв");
                }
            }

            while (active(token)) {
                List<Relay> relays;
                if (bootstrapRelays != null && !bootstrapRelays.isEmpty()) {
                    relays = bootstrapRelays;
                    bootstrapRelays = null;
                    AppLog.i("session", "using fresh bootstrap pool size=" + relays.size());
                } else {
                    relays = new RelayStore(context).read();
                    AppLog.i("session", "using persistent fallback pool size=" + relays.size());
                }
                AppLog.i("session", "relay pool size=" + relays.size());
                if (relays.isEmpty()) throw new IllegalStateException("локальный пул relay пуст");

                Set<String> tried = new HashSet<>();
                // The current network can establish TCP and verify the server certificate, then
                // drops the rest of the OpenVPN control-channel exchange. Probe UDP first so we
                // do not burn minutes on endpoints that DPI lets half-open.
                boolean preferUdp = true;
                boolean tcpTlsFiltered = false;
                boolean connectedThisRound = false;
                Relay connectedRelay = null;

                int[] scanLimits = {Math.min(FAST_SCAN, relays.size()), Math.min(FULL_SCAN, relays.size())};
                int[] attemptLimits = {FAST_ATTEMPTS, FULL_ATTEMPTS};
                int[] probeTimeouts = {900, 1450};

                phaseLoop:
                for (int phase = 0; phase < scanLimits.length && active(token); phase++) {
                    int scan = scanLimits[phase];
                    if (scan <= 0) continue;
                    String phaseName = phase == 0 ? "быстрая" : "расширенная";
                    setState(State.SEARCHING, "Начинаю " + (phase == 0 ? "быструю" : "расширенную") + " проверку: 0/" + scan);

                    final int phaseIndex = phase;
                    List<RelayProbe.Result> ranked = RelayProbe.rank(
                            relays, scan, phase == 0 ? 18 : 16, probeTimeouts[phase],
                            (done, total, accepted, rejected) -> {
                                if (!active(token)) return;
                                if (done == total || done == 1 || done % 4 == 0) {
                                    setState(State.SEARCHING,
                                            "Проверено " + done + "/" + total
                                                    + " • кандидатов " + accepted
                                                    + " • мимо " + rejected
                                                    + (phaseIndex == 0 ? " • fast" : " • full"));
                                }
                            });

                    if (ranked.isEmpty()) {
                        AppLog.w("session", phaseName + " scan produced no reachable/rankable relays");
                        continue;
                    }
                    AppLog.i("session", phaseName + " ranked candidates=" + ranked.size());
                    ranked = orderCandidates(ranked, preferUdp);
                    AppLog.i("session", "transport order=" + (preferUdp ? "UDP-first interleaved" : "TCP-first"));

                    int attempted = 0;
                    for (RelayProbe.Result result : ranked) {
                        if (!active(token)) return;
                        if (result == null || result.relay == null) continue;
                        Relay relay = result.relay;
                        if (tcpTlsFiltered && result.tcp) {
                            AppLog.i("session", "skip TCP after post-certificate TLS filtering relay=" + relay.ip);
                            continue;
                        }
                        if (!tried.add(OpenVpnProfileUtil.endpointKey(relay))) continue;
                        if (attempted >= attemptLimits[phase]) break;
                        attempted++;

                        String transport = result.tcp ? "tcp:" + result.port : "udp:" + result.port;
                        String base = "Попытка " + attempted + "/" + attemptLimits[phase]
                                + " • " + safe(relay.countryShort) + " " + relay.ip
                                + " • " + transport;
                        setState(State.CONNECTING, base);
                        AppLog.i("session", base);

                        long connectTimeout = result.tcp ? TCP_CONNECT_TIMEOUT_MS : UDP_CONNECT_TIMEOUT_MS;
                        boolean ok;
                        try {
                            ok = tunnel.connectBlocking(relay, connectTimeout, (stage, message) -> {
                                if (!active(token)) return;
                                String m = message == null || message.isEmpty() ? "" : " • " + message;
                                setState(State.CONNECTING, base + "\n" + stage + m);
                            });
                        } catch (Throwable e) {
                            lastFailure = e.getClass().getSimpleName() + ": " + safe(e.getMessage());
                            AppLog.e("session", "attempt exception relay=" + relay.ip, e);
                            ok = false;
                        }

                        if (!ok) {
                            if (!tunnel.lastFailure().isEmpty()) lastFailure = tunnel.lastFailure();
                            if (result.tcp && looksLikePostCertificateTlsBlock(lastFailure)) {
                                tcpTlsFiltered = true;
                                preferUdp = true;
                                AppLog.w("session", "TCP control channel looks filtered after TLS certificate; "
                                        + "remaining TCP candidates will be skipped this round");
                                setState(State.SEARCHING, "TCP после сертификата блокируется — переключаюсь на UDP");
                            }
                            AppLog.w("session", "attempt failed relay=" + relay.ip + " reason=" + lastFailure);
                            continue;
                        }

                        connectedThisRound = true;
                        connectedRelay = relay;
                        break phaseLoop;
                    }
                }

                if (!connectedThisRound || connectedRelay == null) {
                    throw new IllegalStateException("не удалось подключиться; последняя причина: "
                            + (lastFailure.isEmpty() ? "нет ответа OpenVPN" : lastFailure));
                }

                final Relay sessionRelay = connectedRelay;
                setState(State.CONNECTED, safe(sessionRelay.countryShort) + " " + sessionRelay.ip);
                AppLog.i("session", "session CONNECTED relay=" + sessionRelay.ip);

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
                setState(State.SEARCHING, "Туннель потерян. Перебираю relay заново…");
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

    private static List<RelayProbe.Result> orderCandidates(List<RelayProbe.Result> ranked, boolean preferUdp) {
        if (!preferUdp || ranked == null || ranked.size() < 2) return ranked;

        List<RelayProbe.Result> udp = new ArrayList<>();
        List<RelayProbe.Result> tcp = new ArrayList<>();
        for (RelayProbe.Result r : ranked) {
            if (r == null) continue;
            if (r.tcp) tcp.add(r); else udp.add(r);
        }

        // Three quick UDP attempts, then one known-live TCP candidate.
        List<RelayProbe.Result> out = new ArrayList<>(ranked.size());
        int u = 0, t = 0;
        while (u < udp.size() || t < tcp.size()) {
            for (int burst = 0; burst < 3 && u < udp.size(); burst++) out.add(udp.get(u++));
            if (t < tcp.size()) out.add(tcp.get(t++));
        }
        return out;
    }

    private static boolean looksLikePostCertificateTlsBlock(String failure) {
        String f = safe(failure).toLowerCase(java.util.Locale.US);
        return f.contains("tls key negotiation failed")
                || f.contains("tls handshake failed")
                || f.contains("fatal tls error");
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

    private static String safe(String v) { return v == null ? "" : v; }
}
