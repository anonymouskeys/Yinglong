package org.yinglong.client.catalog;

import android.content.Context;
import android.util.Base64;

import org.yinglong.client.diag.AppLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Official VPN Gate sources only, with persistent diagnostics. */
public final class RelayUpdater {
    public static final String VPN_GATE_CSV = "https://www.vpngate.net/api/iphone/";
    public static final String VPN_GATE_LIST = "https://www.vpngate.net/en/";
    public static final String VPN_GATE_ALT_LIST = "https://download.vpngate.jp/en/";
    private static final int MINIMUM_ACCEPTED_RELAYS = 10;
    private static final int MIRROR_BOOTSTRAP_PROFILES = 16;
    private static final long MAX_BODY = 32L * 1024L * 1024L;

    private static final Pattern DETAIL_LINK = Pattern.compile(
            "href=[\\\"']([^\\\"']*do_openvpn\\.aspx\\?[^\\\"']+)[\\\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOWNLOAD_LINK = Pattern.compile(
            "href=[\\\"']([^\\\"']*openvpn_download\\.aspx\\?[^\\\"']+)[\\\"']",
            Pattern.CASE_INSENSITIVE);

    private final RelayStore store;
    private final RelayHealthStore health;

    public RelayUpdater(Context context) {
        this.store = new RelayStore(context);
        this.health = new RelayHealthStore(context);
    }

    public int refresh() throws IOException { return refreshMerged(1); }

    /**
     * Fetch a short-timeout snapshot before the first VPN attempt.
     * The returned list contains only relays from the live VPN Gate API, while the same
     * snapshot is also merged into the persistent pool for future fallback.
     */
    public List<Relay> bootstrapFresh() throws IOException {
        String url = VPN_GATE_CSV + "?_yinglong_bootstrap=" + System.currentTimeMillis();
        AppLog.i("catalog", "bootstrap refresh start");
        List<Relay> fresh = fetchCsv(url, 5_000, 10_000);
        if (fresh.size() < MINIMUM_ACCEPTED_RELAYS) {
            throw new IOException("Bootstrap relay snapshot rejected: only " + fresh.size() + " valid entries");
        }
        int pool = store.mergeRelays(fresh, MINIMUM_ACCEPTED_RELAYS);
        health.markSeen(fresh);
        AppLog.i("catalog", "bootstrap refresh received=" + fresh.size() + " mergedPool=" + pool);
        return fresh;
    }

    /**
     * Pull several short-timeout VPN Gate CSV samples before connecting.
     * VPN Gate returns a partial slice per request, so several cache-busted
     * rounds are merged into the persistent last-known-good pool.
     */
    public int bootstrapMerged(int rounds) throws IOException {
        int pool = store.read().size();
        IOException last = null;
        int ok = 0;
        int n = Math.max(1, Math.min(rounds, 8));
        AppLog.i("catalog", "bootstrapMerged rounds=" + n + " startingPool=" + pool);

        for (int i = 0; i < n; i++) {
            try {
                String url = VPN_GATE_CSV + "?_yinglong_bootstrap="
                        + System.currentTimeMillis() + "_" + i;
                List<Relay> fresh = fetchCsv(url, 4_500, 8_000);
                AppLog.i("catalog", "bootstrap API round=" + (i + 1)
                        + " received=" + fresh.size());
                pool = store.mergeRelays(fresh, MINIMUM_ACCEPTED_RELAYS);
                health.markSeen(fresh);
                ok++;
            } catch (IOException e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                AppLog.w("catalog", "bootstrap API round=" + (i + 1)
                        + " failed: " + msg);

                if (msg.contains("/127.0.0.1:")
                        || msg.contains("/0.0.0.0:")
                        || msg.contains("www.vpngate.net/127.0.0.1")) {
                    AppLog.w("catalog",
                            "VPN Gate DNS is poisoned/blocked on this network; "
                                    + "using CI-fresh bundled seed");
                    throw new IOException("VPN_GATE_DNS_BLOCKED: " + msg, e);
                }
            }

            if (i + 1 < n) {
                try {
                    Thread.sleep(350L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (ok > 0) {
            AppLog.i("catalog", "bootstrapMerged complete source=api"
                    + " successfulRounds=" + ok + " pool=" + pool);
            return pool;
        }

        try {
            pool = bootstrapFromOfficialHtml(MIRROR_BOOTSTRAP_PROFILES);
            AppLog.i("catalog", "bootstrapMerged complete source=official-html"
                    + " pool=" + pool);
            return pool;
        } catch (IOException mirrorError) {
            AppLog.w("catalog", "official HTML bootstrap failed: "
                    + mirrorError.getMessage());
            if (last != null) {
                mirrorError.addSuppressed(last);
            }
            throw mirrorError;
        }
    }

    private int bootstrapFromOfficialHtml(int maxProfiles) throws IOException {
        String[] origins = new String[] {
                VPN_GATE_ALT_LIST,
                VPN_GATE_LIST
        };

        IOException last = null;

        for (String origin : origins) {
            try {
                List<Relay> fresh = harvestProfilesFromOrigin(
                        origin,
                        maxProfiles,
                        true
                );

                if (fresh.isEmpty()) {
                    fresh = harvestProfilesFromOrigin(
                            origin,
                            Math.max(4, maxProfiles / 2),
                            false
                    );
                }

                if (fresh.isEmpty()) {
                    throw new IOException("no fresh OpenVPN profiles from " + origin);
                }

                int pool = store.mergeRelays(fresh, 1);
                health.markSeen(fresh);

                AppLog.i("catalog", "LIVE_PROFILE_BOOTSTRAP source=" + origin
                        + " profiles=" + fresh.size()
                        + " pool=" + pool);
                return pool;
            } catch (IOException e) {
                last = e;
                AppLog.w("catalog", "HTML origin failed origin=" + origin
                        + " reason=" + e.getMessage());
            }
        }

        if (last != null) throw last;
        throw new IOException("no official VPN Gate HTML origin available");
    }

    private List<Relay> harvestProfilesFromOrigin(
            String origin,
            int maxProfiles,
            boolean tcpOnly
    ) throws IOException {
        int limit = Math.max(1, Math.min(maxProfiles, 32));
        String listUrl = origin + "?_yinglong_live=" + System.currentTimeMillis();

        AppLog.i("catalog", "HTML bootstrap start origin=" + origin
                + " limit=" + limit + " tcpOnly=" + tcpOnly);

        String html = fetchText(listUrl, 6L * 1024L * 1024L, 5_000, 10_000);
        Matcher m = DETAIL_LINK.matcher(html);

        List<Relay> out = new ArrayList<>();
        Set<String> endpoints = new HashSet<>();

        while (m.find() && out.size() < limit) {
            String relative = htmlDecode(m.group(1));
            URL detailUrl = absolute(origin, relative);
            Map<String, String> q = query(detailUrl.getQuery());

            String ip = q.get("ip");
            String fqdn = q.get("fqdn");
            int tcpPort = positiveInt(q.get("tcp"));
            int udpPort = positiveInt(q.get("udp"));

            if (ip == null || ip.isEmpty()) continue;
            if (tcpOnly && tcpPort <= 0) continue;
            if (!tcpOnly && tcpPort <= 0 && udpPort <= 0) continue;

            String desired = tcpPort > 0
                    ? ip + "|tcp|" + tcpPort
                    : ip + "|udp|" + udpPort;
            if (!endpoints.add(desired)) continue;

            try {
                String detail = fetchText(
                        detailUrl.toString(),
                        768L * 1024L,
                        4_500,
                        8_000
                );

                String configUrl = chooseIpConfigUrl(detail, ip);
                if (configUrl == null || configUrl.isEmpty()) {
                    AppLog.w("catalog", "no IP .ovpn link ip=" + ip
                            + " origin=" + origin);
                    continue;
                }

                String ovpn = fetchText(
                        absolute(origin, configUrl).toString(),
                        2L * 1024L * 1024L,
                        4_500,
                        8_000
                );

                if (!looksLikeOpenVpn(ovpn)) {
                    AppLog.w("catalog", "download is not an OpenVPN profile ip=" + ip);
                    continue;
                }

                String low = ovpn.toLowerCase(java.util.Locale.US);
                if (!low.contains("remote " + ip.toLowerCase(java.util.Locale.US) + " ")) {
                    AppLog.w("catalog", "fresh profile remote mismatch ip=" + ip);
                    continue;
                }

                String b64 = Base64.encodeToString(
                        ovpn.getBytes(StandardCharsets.UTF_8),
                        Base64.NO_WRAP
                );

                Relay fresh = new Relay(
                        fqdn == null || fqdn.isEmpty() ? ip : fqdn,
                        ip,
                        0L,
                        0,
                        0L,
                        "",
                        "",
                        0,
                        0L,
                        0L,
                        0L,
                        "",
                        "VPN Gate official live HTML",
                        "live-mirror-bootstrap",
                        b64
                );

                out.add(fresh);

                String transport = tcpPort > 0
                        ? "tcp:" + tcpPort
                        : "udp:" + udpPort;

                AppLog.i("catalog", "LIVE_PROFILE ip=" + ip
                        + " endpoint=" + transport
                        + " origin=" + origin
                        + " count=" + out.size() + "/" + limit);
            } catch (Exception e) {
                AppLog.w("catalog", "live profile failed ip=" + ip
                        + " origin=" + origin
                        + " reason=" + e.getClass().getSimpleName()
                        + ": " + (e.getMessage() == null ? "" : e.getMessage()));
            }
        }

        AppLog.i("catalog", "HTML bootstrap done origin=" + origin
                + " tcpOnly=" + tcpOnly
                + " profiles=" + out.size());

        return out;
    }

    private static int positiveInt(String value) {
        if (value == null) return 0;
        try {
            int n = Integer.parseInt(value.trim());
            return n > 0 && n <= 65535 ? n : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public int refreshMerged(int rounds) throws IOException {
        int pool = store.read().size();
        IOException last = null;
        int ok = 0;
        int n = Math.max(1, Math.min(rounds, 8));
        AppLog.i("catalog", "refreshMerged rounds=" + n + " startingPool=" + pool);
        for (int i = 0; i < n; i++) {
            try {
                String url = VPN_GATE_CSV + "?_yinglong=" + System.currentTimeMillis() + "_" + i;
                List<Relay> fresh = fetchCsv(url);
                AppLog.i("catalog", "CSV round=" + (i + 1) + " received=" + fresh.size());
                pool = store.mergeRelays(fresh, MINIMUM_ACCEPTED_RELAYS);
                health.markSeen(fresh);
                ok++;
            } catch (IOException e) {
                last = e;
                AppLog.e("catalog", "CSV round=" + (i + 1) + " failed", e);
            }
            if (i + 1 < n) {
                try { Thread.sleep(900L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (ok == 0 && last != null) throw last;
        AppLog.i("catalog", "refreshMerged complete successfulRounds=" + ok + " pool=" + pool);
        return pool;
    }

    public int harvestOfficialHtml(int maxNewProfiles) throws IOException {
        int limit = Math.max(0, Math.min(maxNewProfiles, 24));
        if (limit == 0) return store.read().size();

        Set<String> have = new HashSet<>();
        for (Relay r : store.read()) have.add(r.ip);
        AppLog.i("catalog", "HTML harvest start existing=" + have.size() + " limit=" + limit);

        String html = fetchText(VPN_GATE_LIST + "?_yinglong=" + System.currentTimeMillis(), 4L * 1024L * 1024L);
        Matcher m = DETAIL_LINK.matcher(html);
        List<Relay> additions = new ArrayList<>();
        Set<String> attempted = new HashSet<>();

        while (m.find() && additions.size() < limit) {
            String relative = htmlDecode(m.group(1));
            URL detailUrl = absolute(relative);
            Map<String, String> q = query(detailUrl.getQuery());
            String ip = q.get("ip");
            String fqdn = q.get("fqdn");
            if (ip == null || ip.isEmpty() || have.contains(ip) || !attempted.add(ip)) continue;

            try {
                String detail = fetchText(detailUrl.toString(), 512L * 1024L);
                String configUrl = chooseIpConfigUrl(detail, ip);
                if (configUrl == null) continue;
                String ovpn = fetchText(absolute(configUrl).toString(), 2L * 1024L * 1024L);
                if (!looksLikeOpenVpn(ovpn)) continue;
                String b64 = Base64.encodeToString(ovpn.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                additions.add(new Relay(
                        fqdn == null ? ip : fqdn,
                        ip, 0L, 0, 0L, "", "", 0, 0L, 0L, 0L,
                        "", "VPN Gate official HTML", "harvested after successful session", b64));
                have.add(ip);
                AppLog.i("catalog", "HTML harvested ip=" + ip);
            } catch (Exception e) {
                AppLog.w("catalog", "HTML candidate failed ip=" + ip + " reason=" + e.getClass().getSimpleName());
            }
        }
        if (!additions.isEmpty()) {
            int pool = store.mergeRelays(additions, 1);
            health.markSeen(additions);
            AppLog.i("catalog", "HTML harvest added=" + additions.size() + " pool=" + pool);
            return pool;
        }
        int pool = store.read().size();
        AppLog.i("catalog", "HTML harvest added=0 pool=" + pool);
        return pool;
    }

    private static List<Relay> fetchCsv(String url) throws IOException {
        return fetchCsv(url, 15_000, 45_000);
    }

    private static List<Relay> fetchCsv(String url, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        byte[] body = fetchBytes(url, MAX_BODY, connectTimeoutMs, readTimeoutMs);
        return RelayCsv.parse(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }

    private static String chooseIpConfigUrl(String detail, String ip) {
        Matcher d = DOWNLOAD_LINK.matcher(detail);
        String udpFallback = null;
        while (d.find()) {
            String href = htmlDecode(d.group(1));
            String decoded = href.replace("&amp;", "&");
            if (!decoded.contains("host=" + ip) && !decoded.contains("host=" + urlEncodeLoose(ip))) continue;
            if (decoded.contains("tcp=1")) return decoded;
            if (udpFallback == null) udpFallback = decoded;
        }
        return udpFallback;
    }

    private static boolean looksLikeOpenVpn(String s) {
        String low = s.toLowerCase(java.util.Locale.US);
        return low.contains("client") && low.contains("remote ") && (low.contains("<ca>") || low.contains("ca "));
    }

    private static String fetchText(String url, long max) throws IOException {
        return new String(fetchBytes(url, max), StandardCharsets.UTF_8);
    }

    private static String fetchText(
            String url,
            long max,
            int connectTimeoutMs,
            int readTimeoutMs
    ) throws IOException {
        return new String(
                fetchBytes(url, max, connectTimeoutMs, readTimeoutMs),
                StandardCharsets.UTF_8
        );
    }

    private static byte[] fetchBytes(String url, long max) throws IOException {
        return fetchBytes(url, max, 15_000, 45_000);
    }

    private static byte[] fetchBytes(String url, long max, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(Math.max(1_000, connectTimeoutMs));
        c.setReadTimeout(Math.max(1_000, readTimeoutMs));
        c.setRequestProperty("User-Agent", "Yinglong/0.9.5 (+VPN Gate client)");
        c.setRequestProperty("Accept", "text/plain,text/csv,text/html,application/x-openvpn-profile,*/*;q=0.1");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " from " + url);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > max) throw new IOException("response too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private static URL absolute(String origin, String href) throws IOException {
        try {
            String h = htmlDecode(href);
            if (h.startsWith("http://") || h.startsWith("https://")) {
                return new URL(h);
            }
            return new URL(new URL(origin), h);
        } catch (Exception e) {
            throw new IOException("bad VPN Gate URL origin=" + origin, e);
        }
    }

    private static URL absolute(String href) throws IOException {
        return absolute(VPN_GATE_LIST, href);
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.replace("&amp;", "&").split("&")) {
            int p = part.indexOf('=');
            if (p <= 0) continue;
            try {
                out.put(URLDecoder.decode(part.substring(0, p), "UTF-8"),
                        URLDecoder.decode(part.substring(p + 1), "UTF-8"));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static String htmlDecode(String s) {
        return s == null ? "" : s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"");
    }

    private static String urlEncodeLoose(String s) { return s == null ? "" : s.replace(".", "%2E"); }
}
