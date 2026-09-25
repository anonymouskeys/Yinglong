package org.yinglong.client.net;

import android.os.Build;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class SstpProbe {
    private static final int[] STANDARD_PORTS = {443, 992, 5555};

    public interface Listener {
        void onProgress(int done, int total, int accepted);
    }

    public static final class Result {
        public final Relay relay;
        public final int port;
        public final long tlsMs;
        public final long totalMs;

        Result(Relay relay, int port, long tlsMs, long totalMs) {
            this.relay = relay;
            this.port = port;
            this.tlsMs = tlsMs;
            this.totalMs = totalMs;
        }
    }

    private static final class Target {
        final Relay relay;
        final int port;

        Target(Relay relay, int port) {
            this.relay = relay;
            this.port = port;
        }
    }

    private SstpProbe() {}

    public static List<Result> rank(
            List<Relay> source,
            int maxHosts,
            int concurrency,
            int connectTimeoutMs,
            int tlsTimeoutMs,
            Listener listener
    ) {
        List<Relay> relays = new ArrayList<>(
                source == null ? Collections.emptyList() : source
        );

        relays.sort((a, b) -> Long.compare(
                b == null ? Long.MIN_VALUE : b.score,
                a == null ? Long.MIN_VALUE : a.score
        ));

        List<Target> targets = new ArrayList<>();
        Set<String> seenHosts = new LinkedHashSet<>();

        for (Relay relay : relays) {
            if (relay == null || relay.ip == null || relay.ip.trim().isEmpty()) continue;

            String ip = relay.ip.trim();
            if (!seenHosts.add(ip)) continue;
            if (seenHosts.size() > Math.max(1, maxHosts)) break;

            LinkedHashSet<Integer> ports = new LinkedHashSet<>();
            for (int port : STANDARD_PORTS) ports.add(port);

            try {
                OpenVpnProfileUtil.Endpoint ep = OpenVpnProfileUtil.endpoint(relay);
                if (ep != null && ep.tcp && ep.port > 0 && ep.port <= 65535) {
                    ports.add(ep.port);
                }
            } catch (Throwable ignored) {
            }

            for (int port : ports) {
                targets.add(new Target(relay, port));
            }
        }

        AppLog.i("sstp-probe", "start hosts=" + seenHosts.size()
                + " targets=" + targets.size()
                + " concurrency=" + concurrency
                + " connectTimeoutMs=" + connectTimeoutMs
                + " tlsTimeoutMs=" + tlsTimeoutMs);

        if (targets.isEmpty()) return Collections.emptyList();

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(1, Math.min(concurrency, 48))
        );
        CompletionService<Result> completion = new ExecutorCompletionService<>(pool);

        for (Target target : targets) {
            completion.submit(() -> probe(
                    target.relay,
                    target.port,
                    connectTimeoutMs,
                    tlsTimeoutMs
            ));
        }

        List<Result> out = new ArrayList<>();
        int done = 0;

        try {
            while (done < targets.size()) {
                try {
                    Future<Result> f = completion.take();
                    Result result = f.get();
                    if (result != null) out.add(result);
                } catch (Throwable ignored) {
                }

                done++;
                if (listener != null) {
                    try {
                        listener.onProgress(done, targets.size(), out.size());
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        out.sort(Comparator
                .comparingLong((Result r) -> r.totalMs)
                .thenComparing((a, b) -> Long.compare(
                        b.relay == null ? Long.MIN_VALUE : b.relay.score,
                        a.relay == null ? Long.MIN_VALUE : a.relay.score
                )));

        AppLog.i("sstp-probe", "done accepted=" + out.size()
                + " checked=" + targets.size());

        return out;
    }

    private static Result probe(
            Relay relay,
            int port,
            int connectTimeoutMs,
            int tlsTimeoutMs
    ) {
        String ip = relay.ip == null ? "" : relay.ip.trim();
        String tlsHost = relayHost(relay);
        long started = android.os.SystemClock.elapsedRealtime();

        Socket plain = new Socket();
        SSLSocket ssl = null;

        try {
            plain.connect(
                    new InetSocketAddress(ip, port),
                    Math.max(250, connectTimeoutMs)
            );
            plain.setSoTimeout(Math.max(700, tlsTimeoutMs));

            SSLSocketFactory factory =
                    (SSLSocketFactory) SSLSocketFactory.getDefault();

            ssl = (SSLSocket) factory.createSocket(
                    plain,
                    tlsHost,
                    port,
                    true
            );

            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                params.setServerNames(
                        Collections.singletonList(new SNIHostName(tlsHost))
                );
            }

            ssl.setSSLParameters(params);
            ssl.setSoTimeout(Math.max(700, tlsTimeoutMs));

            long tlsStart = android.os.SystemClock.elapsedRealtime();
            ssl.startHandshake();
            long tlsMs = android.os.SystemClock.elapsedRealtime() - tlsStart;

            String correlation = UUID.randomUUID()
                    .toString()
                    .toUpperCase(Locale.US);

            String request =
                    "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1\r\n"
                    + "Content-Length: 18446744073709551615\r\n"
                    + "Host: " + tlsHost + "\r\n"
                    + "SSTPCORRELATIONID: {" + correlation + "}\r\n"
                    + "\r\n";

            OutputStream out = ssl.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = ssl.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream(1024);

            int matched = 0;
            while (header.size() < 8192) {
                int b = in.read();
                if (b < 0) break;

                header.write(b);

                if ((matched == 0 && b == '\r')
                        || (matched == 1 && b == '\n')
                        || (matched == 2 && b == '\r')
                        || (matched == 3 && b == '\n')) {
                    matched++;
                    if (matched == 4) break;
                } else {
                    matched = b == '\r' ? 1 : 0;
                }
            }

            String response = new String(
                    header.toByteArray(),
                    StandardCharsets.US_ASCII
            );

            String firstLine = response;
            int eol = response.indexOf("\r\n");
            if (eol >= 0) firstLine = response.substring(0, eol);

            if (!firstLine.contains(" 200 ")) {
                AppLog.i("sstp-probe", "reject ip=" + ip
                        + " port=" + port
                        + " http=" + firstLine.replace('\n', ' '));
                return null;
            }

            long totalMs = android.os.SystemClock.elapsedRealtime() - started;

            AppLog.i("sstp-probe", "OK ip=" + ip
                    + " port=" + port
                    + " host=" + tlsHost
                    + " tlsMs=" + tlsMs
                    + " totalMs=" + totalMs);

            return new Result(relay, port, tlsMs, totalMs);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (ssl != null) ssl.close();
            } catch (Throwable ignored) {
            }
            try {
                plain.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String relayHost(Relay relay) {
        String h = relay == null || relay.hostName == null
                ? ""
                : relay.hostName.trim();

        if (h.isEmpty()) return "opengw.net";

        String low = h.toLowerCase(Locale.US);
        if (low.endsWith(".opengw.net")) return h;

        return h + ".opengw.net";
    }
}
