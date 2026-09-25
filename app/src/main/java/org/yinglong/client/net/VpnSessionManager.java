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

/**
 * Yinglong v0.5.1 hybrid session:
 * native SoftEther first, official VPN Gate OpenVPN profile as fallback.
 */
public final class VpnSessionManager {
    public enum State { IDLE, SEARCHING, CONNECTING, CONNECTED, STOPPING, ERROR }

    public interface Listener { void onState(State state, String detail); }

    private static volatile VpnSessionManager instance;

    private final Context context;
    private final SoftEtherTunnel softEther;
    private final SstpTunnel sstp;
    private final OpenVpnTunnel openVpn;
    private final SharedPreferences sessionDiag;
    private final ExecutorService sessionWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService maintenanceWorker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);

    private volatile State state = State.IDLE;
    private volatile String detail = "";

    private static final int BOOTSTRAP_ROUNDS = 3;
    private static final int SOFTETHER_MAX_RELAY_ATTEMPTS = 0;
    private static final long SOFTETHER_ATTEMPT_TIMEOUT_MS = 55_000L;
    private static final int SSTP_MAX_ATTEMPTS = 8;
    private static final long SSTP_ATTEMPT_TIMEOUT_MS = 30_000L;
    private static final int OPENVPN_MAX_ATTEMPTS = 24;
    private static final long OPENVPN_TCP_TIMEOUT_MS = 75_000L;
    private static final long OPENVPN_UDP_TIMEOUT_MS = 60_000L;

    public static VpnSessionManager get(Context context) {
        VpnSessionManager local = instance;
        if (local == null) {
            synchronized (VpnSessionManager.class) {
                local = instance;
                if (local == null) {
                    instance = local = new VpnSessionManager(context.getApplicationContext());
                }
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

        this.softEther = SoftEtherTunnel.get(context);
        this.sstp = SstpTunnel.get(context);
        this.openVpn = OpenVpnTunnel.get(context);

        AppLog.i("session", "VpnSessionManager initialized SoftEther="
                + softEther.engineHealthy() + " SSTP=" + sstp.engineHealthy()
                + " OpenVPN=" + openVpn.engineHealthy());
    }

    public State state() { return state; }
    public String detail() { return detail; }
    public boolean isRunning() { return running.get(); }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onState(state, detail);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void start() {
        boolean permission = softEther.isPermissionGranted();

        AppLog.i("session", "start() called running=" + running.get()
                + " permission=" + permission
                + " softEther=" + softEther.engineHealthy()
                + " sstp=" + sstp.engineHealthy()
                + " openVpn=" + openVpn.engineHealthy());

        if (!softEther.engineHealthy() && !sstp.engineHealthy() && !openVpn.engineHealthy()) {
            setState(State.ERROR, "VPN engines self-check failed");
            return;
        }
        if (!permission) {
            setState(State.ERROR, "Android VPN permission missing");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            AppLog.w("session", "start ignored: session already running");
            return;
        }

        long token = generation.incrementAndGet();
        sessionDiag.edit().putBoolean("active", true).apply();

        setState(State.SEARCHING, "Читаю relay и готовлю транспорт…");
        AppLog.i("session", "user session START token=" + token + " transport=hybrid");
        sessionWorker.execute(() -> runSession(token));
    }

    public void stop() {
        AppLog.i("session", "stop() called state=" + state + " running=" + running.get());

        if (!running.getAndSet(false) && state == State.IDLE) return;

        generation.incrementAndGet();
        setState(State.STOPPING, "Останавливаю VPN…");

        try { softEther.disconnect(); }
        catch (Throwable e) { AppLog.e("session", "SoftEther stop failed", e); }

        try { sstp.disconnect(); }
        catch (Throwable e) { AppLog.e("session", "SSTP stop failed", e); }

        try { openVpn.disconnect(); }
        catch (Throwable e) { AppLog.e("session", "OpenVPN stop failed", e); }

        setState(State.IDLE, "");
        sessionDiag.edit().putBoolean("active", false).apply();
    }

    private void runSession(long token) {
        boolean maintenanceStarted = false;

        try {
            setState(State.SEARCHING, "Пробую обновить список VPN Gate…");

            try {
                int pool = new RelayUpdater(context).bootstrapMerged(BOOTSTRAP_ROUNDS);
                AppLog.i("session", "live relay bootstrap merged; pool=" + pool);
            } catch (Throwable e) {
                AppLog.w("session", "live relay bootstrap unavailable; using bundled/local pool: "
                        + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            }

            while (active(token)) {
                List<Relay> relays = new RelayStore(context).read();
                AppLog.i("session", "relay pool size=" + relays.size());

                if (relays.isEmpty()) {
                    throw new IllegalStateException("локальный пул relay пуст");
                }

                String lastFailure = "";
                Relay connectedRelay = null;
                String connectedTransport = "";
                int connectedPort = 0;

                // 1) Try only a couple of different SoftEther hosts.
                if (softEther.engineHealthy() && SOFTETHER_MAX_RELAY_ATTEMPTS > 0) {
                    setState(State.SEARCHING, "SoftEther: ищу живые нативные endpoint…");

                    List<SoftEtherProbe.Result> softCandidates = SoftEtherProbe.rank(
                            relays,
                            relays.size(),
                            24,
                            1000,
                            (done, total, accepted) -> {
                                if (!active(token)) return;
                                if (done == total || done == 1 || done % 10 == 0) {
                                    setState(State.SEARCHING,
                                            "SoftEther: " + done + "/" + total
                                                    + " • открытых " + accepted);
                                }
                            });

                    Set<String> triedRelayIps = new HashSet<>();
                    int softAttempt = 0;

                    for (SoftEtherProbe.Result result : softCandidates) {
                        if (!active(token)) return;
                        if (result == null || result.relay == null) continue;

                        Relay relay = result.relay;

                        // Do not burn the entire budget on 443/992/5555 of one server.
                        if (!triedRelayIps.add(relay.ip)) continue;
                        if (softAttempt >= SOFTETHER_MAX_RELAY_ATTEMPTS) break;
                        softAttempt++;

                        final String base = "SoftEther " + softAttempt + "/"
                                + SOFTETHER_MAX_RELAY_ATTEMPTS
                                + " • " + safe(relay.countryShort) + " " + relay.ip
                                + " • tls:" + result.port;

                        setState(State.CONNECTING, base);
                        AppLog.i("session", base);

                        boolean ok;
                        try {
                            ok = softEther.connectBlocking(
                                    relay,
                                    result.port,
                                    SOFTETHER_ATTEMPT_TIMEOUT_MS,
                                    (stage, message) -> {
                                        if (!active(token)) return;
                                        String extra = message == null || message.isEmpty()
                                                ? "" : " • " + message;
                                        setState(State.CONNECTING, base + "\n" + stage + extra);
                                    });
                        } catch (Throwable e) {
                            ok = false;
                            lastFailure = e.getClass().getSimpleName()
                                    + ": " + safe(e.getMessage());
                            AppLog.e("session",
                                    "SoftEther attempt exception relay=" + relay.ip
                                            + " port=" + result.port,
                                    e);
                        }

                        if (ok) {
                            connectedRelay = relay;
                            connectedTransport = "SoftEther TLS";
                            connectedPort = result.port;
                            break;
                        }

                        if (!softEther.lastFailure().isEmpty()) {
                            lastFailure = softEther.lastFailure();
                        }

                        AppLog.w("session", "SoftEther attempt failed relay="
                                + relay.ip + " port=" + result.port
                                + " reason=" + lastFailure);
                    }
                }

                // 2) Probe real TCP endpoints once, then try MS-SSTP first.
                // SSTP rides a normal TLS/HTTP exchange and bypasses the OpenVPN
                // control handshake that this mobile path is currently stalling.
                if (connectedRelay == null && active(token)
                        && (sstp.engineHealthy() || openVpn.engineHealthy())) {
                    try { softEther.disconnect(); } catch (Throwable ignored) {}

                    setState(State.SEARCHING,
                            "Проверяю TCP relay для SSTP/OpenVPN…");
                    AppLog.w("session",
                            "starting SSTP-first multi-transport fallback");

                    if (sstp.engineHealthy()) {
                        setState(State.SEARCHING,
                                "Ищу настоящий SSTP (TLS + HTTP 200)…");

                        List<SstpProbe.Result> sstpCandidates = SstpProbe.rank(
                                relays,
                                relays.size(),
                                32,
                                800,
                                2500,
                                (done, total, accepted) -> {
                                    if (!active(token)) return;
                                    if (done == total || done == 1 || done % 40 == 0) {
                                        setState(State.SEARCHING,
                                                "SSTP probe: " + done + "/" + total
                                                        + " • настоящих " + accepted);
                                    }
                                });

                        AppLog.i("session", "real SSTP candidates="
                                + sstpCandidates.size());

                        int sstpAttempt = 0;

                        for (SstpProbe.Result result : sstpCandidates) {
                            if (!active(token)) return;
                            if (result == null || result.relay == null) continue;
                            if (sstpAttempt >= SSTP_MAX_ATTEMPTS) break;

                            sstpAttempt++;
                            Relay relay = result.relay;

                            final String base = "SSTP " + sstpAttempt + "/"
                                    + Math.min(SSTP_MAX_ATTEMPTS, sstpCandidates.size())
                                    + " • " + safe(relay.countryShort) + " " + relay.ip
                                    + " • tls:" + result.port;

                            setState(State.CONNECTING, base);
                            AppLog.i("session", base
                                    + " preflightTlsMs=" + result.tlsMs
                                    + " preflightTotalMs=" + result.totalMs);

                            boolean ok;
                            try {
                                ok = sstp.connectBlocking(
                                        relay,
                                        result.port,
                                        SSTP_ATTEMPT_TIMEOUT_MS,
                                        (stage, message) -> {
                                            if (!active(token)) return;

                                            String extra = message == null || message.isEmpty()
                                                    ? ""
                                                    : " • " + message;

                                            setState(State.CONNECTING,
                                                    base + "\n" + stage + extra);
                                        });
                            } catch (Throwable e) {
                                ok = false;
                                lastFailure = e.getClass().getSimpleName()
                                        + ": " + safe(e.getMessage());

                                AppLog.e("session",
                                        "SSTP attempt exception relay=" + relay.ip
                                                + " port=" + result.port,
                                        e);
                            }

                            if (ok) {
                                connectedRelay = relay;
                                connectedTransport = "SSTP";
                                connectedPort = result.port;
                                break;
                            }

                            if (!sstp.lastFailure().isEmpty()) {
                                lastFailure = sstp.lastFailure();
                            }

                            AppLog.w("session", "SSTP attempt failed relay="
                                    + relay.ip + " port=" + result.port
                                    + " reason=" + lastFailure);
                        }
                    }

                    if (connectedRelay == null && openVpn.engineHealthy()) {
                        setState(State.SEARCHING,
                                "SSTP не найден. Проверяю OpenVPN relay…");

                        List<RelayProbe.Result> ovpnCandidates = RelayProbe.rank(
                                relays,
                                relays.size(),
                                20,
                                1400,
                                (done, total, accepted, rejected) -> {
                                    if (!active(token)) return;
                                    if (done == total || done == 1 || done % 10 == 0) {
                                        setState(State.SEARCHING,
                                                "OpenVPN: " + done + "/" + total
                                                        + " • кандидатов " + accepted);
                                    }
                                });

                        ovpnCandidates = orderOpenVpnCandidates(ovpnCandidates);
                        AppLog.i("session", "OpenVPN candidates="
                                + ovpnCandidates.size());

                        Set<String> tried = new HashSet<>();
                        int ovpnAttempt = 0;

                        for (RelayProbe.Result result : ovpnCandidates) {
                            if (!active(token)) return;
                            if (result == null || result.relay == null) continue;

                            Relay relay = result.relay;
                            String key = relay.ip + ":" + result.port
                                    + ":" + (result.tcp ? "tcp" : "udp");

                            if (!tried.add(key)) continue;
                            if (ovpnAttempt >= OPENVPN_MAX_ATTEMPTS) break;
                            ovpnAttempt++;

                            final String endpoint = (result.tcp ? "tcp:" : "udp:")
                                    + result.port;
                            final String base = "OpenVPN " + ovpnAttempt + "/"
                                    + Math.min(OPENVPN_MAX_ATTEMPTS, ovpnCandidates.size())
                                    + " • " + safe(relay.countryShort) + " " + relay.ip
                                    + " • " + endpoint;

                            setState(State.CONNECTING, base);
                            AppLog.i("session", base);

                            boolean ok;
                            try {
                                long timeout = result.tcp
                                        ? OPENVPN_TCP_TIMEOUT_MS
                                        : OPENVPN_UDP_TIMEOUT_MS;

                                ok = openVpn.connectBlocking(
                                        relay,
                                        timeout,
                                        (stage, message) -> {
                                            if (!active(token)) return;
                                            String extra = message == null || message.isEmpty()
                                                    ? "" : " • " + message;
                                            setState(State.CONNECTING, base + "\n" + stage + extra);
                                        });
                            } catch (Throwable e) {
                                ok = false;
                                lastFailure = e.getClass().getSimpleName()
                                        + ": " + safe(e.getMessage());
                                AppLog.e("session",
                                        "OpenVPN attempt exception relay=" + relay.ip,
                                        e);
                            }

                            if (ok) {
                                connectedRelay = relay;
                                connectedTransport = "OpenVPN";
                                connectedPort = result.port;
                                break;
                            }

                            if (!openVpn.lastFailure().isEmpty()) {
                                lastFailure = openVpn.lastFailure();
                            }

                            AppLog.w("session", "OpenVPN attempt failed relay="
                                    + relay.ip + " reason=" + lastFailure);
                        }
                    }
                }

                if (connectedRelay == null) {
                    throw new IllegalStateException(
                            "Не удалось подключиться через SSTP/OpenVPN"
                                    + (lastFailure.isEmpty()
                                    ? ""
                                    : "; последняя причина: " + lastFailure));
                }

                final Relay sessionRelay = connectedRelay;
                final String sessionTransport = connectedTransport;
                final int sessionPort = connectedPort;

                setState(State.CONNECTED,
                        safe(sessionRelay.countryShort) + " " + sessionRelay.ip
                                + " • " + sessionTransport + ":" + sessionPort);

                AppLog.i("session", "session CONNECTED transport="
                        + sessionTransport + " relay=" + sessionRelay.ip
                        + " port=" + sessionPort);

                // Once we have a real VPN route, retry catalogue maintenance through it.
                if (!maintenanceStarted) {
                    maintenanceStarted = true;
                    maintenanceWorker.execute(() -> {
                        if (!active(token)) return;

                        try {
                            AppLog.i("maintenance", "post-connect relay maintenance started");
                            new PostConnectMaintenance(context).runOnce();
                            AppLog.i("maintenance", "post-connect relay maintenance finished");
                        } catch (Throwable e) {
                            AppLog.e("maintenance",
                                    "post-connect relay maintenance failed", e);
                        }
                    });
                }

                if ("SSTP".equals(sessionTransport)) {
                    sstp.awaitConnectionLoss();
                } else if ("OpenVPN".equals(sessionTransport)) {
                    openVpn.awaitConnectionLoss();
                } else {
                    softEther.awaitConnectionLoss();
                }

                if (!active(token)) return;

                AppLog.w("session", sessionTransport
                        + " tunnel lost; automatic failover begins");
                setState(State.SEARCHING, "Туннель потерян. Ищу другой relay…");
            }
        } catch (Throwable e) {
            AppLog.e("session", "session failed", e);

            if (active(token)) {
                running.set(false);

                String message = safe(e.getMessage());
                if (message.isEmpty()) message = e.getClass().getSimpleName();

                setState(State.ERROR, message);

                try { softEther.disconnect(); } catch (Throwable ignored) {}
                try { sstp.disconnect(); } catch (Throwable ignored) {}
                try { openVpn.disconnect(); } catch (Throwable ignored) {}
            }
        } finally {
            if (generation.get() == token && !running.get()
                    && state != State.ERROR) {
                setState(State.IDLE, "");
            }

            AppLog.i("session", "runSession exit token=" + token
                    + " state=" + state + " running=" + running.get());
        }
    }

    private boolean active(long token) {
        return running.get() && generation.get() == token;
    }

    private void setState(State next, String message) {
        state = next;
        detail = message == null ? "" : message;

        sessionDiag.edit()
                .putString("state", next.name())
                .putString("detail", detail)
                .putLong("updated", System.currentTimeMillis())
                .putBoolean("active",
                        next == State.SEARCHING
                                || next == State.CONNECTING
                                || next == State.CONNECTED
                                || next == State.STOPPING)
                .apply();

        AppLog.i("state",
                next + (detail.isEmpty()
                        ? ""
                        : " " + detail.replace('\n', ' ')));

        for (Listener listener : listeners) {
            try {
                listener.onState(next, detail);
            } catch (Throwable e) {
                AppLog.e("state", "listener failed", e);
            }
        }
    }

    private static List<RelayProbe.Result> orderOpenVpnCandidates(
            List<RelayProbe.Result> ranked) {
        List<RelayProbe.Result> tcp = new ArrayList<>();
        List<RelayProbe.Result> udp = new ArrayList<>();

        for (RelayProbe.Result result : ranked) {
            if (result == null) continue;
            if (result.tcp) tcp.add(result);
            else udp.add(result);
        }

        List<RelayProbe.Result> out = new ArrayList<>(ranked.size());
        int ti = 0;
        int ui = 0;

        while (ti < tcp.size() || ui < udp.size()) {
            if (ti < tcp.size()) {
                out.add(tcp.get(ti++));
            }
            if (ui < udp.size()) {
                out.add(udp.get(ui++));
            }
        }

        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
