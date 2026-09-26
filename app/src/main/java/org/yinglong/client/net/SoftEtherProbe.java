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
 * Ranks reachable SoftEther SSL-VPN TCP endpoints.
 *
 * VPN Gate normally advertises the same TCP endpoint in its SSL-VPN and
 * OpenVPN-TCP columns. The bundled .ovpn profile therefore provides the
 * strongest per-relay TCP-port hint. Standard SoftEther listener ports are
 * retained only as fallbacks.
 */
public final class SoftEtherProbe {
    private static final int[] FALLBACK_PORTS = {443, 992, 5555, 5556};

    private SoftEtherProbe() {}

    public interface ProgressListener {
        void onProgress(int done, int total, int accepted);
    }

    private static final class HostTarget {
        final Relay relay;
        final Set<Integer> ports = new LinkedHashSet<>();
        final Set<Integer> advertisedPorts = new LinkedHashSet<>();

        HostTarget(Relay relay) {
            this.relay = relay;
        }

        void add(int port, boolean advertised) {
            if (port <= 0 || port > 65535) return;
            ports.add(port);
            if (advertised) advertisedPorts.add(port);
        }
    }

    public static final class Result {
        public final Relay relay;
        public final int port;
        public final long connectMs;
        public final boolean advertised;

        Result(Relay relay, int port, long connectMs, boolean advertised) {
            this.relay = relay;
            this.port = port;
            this.connectMs = connectMs;
            this.advertised = advertised;
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

            String ip = relay.ip.trim();
            HostTarget target = targets.get(ip);
            if (target == null) {
                if (targets.size() >= maxHosts) continue;
                target = new HostTarget(relay);
                targets.put(ip, target);
            }

            try {
                OpenVpnProfileUtil.Endpoint ep = OpenVpnProfileUtil.endpoint(relay);
                if (ep != null && ep.tcp) {
                    target.add(ep.port, true);
                }
            } catch (Throwable e) {
                AppLog.w("se-probe", "profile endpoint parse failed ip=" + ip);
            }

            for (int port : FALLBACK_PORTS) {
                target.add(port, false);
            }
        }

        List<HostTarget> hosts = new ArrayList<>(targets.values());
        int endpointCount = 0;
        for (HostTarget h : hosts) endpointCount += h.ports.size();

        AppLog.i(
                "se-probe",
                "SoftEther scan start source=advertised-tcp+native-fallback hosts=" + hosts.size()
                        + " endpoints=" + endpointCount
                        + " concurrency=" + concurrency
                        + " timeoutMs=" + timeoutMs
        );

        if (hosts.isEmpty()) return new ArrayList<>();

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
            if (a.advertised != b.advertised) return a.advertised ? -1 : 1;
            if (a.connectMs != b.connectMs) return Long.compare(a.connectMs, b.connectMs);

            int score = Long.compare(b.relay.score, a.relay.score);
            if (score != 0) return score;

            int pa = fallbackPortPriority(a.port);
            int pb = fallbackPortPriority(b.port);
            if (pa != pb) return Integer.compare(pa, pb);

            return Integer.compare(
                    a.relay.pingMs <= 0 ? Integer.MAX_VALUE : a.relay.pingMs,
                    b.relay.pingMs <= 0 ? Integer.MAX_VALUE : b.relay.pingMs
            );
        });

        int advertisedCount = 0;
        for (Result r : out) if (r.advertised) advertisedCount++;

        AppLog.i("se-probe", "SoftEther scan done candidates=" + out.size()
                + " advertised=" + advertisedCount);
        return out;
    }

    private static List<Result> probeHost(HostTarget target, int timeoutMs) {
        List<Result> out = new ArrayList<>();

        for (int port : target.ports) {
            long start = android.os.SystemClock.elapsedRealtime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(target.relay.ip, port), timeoutMs);
                long ms = Math.max(1L, android.os.SystemClock.elapsedRealtime() - start);
                boolean advertised = target.advertisedPorts.contains(port);

                AppLog.i(
                        "se-probe",
                        "tcp open ip=" + target.relay.ip
                                + " port=" + port
                                + " source=" + (advertised ? "advertised" : "fallback")
                                + " connectMs=" + ms
                );
                out.add(new Result(target.relay, port, ms, advertised));
            } catch (Throwable ignored) {
            }
        }

        return out;
    }

    private static int fallbackPortPriority(int port) {
        if (port == 443) return 0;
        if (port == 992) return 1;
        if (port == 5555) return 2;
        if (port == 5556) return 3;
        return 4;
    }
}
