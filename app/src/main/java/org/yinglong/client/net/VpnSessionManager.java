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
 * Yinglong v0.7.0 OpenVPN3 Core VPN Gate session:
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
    private static final int SOFTETHER_MAX_RELAY_ATTEMPTS = 4;
    private static final long SOFTETHER_ATTEMPT_TIMEOUT_MS = 45_000L;
    private static final int SSTP_MAX_ATTEMPTS = 8;
    private static final long SSTP_ATTEMPT_TIMEOUT_MS = 30_000L;
    private static final int OPENVPN_MAX_ATTEMPTS = 40;
    private static final long OPENVPN_TCP_TIMEOUT_MS = 45_000L;
    private static final long OPENVPN_UDP_TIMEOUT_MS = 20_000L;

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
        boolean restrictedNetwork = false;

        try {
            setState(State.SEARCHING, "Загружаю свежие VPN Gate relay…");

            try {
                int pool = new RelayUpdater(context).bootstrapMerged(BOOTSTRAP_ROUNDS);
                AppLog.i("session", "live relay bootstrap merged; pool=" + pool);
            } catch (Throwable e) {
                String bootstrapError = safe(e.getMessage());
                restrictedNetwork =
                        bootstrapError.contains("VPN_GATE_DNS_BLOCKED")
                                || bootstrapError.contains("/127.0.0.1:")
                                || bootstrapError.contains("/0.0.0.0:");

                AppLog.w("session",
                        "live relay bootstrap unavailable; using bundled/local pool: "
                                + e.getClass().getSimpleName() + ": " + bootstrapError);

                if (restrictedNetwork) {
                    AppLog.w("session",
                            "RESTRICTED_NETWORK enabled: VPN Gate DNS is poisoned; "
                                    + "OpenVPN UDP will be tried before TCP");
                    setState(State.SEARCHING,
                            "Сеть фильтрует VPN Gate • сначала OpenVPN UDP…");
                }
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

                // 2) v0.6.0: go straight to aggressive OpenVPN.
                // The protocol-level SSTP scan found zero usable endpoints on
                // this pool, so do not spend time scanning that transport.
                if (connectedRelay == null && active(token) && openVpn.engineHealthy()) {
                    try { softEther.disconnect(); } catch (Throwable ignored) {}
                    try { sstp.disconnect(); } catch (Throwable ignored) {}

                    setState(State.SEARCHING,
                            "OpenVPN2/OpenVPN3: проверяю relay…");
                    AppLog.w("session",
                            "starting relay-first dual-core VPN Gate engine");

                    if (connectedRelay == null && openVpn.engineHealthy()) {
                        setState(State.SEARCHING,
                                "OpenVPN2/OpenVPN3: ранжирую relay…");

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

                        ovpnCandidates = orderOpenVpnCandidates(
                                ovpnCandidates,
                                restrictedNetwork
                        );
                        AppLog.i("session", "OpenVPN candidates="
                                + ovpnCandidates.size()
                                + " restrictedNetwork=" + restrictedNetwork);
                        Set<String> triedRelays = new HashSet<>();
                        int relayAttempt = 0;

                        setState(State.SEARCHING,
                                "OpenVPN2/OpenVPN3: A/B по живым relay…");

                        for (RelayProbe.Result result : ovpnCandidates) {
                            if (!active(token) || connectedRelay != null) break;
                            if (result == null || result.relay == null) continue;

                            Relay relay = result.relay;
                            String key = relay.ip + ":" + result.port
                                    + ":" + (result.tcp ? "tcp" : "udp");

                            if (!triedRelays.add(key)) continue;
                            if (relayAttempt >= OPENVPN_MAX_ATTEMPTS) break;
                            relayAttempt++;

                            final String endpoint = (result.tcp ? "tcp:" : "udp:")
                                    + result.port;

                            final boolean[] engineOrder = result.tcp
                                    ? new boolean[]{false, true}
                                    : new boolean[]{true, false};

                            for (boolean useOpenVpn3 : engineOrder) {
                                if (!active(token) || connectedRelay != null) break;

                                final String engineName =
                                        useOpenVpn3 ? "OpenVPN3" : "OpenVPN2";

                                if (!openVpn.setOpenVpn3Enabled(useOpenVpn3)) {
                                    AppLog.w("session",
                                            "cannot select " + engineName
                                                    + " relay=" + relay.ip);
                                    continue;
                                }

                                final String base = engineName + " • relay "
                                        + relayAttempt + "/"
                                        + Math.min(OPENVPN_MAX_ATTEMPTS,
                                                ovpnCandidates.size())
                                        + " • " + safe(relay.countryShort)
                                        + " " + relay.ip
                                        + " • " + endpoint;

                                setState(State.CONNECTING, base);
                                AppLog.i("session", "A/B START " + base);

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
                                                String extra =
                                                        message == null || message.isEmpty()
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
                                            engineName
                                                    + " A/B exception relay="
                                                    + relay.ip,
                                            e);
                                    try { openVpn.disconnect(); }
                                    catch (Throwable ignored) {}
                                }

                                if (ok) {
                                    connectedRelay = relay;
                                    connectedTransport = engineName;
                                    connectedPort = result.port;
                                    AppLog.i("session",
                                            "A/B WIN engine=" + engineName
                                                    + " relay=" + relay.ip
                                                    + " port=" + result.port);
                                    break;
                                }

                                if (!openVpn.lastFailure().isEmpty()) {
                                    lastFailure = openVpn.lastFailure();
                                }

                                AppLog.w("session",
                                        "A/B FAIL engine=" + engineName
                                                + " relay=" + relay.ip
                                                + " reason=" + lastFailure);
                            }
                        }
                    }
                }

                if (connectedRelay == null) {
                    throw new IllegalStateException(
                            "Не удалось подключиться через SoftEther/OpenVPN"
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
                } else if (sessionTransport != null
                        && sessionTransport.startsWith("OpenVPN")) {
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
            List<RelayProbe.Result> ranked,
            boolean preferUdp) {
        List<RelayProbe.Result> tcp = new ArrayList<>();
        List<RelayProbe.Result> udp = new ArrayList<>();

        for (RelayProbe.Result result : ranked) {
            if (result == null) continue;
            if (result.tcp) tcp.add(result);
            else udp.add(result);
        }

        List<RelayProbe.Result> out = new ArrayList<>(ranked.size());

        if (preferUdp) {
            out.addAll(udp);
            out.addAll(tcp);
        } else {
            out.addAll(tcp);
            out.addAll(udp);
        }

        AppLog.i("session",
                "candidate order preferUdp=" + preferUdp
                        + " udp=" + udp.size()
                        + " tcp=" + tcp.size());
        return out;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
