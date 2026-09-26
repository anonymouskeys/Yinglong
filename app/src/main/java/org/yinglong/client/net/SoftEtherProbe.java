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
 * Finds reachable native SoftEther SSL-VPN TCP listeners.
 *
 * OpenVPN .ovpn "remote ... tcp" ports are OpenVPN-TCP endpoints. They are
 * NOT native SoftEther listeners: OpenVPN expects its own packet framing
 * before TLS. Native SoftEther is probed only on SoftEther listener ports.
 */
public final class SoftEtherProbe {
    private static final int[] NATIVE_PORTS = {443, 992, 5555, 5556};

    private SoftEtherProbe() {}

    public interface ProgressListener {
        void onProgress(int done, int total, int accepted);
    }

    private static final class HostTarget {
        final Relay relay;
        final Set<Integer> ports = new LinkedHashSet<>();

        HostTarget(Relay relay) {
            this.relay = relay;
            for (int port : NATIVE_PORTS) ports.add(port);
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


        Map<String, HostTarget> targets = new LinkedHashMap<>();
        for (Relay relay : sorted) {
            if (relay == null || relay.ip == null || relay.ip.trim().isEmpty()) continue;
            String ip = relay.ip.trim();
            if (targets.containsKey(ip)) continue;
            if (targets.size() >= maxHosts) break;
            targets.put(ip, new HostTarget(relay));
        }

        List<HostTarget> hosts = new ArrayList<>(targets.values());
        int endpointCount = hosts.size() * NATIVE_PORTS.length;

        AppLog.i(
                "se-probe",
                "SoftEther scan start source=native-softether-only hosts=" + hosts.size()
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
            int pa = nativePortPriority(a.port);
            int pb = nativePortPriority(b.port);
            if (pa != pb) return Integer.compare(pa, pb);
            if (a.connectMs != b.connectMs) return Long.compare(a.connectMs, b.connectMs);

            int score = Long.compare(b.relay.score, a.relay.score);
            if (score != 0) return score;

            return Integer.compare(
                    a.relay.pingMs <= 0 ? Integer.MAX_VALUE : a.relay.pingMs,
                    b.relay.pingMs <= 0 ? Integer.MAX_VALUE : b.relay.pingMs
            );
        });

        AppLog.i("se-probe", "SoftEther scan done candidates=" + out.size()
                + " source=native-softether-only");
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
                        "native tcp open ip=" + target.relay.ip
                                + " port=" + port
                                + " connectMs=" + ms
                );
                out.add(new Result(target.relay, port, ms));
            } catch (Exception failure) {
                if (port == 443) {
                    AppLog.w("se-probe", "tcp failed ip=" + target.relay.ip
                            + " port=" + port + " reason=" + failure);
                }
            }
        }
        return out;
    }

    private static int nativePortPriority(int port) {
        if (port == 443) return 0;
        if (port == 992) return 1;
        if (port == 5555) return 2;
        if (port == 5556) return 3;
        return 4;
    }
}
