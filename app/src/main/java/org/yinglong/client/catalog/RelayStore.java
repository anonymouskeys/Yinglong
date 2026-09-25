package org.yinglong.client.catalog;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import android.system.ErrnoException;
import android.system.Os;
import java.util.List;

public final class RelayStore {
    public static final String SEED_ASSET = "relays_seed.csv";
    private static final String ACTIVE_FILE = "relays.csv";
    private static final String TMP_FILE = "relays.csv.tmp";

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

    public synchronized List<Relay> read() throws IOException {
        ensureSeeded();
        try (FileReader r = new FileReader(activeFile())) {
            return RelayCsv.parse(r);
        }
    }

    /**
     * Validates and atomically promotes a downloaded snapshot. If validation fails,
     * the last known-good relays.csv remains untouched.
     */
    public synchronized int installDownloadedSnapshot(File downloaded, int minimumRelays) throws IOException {
        int count = validateSnapshot(downloaded, minimumRelays);
        replaceAtomically(downloaded, activeFile());
        return count;
    }

    public File newDownloadTempFile() {
        File tmp = tmpFile();
        if (tmp.exists()) tmp.delete();
        return tmp;
    }

    public File activeFile() {
        return new File(context.getFilesDir(), ACTIVE_FILE);
    }

    private File tmpFile() {
        return new File(context.getFilesDir(), TMP_FILE);
    }

    private static int validateSnapshot(File file, int minimumRelays) throws IOException {
        if (!file.isFile() || file.length() == 0) throw new IOException("Empty relay snapshot");
        int count;
        try (FileReader r = new FileReader(file)) {
            count = RelayCsv.parse(r).size();
        }
        if (count < minimumRelays) {
            throw new IOException("Relay snapshot rejected: only " + count + " valid entries");
        }
        return count;
    }

    private static void replaceAtomically(File source, File target) throws IOException {
        // source and target live in the same app files directory. POSIX rename replaces
        // the destination atomically, so a crash cannot leave a half-written catalogue.
        try {
            Os.rename(source.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Atomic relay catalogue replace failed", e);
        }
    }

    private static void copy(InputStream in, FileOutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
}
