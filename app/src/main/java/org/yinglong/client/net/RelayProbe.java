package org.yinglong.client.net;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Bounded direct reachability probes used before tunnel connection. */
public final class RelayProbe {
    private RelayProbe() {}

    public interface ProgressListener {
        void onProgress(int done, int total, int accepted, int rejected);
    }

    public static final class Result {
        public final Relay relay;
        public final long connectMs;
        public final boolean liveTcp;
        public final boolean tcp;
        public final int port;

        Result(Relay relay, long connectMs, boolean liveTcp, boolean tcp, int port) {
            this.relay = relay;
            this.connectMs = connectMs;
            this.liveTcp = liveTcp;
            this.tcp = tcp;
            this.port = port;
        }
    }

    public static List<Result> rank(List<Relay> relays, int maxCandidates, int concurrency, int timeoutMs) {
        return rank(relays, maxCandidates, concurrency, timeoutMs, null);
    }

    public static List<Result> rank(List<Relay> relays, int maxCandidates, int concurrency, int timeoutMs,
                                    ProgressListener progress) {
        AppLog.i("probe", "rank start pool=" + relays.size() + " max=" + maxCandidates
                + " concurrency=" + concurrency + " timeoutMs=" + timeoutMs);

        List<Relay> seed = new ArrayList<>(relays);
        seed.sort(Comparator
                .comparingLong((Relay r) -> r.score).reversed()
                .thenComparingInt(r -> r.pingMs <= 0 ? Integer.MAX_VALUE : r.pingMs));
        if (seed.size() > maxCandidates) seed = new ArrayList<>(seed.subList(0, maxCandidates));

        int workers = Math.max(1, Math.min(concurrency, 20));
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CompletionService<Result> completion = new ExecutorCompletionService<>(pool);
        for (Relay relay : seed) completion.submit(() -> probe(relay, timeoutMs));

        List<Result> out = new ArrayList<>();
        int rejected = 0;
        int done = 0;
        try {
            while (done < seed.size()) {
                Future<Result> f = completion.take();
                done++;
                try {
                    Result r = f.get();
                    if (r != null) out.add(r); else rejected++;
                } catch (Throwable e) {
                    rejected++;
                    AppLog.e("probe", "probe future failed", e);
                }
                if (progress != null) {
                    try { progress.onProgress(done, seed.size(), out.size(), rejected); }
                    catch (Throwable e) { AppLog.e("probe", "progress callback failed", e); }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLog.w("probe", "rank interrupted done=" + done + "/" + seed.size());
        } finally {
            pool.shutdownNow();
        }

        out.sort((a, b) -> {
            int pa = transportPriority(a);
            int pb = transportPriority(b);
            if (pa != pb) return Integer.compare(pa, pb);
            if (a.liveTcp && b.liveTcp && a.connectMs != b.connectMs) return Long.compare(a.connectMs, b.connectMs);
            int scoreCmp = Long.compare(b.relay.score, a.relay.score);
            if (scoreCmp != 0) return scoreCmp;
            return Integer.compare(a.relay.pingMs, b.relay.pingMs);
        });

        AppLog.i("probe", "rank done candidates=" + seed.size() + " accepted=" + out.size() + " rejected=" + rejected);
        return out;
    }

    public static Result probe(Relay r, int timeoutMs) {
        OpenVpnProfileUtil.Endpoint ep;
        try {
            ep = OpenVpnProfileUtil.endpoint(r);
        } catch (Throwable e) {
            AppLog.e("probe", "profile parse failed ip=" + r.ip, e);
            return null;
        }
        if (ep == null) {
            AppLog.w("probe", "no endpoint in profile ip=" + r.ip);
            return null;
        }

        if (!ep.tcp) {
            AppLog.i("probe", "udp candidate retained ip=" + r.ip + " port=" + ep.port);
            return new Result(r, Long.MAX_VALUE / 8, false, false, ep.port);
        }

        long start = android.os.SystemClock.elapsedRealtime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(r.ip, ep.port), timeoutMs);
            long ms = Math.max(1L, android.os.SystemClock.elapsedRealtime() - start);
            AppLog.i("probe", "tcp ok ip=" + r.ip + " port=" + ep.port + " connectMs=" + ms);
            return new Result(r, ms, true, true, ep.port);
        } catch (Throwable e) {
            AppLog.w("probe", "tcp fail ip=" + r.ip + " port=" + ep.port + " reason=" + e.getClass().getSimpleName());
            return null;
        }
    }

    private static int transportPriority(Result r) {
        if (r.liveTcp && r.tcp && r.port == 443) return 0;
        if (r.liveTcp && r.tcp) return 1;
        if (!r.tcp) return 2;
        return 3;
    }
}
