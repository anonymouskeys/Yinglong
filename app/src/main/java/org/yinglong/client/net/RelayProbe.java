package org.yinglong.client.net;

import org.yinglong.client.catalog.Relay;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded direct reachability probes used before tunnel connection. */
public final class RelayProbe {
    private RelayProbe() {}

    public static final class Result {
        public final Relay relay;
        public final long connectMs;
        public final boolean liveTcp;
        Result(Relay relay, long connectMs, boolean liveTcp) {
            this.relay = relay; this.connectMs = connectMs; this.liveTcp = liveTcp;
        }
    }

    public static List<Result> rank(List<Relay> relays, int maxCandidates, int concurrency, int timeoutMs) {
        List<Relay> seed = new ArrayList<>(relays);
        seed.sort(Comparator
                .comparingLong((Relay r) -> r.score).reversed()
                .thenComparingInt(r -> r.pingMs <= 0 ? Integer.MAX_VALUE : r.pingMs));
        if (seed.size() > maxCandidates) seed = new ArrayList<>(seed.subList(0, maxCandidates));

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(concurrency, 12)));
        List<Future<Result>> futures = new ArrayList<>();
        for (Relay r : seed) futures.add(pool.submit(() -> probe(r, timeoutMs)));
        pool.shutdown();

        List<Result> out = new ArrayList<>();
        for (Future<Result> f : futures) {
            try {
                Result r = f.get();
                if (r != null) out.add(r);
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Double.compare(score(a), score(b)));
        return out;
    }

    public static Result probe(Relay r, int timeoutMs) {
        OpenVpnProfileUtil.Endpoint ep;
        try { ep = OpenVpnProfileUtil.endpoint(r); } catch (Exception e) { return null; }
        if (ep == null) return null;
        if (!ep.tcp) {
            // UDP cannot be confirmed with a TCP connect; retain as an unknown fallback.
            return new Result(r, Long.MAX_VALUE / 8, false);
        }
        long start = android.os.SystemClock.elapsedRealtime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(r.ip, ep.port), timeoutMs);
            long ms = Math.max(1L, android.os.SystemClock.elapsedRealtime() - start);
            return new Result(r, ms, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static double score(Result r) {
        if (!r.liveTcp) return 1e12 - Math.min(1e9, r.relay.score);
        double latency = r.connectMs * 1000.0;
        double publishedPing = Math.max(0, r.relay.pingMs) * 10.0;
        double speedBonus = Math.log10(Math.max(1.0, r.relay.speedBps)) * 50.0;
        double qualityBonus = Math.log10(Math.max(1.0, r.relay.score)) * 100.0;
        return latency + publishedPing - speedBonus - qualityBonus;
    }
}
