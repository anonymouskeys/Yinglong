package org.yinglong.client.net;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Fast direct reachability probes before opening the expensive VPN handshake. */
public final class RelayProbe {
    private RelayProbe() {}

    private static final class Seed {
        final Relay relay;
        final OpenVpnProfileUtil.Endpoint endpoint;
        Seed(Relay relay, OpenVpnProfileUtil.Endpoint endpoint) {
            this.relay = relay;
            this.endpoint = endpoint;
        }
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
        AppLog.i("probe", "rank start pool=" + relays.size() + " max=" + maxCandidates
                + " concurrency=" + concurrency + " timeoutMs=" + timeoutMs);

        List<Seed> seeds = new ArrayList<>();
        for (Relay r : relays) {
            try {
                OpenVpnProfileUtil.Endpoint ep = OpenVpnProfileUtil.endpoint(r);
                if (ep != null) seeds.add(new Seed(r, ep));
            } catch (Throwable e) {
                AppLog.e("probe", "profile endpoint parse failed ip=" + r.ip, e);
            }
        }

        // In heavily filtered networks TCP/443 deserves first shot. Then other TCP. UDP is kept
        // as a fallback because a TCP connect probe cannot prove UDP reachability.
        seeds.sort(Comparator
                .comparingInt((Seed s) -> transportPriority(s.endpoint))
                .thenComparing((Seed s) -> s.relay.score, Comparator.reverseOrder())
                .thenComparingInt(s -> s.relay.pingMs <= 0 ? Integer.MAX_VALUE : s.relay.pingMs));

        if (seeds.size() > maxCandidates) seeds = new ArrayList<>(seeds.subList(0, maxCandidates));

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(concurrency, 16)));
        List<Future<Result>> futures = new ArrayList<>();
        for (Seed s : seeds) futures.add(pool.submit(() -> probe(s.relay, s.endpoint, timeoutMs)));
        pool.shutdown();

        List<Result> out = new ArrayList<>();
        int failed = 0;
        for (Future<Result> f : futures) {
            try {
                Result r = f.get();
                if (r != null) out.add(r); else failed++;
            } catch (Throwable e) {
                failed++;
                AppLog.e("probe", "probe future failed", e);
            }
        }

        out.sort(Comparator
                .comparingInt(RelayProbe::resultPriority)
                .thenComparingLong(r -> r.liveTcp ? r.connectMs : Long.MAX_VALUE / 4)
                .thenComparing((Result r) -> r.relay.score, Comparator.reverseOrder()));

        AppLog.i("probe", "rank done candidates=" + seeds.size() + " accepted=" + out.size() + " rejected=" + failed);
        int preview = Math.min(12, out.size());
        for (int i = 0; i < preview; i++) {
            Result r = out.get(i);
            AppLog.i("probe", "rank #" + (i + 1) + " ip=" + r.relay.ip
                    + " proto=" + (r.tcp ? "tcp" : "udp") + " port=" + r.port
                    + " liveTcp=" + r.liveTcp + " connectMs=" + r.connectMs
                    + " pubScore=" + r.relay.score);
        }
        return out;
    }

    public static Result probe(Relay r, int timeoutMs) {
        OpenVpnProfileUtil.Endpoint ep;
        try { ep = OpenVpnProfileUtil.endpoint(r); }
        catch (Throwable e) {
            AppLog.e("probe", "profile parse failed ip=" + r.ip, e);
            return null;
        }
        if (ep == null) return null;
        return probe(r, ep, timeoutMs);
    }

    private static Result probe(Relay r, OpenVpnProfileUtil.Endpoint ep, int timeoutMs) {
        if (!ep.tcp) {
            AppLog.i("probe", "udp fallback retained ip=" + r.ip + " port=" + ep.port);
            return new Result(r, Long.MAX_VALUE / 8, false, false, ep.port);
        }

        long start = android.os.SystemClock.elapsedRealtime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(r.ip, ep.port), timeoutMs);
            long ms = Math.max(1L, android.os.SystemClock.elapsedRealtime() - start);
            AppLog.i("probe", "tcp ok ip=" + r.ip + " port=" + ep.port + " connectMs=" + ms);
            return new Result(r, ms, true, true, ep.port);
        } catch (Throwable e) {
            AppLog.w("probe", "tcp fail ip=" + r.ip + " port=" + ep.port
                    + " reason=" + e.getClass().getSimpleName() + ": " + safe(e.getMessage()));
            return null;
        }
    }

    private static int transportPriority(OpenVpnProfileUtil.Endpoint ep) {
        if (ep.tcp && ep.port == 443) return 0;
        if (ep.tcp) return 1;
        return 2;
    }

    private static int resultPriority(Result r) {
        if (r.liveTcp && r.port == 443) return 0;
        if (r.liveTcp) return 1;
        return 2;
    }

    private static String safe(String v) { return v == null ? "" : v; }
}
