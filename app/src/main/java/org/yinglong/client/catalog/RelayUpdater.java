package org.yinglong.client.catalog;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
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

/**
 * Official VPN Gate sources only.
 * 1) CSV API: ready-to-use OpenVPN profiles.
 * 2) Public HTML list: discovers additional OpenVPN-capable relays from the
 *    official site, then downloads their IP-based .ovpn profile.
 */
public final class RelayUpdater {
    public static final String VPN_GATE_CSV = "https://www.vpngate.net/api/iphone/";
    public static final String VPN_GATE_LIST = "https://www.vpngate.net/en/";
    private static final int MINIMUM_ACCEPTED_RELAYS = 10;
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

    /** Multiple samples are merged, never replace the accumulated pool. */
    public int refreshMerged(int rounds) throws IOException {
        int pool = store.read().size();
        IOException last = null;
        int ok = 0;
        int n = Math.max(1, Math.min(rounds, 8));
        for (int i = 0; i < n; i++) {
            try {
                String url = VPN_GATE_CSV + "?_yinglong=" + System.currentTimeMillis() + "_" + i;
                List<Relay> fresh = fetchCsv(url);
                pool = store.mergeRelays(fresh, MINIMUM_ACCEPTED_RELAYS);
                health.markSeen(fresh);
                ok++;
            } catch (IOException e) { last = e; }
            if (i + 1 < n) {
                try { Thread.sleep(900L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        if (ok == 0 && last != null) throw last;
        return pool;
    }

    /**
     * Harvest a limited number of extra profiles from VPN Gate's official HTML
     * list. This is intentionally bounded to avoid hammering the academic site.
     */
    public int harvestOfficialHtml(int maxNewProfiles) throws IOException {
        int limit = Math.max(0, Math.min(maxNewProfiles, 24));
        if (limit == 0) return store.read().size();

        Set<String> have = new HashSet<>();
        for (Relay r : store.read()) have.add(r.ip);

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
                        ip,
                        0L, 0, 0L,
                        "", "", 0, 0L, 0L, 0L,
                        "", "VPN Gate official HTML", "harvested after successful session", b64));
                have.add(ip);
            } catch (Exception ignored) {
                // One disappearing volunteer node must not abort the harvest.
            }
        }
        if (!additions.isEmpty()) {
            int pool = store.mergeRelays(additions, 1);
            health.markSeen(additions);
            return pool;
        }
        return store.read().size();
    }

    private static List<Relay> fetchCsv(String url) throws IOException {
        byte[] body = fetchBytes(url, MAX_BODY);
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

    private static byte[] fetchBytes(String url, long max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(45_000);
        c.setRequestProperty("User-Agent", "Yinglong/0.2 (+VPN Gate client)");
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

    private static URL absolute(String href) throws IOException {
        try {
            String h = htmlDecode(href);
            if (h.startsWith("http://") || h.startsWith("https://")) return new URL(h);
            if (h.startsWith("../")) return new URL("https://www.vpngate.net/" + h.substring(3));
            if (h.startsWith("./")) h = h.substring(2);
            if (h.startsWith("/")) return new URL("https://www.vpngate.net" + h);
            return new URL("https://www.vpngate.net/en/" + h);
        } catch (Exception e) { throw new IOException("bad VPN Gate URL", e); }
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
