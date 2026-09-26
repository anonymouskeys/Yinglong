package org.yinglong.client.net;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.LogItem;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;

/**
 * Single-owner OpenVPN engine for Yinglong.
 *
 * connect/retry never calls stopVPN itself.
 * VPNLaunchHelper.startOpenVpn(..., true) exclusively owns relay replacement.
 * stopVPN(false) is used only for a real user/session disconnect.
 */
public final class OpenVpnTunnel implements VpnStatus.StateListener, VpnStatus.LogListener {
    public interface ProgressListener {
        void onStage(String stage, String message);
    }

    private static volatile OpenVpnTunnel instance;

    private final Context context;
    private final ConnectivityManager connectivity;

    private volatile Attempt currentAttempt;
    private volatile CountDownLatch connectionLost = new CountDownLatch(0);
    private volatile boolean connected;
    private volatile Relay activeRelay;
    private volatile String lastFailure = "";
    private volatile boolean engineHealthy;
    private volatile boolean suppressParameterDump;

    private static final class Attempt {
        final Relay relay;
        final String profileUuid;
        final ProgressListener progress;
        final CountDownLatch done = new CountDownLatch(1);
        final long launchedAt = SystemClock.elapsedRealtime();

        volatile boolean success;
        volatile boolean sawProgress;
        volatile boolean serverReplied;
        volatile String failure = "not connected";
        volatile long lastProgressAt = launchedAt;
        volatile String lastStage = "NEW";

        Attempt(Relay relay, String profileUuid, ProgressListener progress) {
            this.relay = relay;
            this.profileUuid = profileUuid;
            this.progress = progress;
        }

        void stage(String stage, String message) {
            String next = stage == null || stage.isEmpty() ? "?" : stage;
            if (!next.equals(lastStage)) {
                lastStage = next;
                lastProgressAt = SystemClock.elapsedRealtime();
            }
            if (progress != null) {
                try {
                    progress.onStage(next, message == null ? "" : message);
                } catch (Throwable ignored) {
                }
            }
        }

        void progress(String stage, String message) {
            sawProgress = true;
            lastStage = stage == null || stage.isEmpty() ? lastStage : stage;
            lastProgressAt = SystemClock.elapsedRealtime();
            if (progress != null) {
                try {
                    progress.onStage(lastStage, message == null ? "" : message);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static OpenVpnTunnel get(Context context) {
        OpenVpnTunnel local = instance;
        if (local == null) {
            synchronized (OpenVpnTunnel.class) {
                local = instance;
                if (local == null) {
                    instance = local = new OpenVpnTunnel(context.getApplicationContext());
                }
            }
        }
        return local;
    }

    private OpenVpnTunnel(Context context) {
        this.context = context;
        this.connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        createNotificationChannels();
        setOpenVpn3Enabled(true);
        VpnStatus.addStateListener(this);
        VpnStatus.addLogListener(this);

        engineHealthy = validateEngineInstall();
        OpenVpnEngineDiagnostics.startup(context);
        AppLog.i("engine-v7", "OpenVPN3 Core initialized selected="
                + VpnProfile.doUseOpenVPN3(context)
                + " healthy=" + engineHealthy);
    }

    public boolean setOpenVpn3Enabled(boolean enabled) {
        boolean stored = context.getSharedPreferences(
                        context.getPackageName() + "_preferences",
                        Context.MODE_MULTI_PROCESS | Context.MODE_PRIVATE)
                .edit()
                .putBoolean("ovpn3", enabled)
                .putBoolean("usesystemproxy", false)
                .commit();

        boolean selected = VpnProfile.doUseOpenVPN3(context);
        boolean ok = stored && selected == enabled;

        AppLog.i("engine-v8", "engine select requested="
                + (enabled ? "OpenVPN3" : "OpenVPN2")
                + " stored=" + stored
                + " active=" + (selected ? "OpenVPN3" : "OpenVPN2")
                + " ok=" + ok);
        return ok;
    }

    public String selectedEngineName() {
        return VpnProfile.doUseOpenVPN3(context) ? "OpenVPN3" : "OpenVPN2";
    }

    public boolean isPermissionGranted() {
        return VpnService.prepare(context) == null;
    }

    public boolean isConnected() {
        return connected;
    }

    public Relay activeRelay() {
        return activeRelay;
    }

    public String lastFailure() {
        return lastFailure;
    }

    public boolean engineHealthy() {
        return engineHealthy;
    }

    public boolean connectBlocking(Relay relay, long timeoutMs) throws Exception {
        return connectBlocking(relay, timeoutMs, null);
    }

    public boolean connectBlocking(
            Relay relay,
            long timeoutMs,
            ProgressListener progressListener
    ) throws Exception {
        if (relay == null) {
            throw new IllegalArgumentException("relay == null");
        }
        if (!engineHealthy) {
            throw new IllegalStateException("OpenVPN engine self-check failed");
        }
        if (!isPermissionGranted()) {
            throw new IllegalStateException("Android VPN permission is not granted");
        }

        hardStopEngine("pre-start " + selectedEngineName(), 900L);

        String config = OpenVpnProfileUtil.configWithDirectIp(relay);
        if (config == null || config.trim().isEmpty()) {
            throw new IOException("empty OpenVPN profile");
        }

        OpenVpnProfileUtil.Endpoint endpoint = OpenVpnProfileUtil.endpoint(relay);
        boolean tcpTransport = endpoint == null || endpoint.tcp;
        String endpointText = endpoint == null
                ? "?"
                : ((endpoint.tcp ? "tcp" : "udp") + ":" + endpoint.port);

        AppLog.i("engine-v8", "prepare engine=" + selectedEngineName()
                + " relay=" + relay.ip
                + " endpoint=" + endpointText
                + " profileChars=" + config.length());

        VpnProfile profile;
        try {
            ConfigParser parser = new ConfigParser();
            parser.parseConfig(new StringReader(config));
            profile = parser.convertProfile();

            applyVpnGateCompatibility(profile);
        } catch (Throwable e) {
            lastFailure = "profile parse: " + e.getClass().getSimpleName()
                    + ": " + safe(e.getMessage());
            AppLog.e("engine-v4", "profile parse failed relay=" + relay.ip, e);
            throw e;
        }

        profile.mName = "Yinglong " + safe(relay.countryShort) + " " + relay.ip;
        profile.mBlockUnusedAddressFamilies = true;
        profile.mPersistTun = false;

        OpenVpnEngineDiagnostics.profile(relay, profile, config, endpoint);

        int check = profile.checkProfile(context);
        if (check != R.string.no_error_found) {
            String message;
            try {
                message = context.getString(check);
            } catch (Throwable ignored) {
                message = "profile validation failed: resource=" + check;
            }
            lastFailure = message;
            throw new IOException(message);
        }

        ProfileManager.getInstance(context);
        ProfileManager.setTemporaryProfile(context, profile);

        Attempt attempt = new Attempt(
                relay,
                profile.getUUIDString(),
                progressListener
        );

        currentAttempt = attempt;
        connected = false;
        activeRelay = null;
        lastFailure = "";

        attempt.stage("ENGINE_START", endpointText);
        AppLog.i("engine-v8", "START " + selectedEngineName()
                + " profile=" + attempt.profileUuid
                + " relay=" + relay.ip + " endpoint=" + endpointText);

        try {
            VPNLaunchHelper.startOpenVpn(profile, context, "Yinglong", true);
        } catch (Throwable t) {
            attempt.failure = "startOpenVpn: " + t.getClass().getSimpleName()
                    + ": " + safe(t.getMessage());
            lastFailure = attempt.failure;
            AppLog.e("engine-v4", "startOpenVpn failed relay=" + relay.ip, t);
            if (currentAttempt == attempt) {
                currentAttempt = null;
            }
            hardStopEngine("start failure " + selectedEngineName()
                    + " relay=" + relay.ip, 1_600L);
            return false;
        }

        long startedAt = SystemClock.elapsedRealtime();
        boolean core3 = "OpenVPN3".equals(selectedEngineName());

        long noReplyMs = tcpTransport
                ? (core3 ? 14_000L : 10_000L)
                : (core3 ? 8_000L : 7_000L);

        long repliedMs = Math.max(
                timeoutMs,
                tcpTransport ? (core3 ? 36_000L : 30_000L)
                        : (core3 ? 28_000L : 22_000L)
        );

        long noReplyDeadline = startedAt + noReplyMs;
        long repliedDeadline = startedAt + repliedMs;

        long postReplyStallMs = tcpTransport
                ? (core3 ? 24_000L : 18_000L)
                : (core3 ? 20_000L : 16_000L);

        AppLog.i("engine-v81", "A/B watchdog engine=" + selectedEngineName()
                + " relay=" + relay.ip
                + " noReplyMs=" + noReplyMs
                + " repliedMs=" + repliedMs
                + " postReplyStallMs=" + postReplyStallMs);

        boolean signalled = false;
        try {
            while (!(signalled = attempt.done.await(250L, TimeUnit.MILLISECONDS))) {
                long now = SystemClock.elapsedRealtime();
                long idle = now - attempt.lastProgressAt;

                if (!attempt.serverReplied && now >= noReplyDeadline) {
                    attempt.failure = "no server reply after " + noReplyMs
                            + " ms at " + attempt.lastStage;
                    break;
                }

                if (attempt.serverReplied && idle >= postReplyStallMs) {
                    attempt.failure = "server replied but stalled " + idle
                            + " ms at " + attempt.lastStage;
                    break;
                }

                if (attempt.serverReplied && now >= repliedDeadline) {
                    attempt.failure = "reply-phase timeout after " + repliedMs
                            + " ms at " + attempt.lastStage;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.failure = "interrupted";
        }

        if (!signalled) {
            lastFailure = attempt.failure;
            AppLog.w("engine-v7", "attempt timeout relay=" + relay.ip
                    + " reason=" + attempt.failure
                    + " serverReplied=" + attempt.serverReplied);

            attempt.stage("TIMEOUT", attempt.failure);

            if (currentAttempt == attempt) {
                currentAttempt = null;
            }
            hardStopEngine("timeout " + selectedEngineName()
                    + " relay=" + relay.ip, 1_600L);
            return false;
        }

        if (!attempt.success) {
            lastFailure = attempt.failure;
            AppLog.w("engine-v7", "attempt failed relay=" + relay.ip
                    + " reason=" + attempt.failure);
            attempt.stage("FAILED", attempt.failure);
            if (currentAttempt == attempt) {
                currentAttempt = null;
            }
            hardStopEngine("failed " + selectedEngineName()
                    + " relay=" + relay.ip, 1_600L);
            return false;
        }

        attempt.stage("VERIFY_ANDROID", "TRANSPORT_VPN");
        if (!waitForAndroidVpnTransport(5_000L)) {
            attempt.failure =
                    "OpenVPN connected but Android TRANSPORT_VPN did not appear";
            lastFailure = attempt.failure;
            connected = false;
            activeRelay = null;
            if (currentAttempt == attempt) {
                currentAttempt = null;
            }
            AppLog.w("engine-v8", attempt.failure + " relay=" + relay.ip);
            hardStopEngine("verify failed " + selectedEngineName()
                    + " relay=" + relay.ip, 1_600L);
            return false;
        }

        AppLog.i("engine-v8", "CONNECTED+VERIFIED engine="
                + selectedEngineName()
                + " relay=" + relay.ip
                + " profile=" + attempt.profileUuid);
        attempt.stage("CONNECTED", relay.ip);
        return true;
    }

    public void awaitConnectionLoss() throws InterruptedException {
        CountDownLatch latch = connectionLost;
        if (connected && latch != null) {
            latch.await();
        }
    }

    public void disconnect() {
        Attempt attempt = currentAttempt;
        if (attempt != null && attempt.done.getCount() > 0) {
            attempt.failure = "stopped";
            attempt.done.countDown();
        }

        currentAttempt = null;
        connected = false;
        activeRelay = null;

        CountDownLatch lost = connectionLost;
        if (lost != null) {
            lost.countDown();
        }

        hardStopEngine("disconnect", 1_800L);
    }

    private void hardStopEngine(String reason, long waitMs) {
        AppLog.i("engine-v8", "hard-stop begin reason=" + reason);

        requestNativeStop(false, Math.min(waitMs, 1_200L));

        try {
            context.stopService(new Intent(context, OpenVPNService.class));
        } catch (Throwable e) {
            AppLog.e("engine-v8", "stopService failed reason=" + reason, e);
        }

        SystemClock.sleep(Math.max(250L, Math.min(waitMs, 1_200L)));
        AppLog.i("engine-v8", "hard-stop end reason=" + reason);
    }

    private void requestNativeStop(boolean replaceConnection, long waitMs) {
        CountDownLatch finished = new CountDownLatch(1);

        Intent intent = new Intent(context, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);

        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IOpenVPNServiceInternal service =
                            IOpenVPNServiceInternal.Stub.asInterface(binder);
                    if (service != null) {
                        boolean requested = service.stopVPN(replaceConnection);
                        AppLog.i("engine-v4", "explicit stop requested="
                                + requested
                                + " replaceConnection=" + replaceConnection);
                    }
                } catch (Throwable e) {
                    AppLog.e("engine-v4", "explicit stop failed", e);
                } finally {
                    try {
                        context.unbindService(this);
                    } catch (Throwable ignored) {
                    }
                    finished.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                finished.countDown();
            }
        };

        boolean bound = false;
        try {
            bound = context.bindService(intent, connection, 0);
        } catch (Throwable e) {
            AppLog.e("engine-v4", "bind for explicit stop failed", e);
        }

        if (!bound) {
            finished.countDown();
            try {
                context.unbindService(connection);
            } catch (Throwable ignored) {
            }
        }

        if (waitMs > 0) {
            try {
                finished.await(waitMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void updateState(
            String state,
            String logmessage,
            int localizedResId,
            ConnectionStatus level,
            Intent intent
    ) {
        String s = state == null ? "?" : state;
        String msg = logmessage == null
                ? ""
                : logmessage.replace('\n', ' ').replace('\r', ' ');

        AppLog.i("ovpn-v4", s + " level=" + String.valueOf(level)
                + (msg.isEmpty() ? "" : " msg=" + msg));

        Attempt attempt = currentAttempt;
        if (attempt == null) {
            return;
        }

        long ageMs = SystemClock.elapsedRealtime() - attempt.launchedAt;

        if (ageMs < 3_500L
                && (level == ConnectionStatus.LEVEL_NOTCONNECTED
                || level == ConnectionStatus.LEVEL_NONETWORK)) {
            AppLog.w("ovpn-v4", "ignore stale terminal state=" + s
                    + " ageMs=" + ageMs
                    + " profile=" + attempt.profileUuid);
            return;
        }

        attempt.stage(s, msg);

        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            attempt.sawProgress = true;
            attempt.serverReplied = true;
            attempt.success = true;
            attempt.failure = "";

            connected = true;
            activeRelay = attempt.relay;
            connectionLost = new CountDownLatch(1);

            attempt.done.countDown();
            return;
        }

        if (level == ConnectionStatus.LEVEL_START
                || level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET
                || level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED) {
            attempt.sawProgress = true;
        }

        if (level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED) {
            attempt.serverReplied = true;
            attempt.lastProgressAt = SystemClock.elapsedRealtime();
        }

        boolean terminal =
                level == ConnectionStatus.LEVEL_AUTH_FAILED
                || level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
                || level == ConnectionStatus.LEVEL_NONETWORK
                || level == ConnectionStatus.LEVEL_NOTCONNECTED;

        if (!terminal) {
            return;
        }

        boolean wasConnected = connected;
        connected = false;

        String reason = s + (msg.isEmpty() ? "" : ": " + msg);
        lastFailure = reason;

        if (attempt.done.getCount() > 0) {
            attempt.success = false;
            attempt.failure = reason;
            attempt.done.countDown();
        }

        if (wasConnected) {
            CountDownLatch lost = connectionLost;
            if (lost != null) {
                lost.countDown();
            }
        }
    }

    @Override
    public void setConnectedVPN(String uuid) {
        Attempt attempt = currentAttempt;
        AppLog.i("ovpn-v4", "profile activated uuid=" + String.valueOf(uuid)
                + (attempt == null
                ? ""
                : " expected=" + attempt.profileUuid));
    }

    @Override
    public void newLog(LogItem logItem) {
        if (logItem == null) {
            return;
        }

        try {
            String line = logItem.getString(context);
            if (line == null) {
                return;
            }

            line = line.trim();
            if (line.isEmpty()) {
                return;
            }

            if (line.contains("linker: Warning:")
                    && line.contains("libovpnexec.so")
                    && line.contains("is not a directory")) {
                AppLog.i("openvpn-v4",
                        "OpenVPN2 native binary loaded; harmless upstream "
                                + "LD_LIBRARY_PATH warning suppressed");
                return;
            }

            if (line.contains("Current Parameter Settings:")) {
                suppressParameterDump = true;
                AppLog.i("openvpn-v4", "Current Parameter Settings: <suppressed>");
                return;
            }

            if (suppressParameterDump) {
                if (line.startsWith("OpenVPN ")) {
                    suppressParameterDump = false;
                } else {
                    return;
                }
            }

            AppLog.i("openvpn-v4", line);
            OpenVpnEngineDiagnostics.signal(line);

            Attempt attempt = currentAttempt;
            if (attempt == null) {
                return;
            }

            String low = line.toLowerCase(Locale.US);

            if (line.contains("TCP connection established")) {
                attempt.progress("TCP_OK", "TCP connected");
            } else if (line.startsWith("TLS: Initial packet")) {
                attempt.serverReplied = true;
                attempt.progress("TLS", "server replied");
            } else if (line.contains("VERIFY OK: depth=0")) {
                attempt.serverReplied = true;
                attempt.progress("TLS_CERT_OK", "server certificate verified");
            } else if (line.contains("Peer Connection Initiated")) {
                attempt.serverReplied = true;
                attempt.progress("PEER_OK", "TLS complete");
            } else if (line.contains("PUSH_REQUEST")) {
                attempt.progress("GET_CONFIG", "requesting routes");
            } else if (line.contains("PUSH_REPLY")) {
                attempt.progress("PUSH_REPLY", "routes received");
            } else if (line.contains("Initialization Sequence Completed")) {
                attempt.progress("INIT_COMPLETE", "OpenVPN initialized");
            } else if (low.contains("auth_failed")
                    || low.contains("unsupported protocol")
                    || low.contains("tls error")
                    || low.contains("certificate verify failed")
                    || low.contains("options error")
                    || low.contains("cannot load")
                    || low.contains("fatal error")
                    || low.contains("event(error)")
                    || low.contains("connect() error")
                    || low.contains("config file parse error")) {
                attempt.failure = line;
                lastFailure = line;
                attempt.progress("ENGINE_ERROR", line);

                // Do not let one obsolete/dead relay consume OpenVPN's own
                // exponential retry loop. Move to the next relay immediately.
                if (attempt.done.getCount() > 0) {
                    attempt.success = false;
                    attempt.done.countDown();
                }
            }
        } catch (Throwable e) {
            AppLog.e("openvpn-v4", "failed to process engine log", e);
        }
    }

    private void applyVpnGateCompatibility(VpnProfile profile) {
        /*
         * v0.7.0 runs OpenVPN3 Core from the ics-openvpn ovpn23 build.
         * These fields map to OpenVPN3 native compatibility switches instead
         * of OpenVPN 2.x/OpenSSL command-line cipher syntax.
         */
        profile.mCompatMode = 20306;
        profile.mUseLegacyProvider = true;
        profile.mTlSCertProfile = "legacy";
        profile.mConnectRetryMax = "0";
        profile.mConnectRetry = "1";
        profile.mConnectRetryMaxTime = "4";
        profile.mExpectTLSCert = true;

        String cipher = safe(profile.mCipher).trim();
        String dataCiphers = safe(profile.mDataCiphers).trim();

        if (!cipher.isEmpty() && !containsCipher(dataCiphers, cipher)) {
            profile.mDataCiphers = dataCiphers.isEmpty()
                    ? cipher
                    : dataCiphers + ":" + cipher;
        }

        String extra = safe(profile.mCustomConfigOptions);
        String low = extra.toLowerCase(Locale.US);
        if (!low.contains("tls-version-min")) {
            if (!extra.isEmpty() && !extra.endsWith("\n")) extra += "\n";
            extra += "tls-version-min 1.0\n";
            profile.mUseCustomConfig = true;
            profile.mCustomConfigOptions = extra;
        }

        AppLog.i("engine-v8",
                "compat engine=" + selectedEngineName()
                        + " legacyProvider=true tlsCertProfile=legacy"
                        + " tlsMin=1.0 retryMax=0");
    }

    private static boolean containsCipher(String list, String cipher) {
        if (list == null || list.isEmpty()
                || cipher == null || cipher.isEmpty()) {
            return false;
        }

        for (String item : list.split(":")) {
            if (cipher.equalsIgnoreCase(item.trim())) {
                return true;
            }
        }

        return false;
    }

    private boolean waitForAndroidVpnTransport(long timeoutMs) {
        if (connectivity == null) {
            return true;
        }

        long deadline =
                SystemClock.elapsedRealtime() + Math.max(500L, timeoutMs);

        do {
            try {
                Network active = connectivity.getActiveNetwork();
                NetworkCapabilities caps = active == null
                        ? null
                        : connectivity.getNetworkCapabilities(active);

                if (caps != null
                        && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    AppLog.i("engine-v4", "Android TRANSPORT_VPN verified");
                    return true;
                }
            } catch (Throwable t) {
                AppLog.e("engine-v4", "VPN transport verification failed", t);
                return true;
            }

            try {
                Thread.sleep(120L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (SystemClock.elapsedRealtime() < deadline);

        return false;
    }

    private boolean validateEngineInstall() {
        boolean serviceOk = false;
        boolean nativeOk = false;
        boolean bridgeOk = false;
        boolean selected = false;

        try {
            context.getPackageManager().getServiceInfo(
                    new ComponentName(context, OpenVPNService.class),
                    PackageManager.GET_META_DATA
            );
            serviceOk = true;
        } catch (Throwable t) {
            AppLog.e("engine-v7", "OpenVPNService missing", t);
        }

        try {
            Class.forName(
                    "de.blinkt.openvpn.core.OpenVPNThreadv3",
                    false,
                    context.getClassLoader()
            );
            bridgeOk = true;
        } catch (Throwable t) {
            AppLog.e("engine-v7", "OpenVPN3 Java bridge missing", t);
        }

        try {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            File ovpn3 = new File(nativeDir, "libovpn3.so");
            File ovpn2 = new File(nativeDir, "libovpnexec.so");

            nativeOk = ovpn3.isFile() && ovpn3.length() > 0L;

            AppLog.i("engine-v7", "nativeLibraryDir=" + nativeDir
                    + " libovpn3=" + ovpn3.exists()
                    + " ovpn3Bytes=" + (ovpn3.exists() ? ovpn3.length() : 0L)
                    + " libovpnexec=" + ovpn2.exists()
                    + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS));
        } catch (Throwable t) {
            AppLog.e("engine-v7", "native OpenVPN3 check failed", t);
        }

        try {
            selected = VpnProfile.doUseOpenVPN3(context);
        } catch (Throwable t) {
            AppLog.e("engine-v7", "OpenVPN3 selection check failed", t);
        }

        return serviceOk && nativeOk && bridgeOk && selected;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        try {
            NotificationManager nm =
                    (NotificationManager)
                            context.getSystemService(Context.NOTIFICATION_SERVICE);

            if (nm == null) {
                return;
            }

            nm.createNotificationChannel(new NotificationChannel(
                    OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
                    "Yinglong VPN",
                    NotificationManager.IMPORTANCE_MIN
            ));

            nm.createNotificationChannel(new NotificationChannel(
                    OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
                    "Yinglong VPN status",
                    NotificationManager.IMPORTANCE_LOW
            ));

            nm.createNotificationChannel(new NotificationChannel(
                    OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
                    "Yinglong VPN requests",
                    NotificationManager.IMPORTANCE_HIGH
            ));
        } catch (Throwable e) {
            AppLog.e("engine-v4", "notification channel setup failed", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
