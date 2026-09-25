package org.yinglong.client.net;

import android.util.Base64;
import org.yinglong.client.catalog.Relay;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenVpnProfileUtil {
    private static final Pattern REMOTE = Pattern.compile("(?m)^\\s*remote\\s+(\\S+)\\s+(\\d+)(?:\\s+.*)?$");
    private static final Pattern PROTO = Pattern.compile("(?m)^\\s*proto\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private OpenVpnProfileUtil() {}

    public static final class Endpoint {
        public final int port;
        public final boolean tcp;
        Endpoint(int port, boolean tcp) { this.port = port; this.tcp = tcp; }
    }

    /** Stable identity for one concrete VPN transport, not merely one server IP. */
    public static String endpointKey(Relay relay) {
        if (relay == null) return "?";
        String ip = relay.ip == null ? "" : relay.ip.trim();
        try {
            Endpoint ep = endpoint(relay);
            if (ep != null) return ip + "|" + (ep.tcp ? "tcp" : "udp") + "|" + ep.port;
        } catch (Throwable ignored) {}
        String raw = relay.openVpnConfigBase64 == null ? "" : relay.openVpnConfigBase64;
        return ip + "|profile|" + Integer.toHexString(raw.hashCode());
    }

    public static String decode(Relay relay) {
        byte[] raw = Base64.decode(relay.openVpnConfigBase64, Base64.DEFAULT);
        return new String(raw, StandardCharsets.UTF_8);
    }

    public static Endpoint endpoint(Relay relay) {
        String cfg = decode(relay);
        Matcher rm = REMOTE.matcher(cfg);
        if (!rm.find()) return null;
        int port;
        try { port = Integer.parseInt(rm.group(2)); } catch (Exception e) { return null; }
        Matcher pm = PROTO.matcher(cfg);
        String proto = pm.find() ? pm.group(1).toLowerCase(Locale.US) : "udp";
        return new Endpoint(port, proto.startsWith("tcp"));
    }

    /** Force direct IP destination so .opengw.net DNS blocking cannot break the profile. */
    public static String configWithDirectIp(Relay relay) {
        String cfg = decode(relay);
        Matcher m = REMOTE.matcher(cfg);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String line = m.group(0);
            String replacement = line.replaceFirst("remote\\s+\\S+", "remote " + relay.ip);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);

        // v0.7.0 uses OpenVPN3 Core. Preserve the VPN Gate profile exactly
        // as published and only replace the remote DNS name with the relay IP.
        return out.toString();
    }
}
