package org.yinglong.client.catalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/** Parser for the official VPN Gate /api/iphone/ CSV feed. */
public final class RelayCsv {
    public static final String HEADER_PREFIX = "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort";

    private RelayCsv() {}

    public static List<Relay> parse(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        List<Relay> out = new ArrayList<>();
        String line;
        boolean headerSeen = false;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.equals("*vpn_servers") || line.equals("*")) continue;
            if (line.startsWith("#")) {
                if (line.startsWith(HEADER_PREFIX)) headerSeen = true;
                continue;
            }
            if (!headerSeen) continue;

            // VPN Gate documents 15 columns. Base64 never contains commas, so limit=15
            // preserves the final OpenVPN profile as one field.
            String[] c = line.split(",", 15);
            if (c.length != 15) continue;
            try {
                out.add(new Relay(
                        c[0], c[1], longValue(c[2]), intValue(c[3]), longValue(c[4]),
                        c[5], c[6], intValue(c[7]), longValue(c[8]), longValue(c[9]),
                        longValue(c[10]), c[11], c[12], c[13], c[14]
                ));
            } catch (NumberFormatException ignored) {
                // A malformed volunteer entry must not invalidate the whole snapshot.
            }
        }
        if (!headerSeen) throw new IOException("VPN Gate CSV header not found");
        return out;
    }

    private static long longValue(String v) {
        if (v == null || v.isEmpty() || v.equals("-")) return 0L;
        return Long.parseLong(v.trim());
    }

    private static int intValue(String v) {
        if (v == null || v.isEmpty() || v.equals("-")) return 0;
        return Integer.parseInt(v.trim());
    }
}
