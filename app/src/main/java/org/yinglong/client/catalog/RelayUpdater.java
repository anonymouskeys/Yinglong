package org.yinglong.client.catalog;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public final class RelayUpdater {
    public static final String VPN_GATE_CSV = "https://www.vpngate.net/api/iphone/";
    private static final int MINIMUM_ACCEPTED_RELAYS = 10;

    private final RelayStore store;

    public RelayUpdater(Context context) {
        this.store = new RelayStore(context);
    }

    public int refresh() throws IOException {
        File tmp = store.newDownloadTempFile();
        HttpURLConnection connection = (HttpURLConnection) new URL(VPN_GATE_CSV).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(45_000);
        connection.setRequestProperty("User-Agent", "Yinglong/0.1 (+VPN Gate client)");
        connection.setRequestProperty("Accept", "text/plain,text/csv,*/*;q=0.1");

        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("VPN Gate HTTP " + code);
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(tmp, false)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    // Guard against a broken/malicious endpoint exhausting app storage.
                    if (total > 32L * 1024L * 1024L) throw new IOException("Relay snapshot too large");
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            return store.installDownloadedSnapshot(tmp, MINIMUM_ACCEPTED_RELAYS);
        } finally {
            connection.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }
}
