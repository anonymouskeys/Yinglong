package org.yinglong.client.catalog;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Persistent accumulated relay pool. */
public final class RelayStore {
    public static final String SEED_ASSET = "relays_seed.csv";
    private static final String ACTIVE_FILE = "relays.csv";
    private static final String TMP_FILE = "relays.csv.tmp";
    private static final String HEADER = "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64";

    private final Context context;

    public RelayStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void ensureSeeded() throws IOException {
        File active = activeFile();
        if (active.isFile() && active.length() > 0) return;
        File tmp = tmpFile();
        try (InputStream in = context.getAssets().open(SEED_ASSET);
             FileOutputStream out = new FileOutputStream(tmp, false)) {
            copy(in, out);
            out.getFD().sync();
        }
        validateSnapshot(tmp, 1);
        replaceAtomically(tmp, active);
    }

    /** Merge the seed bundled in the newly installed APK into the user's existing pool. */
    public synchronized int mergeBundledSeed() throws IOException {
        ensureSeeded();
        List<Relay> bundled;
        try (InputStream in = context.getAssets().open(SEED_ASSET);
             InputStreamReader r = new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            bundled = RelayCsv.parse(r);
        }
        if (bundled.isEmpty()) return read().size();
        return mergeRelays(bundled, 1);
    }

    public synchronized List<Relay> read() throws IOException {
        ensureSeeded();
        try (FileReader r = new FileReader(activeFile())) {
            return RelayCsv.parse(r);
        }
    }

    /** Merge new snapshot into the accumulated pool. Latest data wins per IP. */
    public synchronized int mergeRelays(List<Relay> incoming, int minimumIncoming) throws IOException {
        if (incoming == null || incoming.size() < minimumIncoming) {
            throw new IOException("Relay snapshot rejected: only " + (incoming == null ? 0 : incoming.size()) + " valid entries");
        }
        LinkedHashMap<String, Relay> merged = new LinkedHashMap<>();
        for (Relay r : read()) if (valid(r)) merged.put(key(r), r);
        for (Relay r : incoming) if (valid(r)) merged.put(key(r), r);
        writeAtomically(new ArrayList<>(merged.values()));
        return merged.size();
    }

    /** Remove relays only after the health policy has positively marked them stale/dead. */
    public synchronized int removeIps(Set<String> ips) throws IOException {
        if (ips == null || ips.isEmpty()) return read().size();
        List<Relay> kept = new ArrayList<>();
        for (Relay r : read()) if (!ips.contains(r.ip)) kept.add(r);
        writeAtomically(kept);
        return kept.size();
    }

    public File activeFile() { return new File(context.getFilesDir(), ACTIVE_FILE); }
    private File tmpFile() { return new File(context.getFilesDir(), TMP_FILE); }

    private static boolean valid(Relay r) {
        return r != null && r.ip != null && !r.ip.isEmpty()
                && r.openVpnConfigBase64 != null && !r.openVpnConfigBase64.isEmpty();
    }

    private static String key(Relay r) { return r.ip.trim(); }

    private void writeAtomically(List<Relay> relays) throws IOException {
        File tmp = tmpFile();
        try (FileOutputStream fos = new FileOutputStream(tmp, false);
             BufferedWriter w = new BufferedWriter(new java.io.OutputStreamWriter(fos, java.nio.charset.StandardCharsets.UTF_8))) {
            w.write("*vpn_servers\n");
            w.write(HEADER); w.write('\n');
            for (Relay r : relays) {
                w.write(csv(r.hostName)); w.write(',');
                w.write(csv(r.ip)); w.write(',');
                w.write(Long.toString(r.score)); w.write(',');
                w.write(Integer.toString(r.pingMs)); w.write(',');
                w.write(Long.toString(r.speedBps)); w.write(',');
                w.write(csv(r.countryLong)); w.write(',');
                w.write(csv(r.countryShort)); w.write(',');
                w.write(Integer.toString(r.sessions)); w.write(',');
                w.write(Long.toString(r.uptimeMs)); w.write(',');
                w.write(Long.toString(r.totalUsers)); w.write(',');
                w.write(Long.toString(r.totalTraffic)); w.write(',');
                w.write(csv(r.logType)); w.write(',');
                w.write(csv(r.operator)); w.write(',');
                w.write(csv(r.message)); w.write(',');
                w.write(r.openVpnConfigBase64 == null ? "" : r.openVpnConfigBase64.trim());
                w.write('\n');
            }
            w.write("*\n");
            w.flush();
            fos.getFD().sync();
        }
        validateSnapshot(tmp, 1);
        replaceAtomically(tmp, activeFile());
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.replace(',', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static int validateSnapshot(File file, int minimumRelays) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("Empty relay snapshot");
        int count;
        try (FileReader r = new FileReader(file)) { count = RelayCsv.parse(r).size(); }
        if (count < minimumRelays) throw new IOException("Relay snapshot rejected: only " + count + " valid entries");
        return count;
    }

    private static void replaceAtomically(File source, File target) throws IOException {
        try { Os.rename(source.getAbsolutePath(), target.getAbsolutePath()); }
        catch (ErrnoException e) { throw new IOException("Atomic relay catalogue replace failed", e); }
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
}
