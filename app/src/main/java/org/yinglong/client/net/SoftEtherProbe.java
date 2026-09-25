package org.yinglong.client.net;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fast TCP reachability scan for the native SoftEther SSL-VPN listener.
 *
 * VPN Gate SoftEther servers commonly expose TCP listeners on 443, 992 and 5555.
 * A TCP-open result is only a candidate; the real SoftEther/TLS handshake is the
 * authoritative test.
 */
public final class SoftEtherProbe {
    private static final int[] PORTS = {443, 992, 5555};

    private SoftEtherProbe() {}

    public interface ProgressListener {
        void onProgress(int done, int total, int accepted);
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
        Map<String, Relay> unique = new LinkedHashMap<>();
        List<Relay> sorted = new ArrayList<>(relays);
        sorted.sort(Comparator
                .comparingLong((Relay r) -> r.score).reversed()
                .thenComparingInt(r -> r.pingMs <= 0 ? Integer.MAX_VALUE : r.pingMs));

        for (Relay r : sorted) {
            if (r == null || r.ip == null || r.ip.trim().isEmpty()) continue;
            unique.putIfAbsent(r.ip.trim(), r);
            if (unique.size() >= maxHosts) break;
        }

        List<Relay> hosts = new ArrayList<>(unique.values());
        AppLog.i("se-probe", "SoftEther scan start hosts=" + hosts.size()
                + " ports=443,992,5555 concurrency=" + concurrency
                + " timeoutMs=" + timeoutMs);

        int workers = Math.max(1, Math.min(concurrency, 24));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CompletionService<List<Result>> completion = new ExecutorCompletionService<>(pool);
        for (Relay relay : hosts) completion.submit(() -> probeHost(relay, timeoutMs));

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
                    try { progress.onProgress(done, hosts.size(), out.size()); }
                    catch (Throwable e) { AppLog.e("se-probe", "progress callback failed", e); }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLog.w("se-probe", "scan interrupted done=" + done + "/" + hosts.size());
        } finally {
            pool.shutdownNow();
        }

        out.sort((a, b) -> {
            int pa = portPriority(a.port);
            int pb = portPriority(b.port);
            if (pa != pb) return Integer.compare(pa, pb);
            if (a.connectMs != b.connectMs) return Long.compare(a.connectMs, b.connectMs);
            int score = Long.compare(b.relay.score, a.relay.score);
            if (score != 0) return score;
            return Integer.compare(a.relay.pingMs, b.relay.pingMs);
        });

        AppLog.i("se-probe", "SoftEther scan done candidates=" + out.size());
        return out;
    }

    private static List<Result> probeHost(Relay relay, int timeoutMs) {
        List<Result> out = new ArrayList<>();
        for (int port : PORTS) {
            long start = android.os.SystemClock.elapsedRealtime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(relay.ip, port), timeoutMs);
                long ms = Math.max(1L, android.os.SystemClock.elapsedRealtime() - start);
                AppLog.i("se-probe", "tcp open ip=" + relay.ip + " port=" + port
                        + " connectMs=" + ms);
                out.add(new Result(relay, port, ms));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static int portPriority(int port) {
        if (port == 443) return 0;
        if (port == 992) return 1;
        if (port == 5555) return 2;
        return 3;
    }
}
