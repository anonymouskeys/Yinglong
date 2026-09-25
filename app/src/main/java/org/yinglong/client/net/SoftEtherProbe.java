package org.yinglong.client.net;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds reachable SoftEther TCP listeners using the concrete TCP port already
 * embedded in each locally stored VPN Gate OpenVPN profile.
 *
 * VPN Gate generates its OpenVPN TCP profile from an available TCP listener of
 * the same SoftEther VPN Server.  Therefore the profile's remote TCP port is a
 * much stronger SoftEther candidate than blindly trying 443/992/5555.
 *
 * No network catalogue refresh is performed here.
 */
public final class SoftEtherProbe {
    private SoftEtherProbe() {}

    public interface ProgressListener {
        void onProgress(int done, int total, int accepted);
    }

    private static final class HostTarget {
        final Relay relay;
        final Set<Integer> ports = new LinkedHashSet<>();

        HostTarget(Relay relay) {
            this.relay = relay;
        }
    }

    public static final class Result {
        public final Relay relay;
        public final int port;
        public final long connectMs;

        Result(Relay relay, int port, long connectMs) {
            this.relay = relay;
            this.port = port;
            this.connectMs = connectMs;
        }
    }

    public static List<Result> rank(List<Relay> relays, int maxHosts, int concurrency,
                                    int timeoutMs, ProgressListener progress) {
        List<Relay> sorted = new ArrayList<>(relays);
        sorted.sort(Comparator
                .comparingLong((Relay r) -> r.score).reversed()
                .thenComparingInt(r -> r.pingMs <= 0 ? Integer.MAX_VALUE : r.pingMs));

        Map<String, HostTarget> targets = new LinkedHashMap<>();

        for (Relay relay : sorted) {
            if (relay == null || relay.ip == null || relay.ip.trim().isEmpty()) continue;

            OpenVpnProfileUtil.Endpoint ep;
            try {
                ep = OpenVpnProfileUtil.endpoint(relay);
            } catch (Throwable ignored) {
                continue;
            }

            // UDP OpenVPN ports are not SoftEther SSL-VPN TCP listeners.
            if (ep == null || !ep.tcp || ep.port <= 0 || ep.port > 65535) continue;

            String ip = relay.ip.trim();
            HostTarget target = targets.get(ip);
            if (target == null) {
                if (targets.size() >= maxHosts) continue;
                target = new HostTarget(relay);
                targets.put(ip, target);
            }
            target.ports.add(ep.port);
        }

        List<HostTarget> hosts = new ArrayList<>(targets.values());
        int endpointCount = 0;
        for (HostTarget h : hosts) endpointCount += h.ports.size();

        AppLog.i(
                "se-probe",
                "SoftEther scan start source=local-vpngate-tcp-profiles hosts=" + hosts.size()
                        + " endpoints=" + endpointCount
                        + " concurrency=" + concurrency
                        + " timeoutMs=" + timeoutMs
        );

        if (hosts.isEmpty()) {
            AppLog.w(
                    "se-probe",
                    "no TCP profile ports in local catalogue; refusing blind 443/992/5555 scan"
            );
            return new ArrayList<>();
        }

        int workers = Math.max(1, Math.min(concurrency, 24));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CompletionService<List<Result>> completion = new ExecutorCompletionService<>(pool);

        for (HostTarget target : hosts) {
            completion.submit(() -> probeHost(target, timeoutMs));
        }

        List<Result> out = new ArrayList<>();
        int done = 0;
        try {
            while (done < hosts.size()) {
                Future<List<Result>> future = completion.take();
                done++;
                try {
                    List<Result> got = future.get();
                    if (got != null) out.addAll(got);
                } catch (Throwable e) {
                    AppLog.e("se-probe", "probe future failed", e);
                }

                if (progress != null) {
                    try {
                        progress.onProgress(done, hosts.size(), out.size());
                    } catch (Throwable e) {
                        AppLog.e("se-probe", "progress callback failed", e);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLog.w("se-probe", "scan interrupted done=" + done + "/" + hosts.size());
        } finally {
            pool.shutdownNow();
        }

        out.sort((a, b) -> {
            if (a.connectMs != b.connectMs) return Long.compare(a.connectMs, b.connectMs);
            int score = Long.compare(b.relay.score, a.relay.score);
            if (score != 0) return score;
            return Integer.compare(
                    a.relay.pingMs <= 0 ? Integer.MAX_VALUE : a.relay.pingMs,
                    b.relay.pingMs <= 0 ? Integer.MAX_VALUE : b.relay.pingMs
            );
        });

        AppLog.i("se-probe", "SoftEther scan done candidates=" + out.size());
        return out;
    }

    private static List<Result> probeHost(HostTarget target, int timeoutMs) {
        List<Result> out = new ArrayList<>();

        for (int port : target.ports) {
            long start = android.os.SystemClock.elapsedRealtime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.relay.ip, port), timeoutMs);
                long ms = Math.max(1L, android.os.SystemClock.elapsedRealtime() - start);
                AppLog.i(
                        "se-probe",
                        "catalog tcp open ip=" + target.relay.ip
                                + " port=" + port
                                + " connectMs=" + ms
                );
                out.add(new Result(target.relay, port, ms));
            } catch (Throwable ignored) {
            }
        }

        return out;
    }
}
