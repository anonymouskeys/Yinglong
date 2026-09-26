package org.yinglong.client.net;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yinglong.client.diag.AppLog;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Resolver for public bootstrap services only; TLS verification remains enabled. */
public final class BootstrapDns {
    private BootstrapDns() {}
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final class Entry {
        final List<InetAddress> addresses;
        final long expires;
        Entry(List<InetAddress> addresses, int ttl) {
            this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses));
            expires = android.os.SystemClock.elapsedRealtime() + Math.min(ttl, 300) * 1000L;
        }
    }

    // Numeric bootstrap addresses avoid depending on the broken system DNS to
    // find DoH itself. OkHttp still uses the URL hostname for SNI/certificate checks.
    private static final OkHttpClient DOH = new OkHttpClient.Builder()
            .dns(host -> {
                if (host.equals("cloudflare-dns.com"))
                    return Collections.singletonList(InetAddress.getByAddress(new byte[]{1, 1, 1, 1}));
                if (host.equals("dns.google"))
                    return Collections.singletonList(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}));
                throw new UnknownHostException("Unexpected DoH host: " + host);
            })
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .followRedirects(false).followSslRedirects(false).build();

    public static final Dns DNS = host -> {
        if (!isBootstrapHost(host)) return Dns.SYSTEM.lookup(host);
        return resolve(host);
    };

    static boolean isBootstrapHost(String host) {
        String h = normalize(host);
        return h.equals("www.vpngate.net") || h.equals("download.vpngate.jp")
                || h.endsWith(".servers.nat-traversal.softether-network.net")
                || h.endsWith(".servers.nat-traversal.uxcom.jp");
    }

    private static String normalize(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    static boolean isPublicV4(InetAddress address) {
        if (!(address instanceof Inet4Address) || address.isAnyLocalAddress()
                || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        int first = b[0] & 255, second = b[1] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19));
    }

    private static List<InetAddress> resolve(String hostname) throws UnknownHostException {
        String host = normalize(hostname);
        Entry cached = CACHE.get(host);
        if (cached != null && cached.expires > android.os.SystemClock.elapsedRealtime())
            return cached.addresses;
        try {
            List<InetAddress> normal = Dns.SYSTEM.lookup(host);
            List<InetAddress> publicV4 = new ArrayList<>();
            for (InetAddress a : normal) if (isPublicV4(a)) publicV4.add(a);
            if (!publicV4.isEmpty()) {
                CACHE.put(host, new Entry(publicV4, 60));
                AppLog.i("bootstrap-dns", "system " + host + " -> " + publicV4);
                return publicV4;
            }
            AppLog.w("bootstrap-dns", "Non-public DNS answer for " + host + ": " + normal);
        } catch (UnknownHostException e) {
            AppLog.w("bootstrap-dns", "System DNS failed for " + host + ": " + e);
        }
        Exception last = null;
        for (String provider : new String[]{"https://cloudflare-dns.com/dns-query", "https://dns.google/resolve"}) {
            try {
                HttpUrl url = HttpUrl.get(provider).newBuilder()
                        .addQueryParameter("name", host).addQueryParameter("type", "A").build();
                Request request = new Request.Builder().url(url).header("Accept", "application/dns-json").build();
                try (Response response = DOH.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null)
                        throw new IOException("DoH HTTP " + response.code());
                    String json = response.peekBody(65537).string();
                    if (json.length() > 65536) throw new IOException("Oversized DoH reply");
                    JSONObject reply = new JSONObject(json);
                    if (reply.optInt("Status", -1) != 0) throw new IOException("DNS status " + reply.optInt("Status"));
                    JSONArray answers = reply.optJSONArray("Answer");
                    List<InetAddress> ips = new ArrayList<>();
                    int ttl = 300;
                    if (answers != null) for (int i = 0; i < answers.length(); i++) {
                        JSONObject answer = answers.getJSONObject(i);
                        if (answer.optInt("type") != 1) continue;
                        String value = answer.optString("data");
                        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) continue;
                        InetAddress ip = InetAddress.getByName(value);
                        if (isPublicV4(ip)) {
                            ips.add(ip);
                            ttl = Math.min(ttl, Math.max(0, answer.optInt("TTL", 0)));
                        }
                    }
                    if (ips.isEmpty()) throw new IOException("No public A record");
                    CACHE.put(host, new Entry(ips, ttl));
                    AppLog.i("bootstrap-dns", "DoH " + host + " -> " + ips);
                    return ips;
                }
            } catch (Exception e) {
                last = e;
                AppLog.w("bootstrap-dns", "DoH failed host=" + host + " provider=" + provider + " reason=" + e);
            }
        }
        UnknownHostException failure = new UnknownHostException("Bootstrap DNS unavailable: " + host);
        if (last != null) failure.initCause(last);
        throw failure;
    }

    /** Called from Cedar's Android resolver adapter, on a native worker thread. */
    public static String resolveIpv4(String hostname) {
        if (!isBootstrapHost(hostname)) return "";
        try { return resolve(hostname).get(0).getHostAddress(); }
        catch (UnknownHostException e) { return ""; }
    }
}
