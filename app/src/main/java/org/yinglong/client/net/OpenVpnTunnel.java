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

/** Application-facing wrapper around the embedded ics-openvpn engine. */
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

    private static final class Attempt {
        final Relay relay;
        final CountDownLatch done = new CountDownLatch(1);
        final long launchedAt = SystemClock.elapsedRealtime();
        final ProgressListener progress;
        volatile boolean success;
        volatile boolean sawProgress;
        volatile boolean serverReplied;
        volatile String failure = "not connected";
        volatile long lastProgressAt = launchedAt;
        volatile String lastStage = "NEW";

        Attempt(Relay relay, ProgressListener progress) {
            this.relay = relay;
            this.progress = progress;
        }

        void stage(String stage, String message) {
            String next = stage == null || stage.isEmpty() ? "?" : stage;
            if (!next.equals(lastStage)) {
                lastStage = next;
                lastProgressAt = SystemClock.elapsedRealtime();
            }
            if (progress == null) return;
            try { progress.onStage(next, message == null ? "" : message); }
            catch (Throwable ignored) {}
        }

        void meaningfulProgress(String stage, String message) {
            lastProgressAt = SystemClock.elapsedRealtime();
            lastStage = stage == null || stage.isEmpty() ? lastStage : stage;
            if (progress != null) {
                try { progress.onStage(lastStage, message == null ? "" : message); }
                catch (Throwable ignored) {}
            }
        }
    }

    public static OpenVpnTunnel get(Context context) {
        OpenVpnTunnel local = instance;
        if (local == null) {
            synchronized (OpenVpnTunnel.class) {
                local = instance;
                if (local == null) instance = local = new OpenVpnTunnel(context.getApplicationContext());
            }
        }
        return local;
    }

    private OpenVpnTunnel(Context context) {
        this.context = context;
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        createNotificationChannels();
        VpnStatus.addStateListener(this);
        VpnStatus.addLogListener(this);
        engineHealthy = validateEngineInstall();
        AppLog.i("tunnel", "ics-openvpn backend initialized healthy=" + engineHealthy);
    }

    public boolean isPermissionGranted() { return VpnService.prepare(context) == null; }
    public boolean isConnected() { return connected; }
    public Relay activeRelay() { return activeRelay; }
    public String lastFailure() { return lastFailure; }
    public boolean engineHealthy() { return engineHealthy; }

    public boolean connectBlocking(Relay relay, long timeoutMs) throws Exception {
        return connectBlocking(relay, timeoutMs, null);
    }

    public boolean connectBlocking(Relay relay, long timeoutMs, ProgressListener progress) throws Exception {
        if (relay == null) throw new IllegalArgumentException("relay == null");
        if (!engineHealthy) throw new IllegalStateException("OpenVPN engine self-check failed; see log");
        if (!isPermissionGranted()) throw new IllegalStateException("Android VPN permission is not granted");

        // Always tear down a stale service/tun from a previous attempt/process before a new relay.
        // This is cheap when nothing is running and prevents an old session from blocking failover.
        stopEngine(650L);
        // OpenVPNService teardown is asynchronous. Do not start the replacement profile while
        // Android still reports the old VPN as the active transport; that race produces a
        // transient NONETWORK on some Samsung/Android 16 builds.
        waitForUnderlyingNetwork(1_800L);

        String config = OpenVpnProfileUtil.configWithDirectIp(relay);
        if (config == null || config.trim().isEmpty()) throw new IOException("empty OpenVPN profile");

        OpenVpnProfileUtil.Endpoint endpoint = OpenVpnProfileUtil.endpoint(relay);
        String endpointText = endpoint == null ? "?" : ((endpoint.tcp ? "tcp" : "udp") + ":" + endpoint.port);
        AppLog.i("tunnel", "profile relay=" + relay.ip + " endpoint=" + endpointText + " chars=" + config.length());
        if (progress != null) progress.onStage("PROFILE", endpointText);

        VpnProfile profile;
        try {
            ConfigParser parser = new ConfigParser();
            parser.parseConfig(new StringReader(config));
            profile = parser.convertProfile();
            applyVpnGateCompatibility(profile);
        } catch (Throwable e) {
            lastFailure = "profile parse: " + e.getClass().getSimpleName();
            AppLog.e("tunnel", "profile parse failed for " + relay.ip, e);
            throw e;
        }

        profile.mName = "Yinglong " + safe(relay.countryShort) + " " + relay.ip;
        profile.mBlockUnusedAddressFamilies = true;
        // Important for failover: do NOT keep a stale TUN across our separate relay attempts.
        profile.mPersistTun = false;

        int check = profile.checkProfile(context);
        if (check != R.string.no_error_found) {
            String message;
            try { message = context.getString(check); }
            catch (Throwable ignored) { message = "profile validation failed: resource=" + check; }
            lastFailure = message;
            throw new IOException(message);
        }
        if (progress != null) progress.onStage("PROFILE_OK", endpointText);

        ProfileManager.getInstance(context);
        ProfileManager.setTemporaryProfile(context, profile);

        Attempt attempt = new Attempt(relay, progress);
        currentAttempt = attempt;
        connected = false;
        activeRelay = null;
        lastFailure = "";

        AppLog.i("tunnel", "START OpenVPN relay=" + relay.ip + " country=" + safe(relay.countryShort));
        attempt.stage("ENGINE_START", "запускаю OpenVPN");
        try {
            // replace_running_vpn=true makes a stale ics-openvpn instance replaceable instead of
            // silently refusing a new profile during rapid failover.
            VPNLaunchHelper.startOpenVpn(profile, context, "Yinglong", true);
            AppLog.i("tunnel", "startOpenVpn returned relay=" + relay.ip);
            attempt.stage("ENGINE_STARTED", "жду OpenVPN state");
        } catch (Throwable t) {
            attempt.failure = "startOpenVpn exception: " + t.getClass().getSimpleName() + ": " + safe(t.getMessage());
            lastFailure = attempt.failure;
            AppLog.e("tunnel", "startOpenVpn threw for " + relay.ip, t);
            currentAttempt = null;
            return false;
        }

        boolean signaled = false;
        long overallMs = Math.max(15_000L, timeoutMs);
        long overallDeadline = SystemClock.elapsedRealtime() + overallMs;
        AppLog.i("tunnel", "watchdog relay=" + relay.ip + " overallMs=" + overallMs
                + " preReplyStallMs=12000 postReplyStallMs=65000");
        try {
            while (!(signaled = attempt.done.await(250L, TimeUnit.MILLISECONDS))) {
                long now = SystemClock.elapsedRealtime();
                long idle = now - attempt.lastProgressAt;
                // Before the server replies we fail quickly. Once TLS/AUTH has started, let the
                // OpenVPN control-channel handshake breathe. The previous 22s watchdog killed every
                // tested relay at TLS_CERT_OK before OpenVPN's own handshake window could expire.
                long stallLimit = attempt.serverReplied ? 65_000L : 12_000L;
                if (idle >= stallLimit) {
                    attempt.failure = "stalled " + idle + " ms at " + attempt.lastStage;
                    break;
                }
                if (now >= overallDeadline) {
                    attempt.failure = "overall timeout after " + overallMs + " ms at " + attempt.lastStage;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.failure = "interrupted";
        }

        if (!signaled) {
            lastFailure = attempt.failure;
            AppLog.w("tunnel", attempt.failure + " relay=" + relay.ip
                    + " sawProgress=" + attempt.sawProgress + " serverReplied=" + attempt.serverReplied);
            attempt.stage("TIMEOUT", attempt.failure);
            stopEngine(650L);
            return false;
        }

        if (!attempt.success) {
            lastFailure = attempt.failure;
            AppLog.w("tunnel", "connect failed relay=" + relay.ip + " reason=" + attempt.failure);
            attempt.stage("FAILED", attempt.failure);
            stopEngine(650L);
            return false;
        }

        attempt.stage("VERIFY_ANDROID", "проверяю TRANSPORT_VPN");
        if (!waitForAndroidVpnTransport(4500L)) {
            attempt.failure = "OpenVPN said CONNECTED but Android TRANSPORT_VPN did not appear";
            lastFailure = attempt.failure;
            AppLog.w("tunnel", attempt.failure + " relay=" + relay.ip);
            attempt.stage("VERIFY_FAILED", attempt.failure);
            stopEngine(650L);
            return false;
        }

        AppLog.i("tunnel", "CONNECTED+VERIFIED relay=" + relay.ip);
        attempt.stage("CONNECTED", relay.ip);
        return true;
    }

    public void awaitConnectionLoss() throws InterruptedException {
        CountDownLatch latch = connectionLost;
        if (connected && latch != null) latch.await();
    }

    public void disconnect() { stopEngine(650L); }

    private void stopEngine(long waitMs) {
        Attempt attempt = currentAttempt;
        if (attempt != null && attempt.done.getCount() > 0) {
            attempt.failure = "stopped";
            attempt.done.countDown();
        }
        CountDownLatch lost = connectionLost;
        if (lost != null) lost.countDown();

        connected = false;
        activeRelay = null;
        currentAttempt = null;

        CountDownLatch callFinished = new CountDownLatch(1);
        Intent intent = new Intent(context, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IOpenVPNServiceInternal service = IOpenVPNServiceInternal.Stub.asInterface(binder);
                    if (service != null) {
                        boolean requested = service.stopVPN(true);
                        AppLog.i("tunnel", "stopVPN sent result=" + requested);
                    }
                } catch (Throwable e) {
                    AppLog.e("tunnel", "stopVPN failed", e);
                } finally {
                    try { context.unbindService(this); } catch (Throwable ignored) {}
                    callFinished.countDown();
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) { callFinished.countDown(); }
        };

        boolean bound = false;
        try { bound = context.bindService(intent, connection, 0); }
        catch (Throwable e) { AppLog.e("tunnel", "bind OpenVPNService for stop failed", e); }

        if (!bound) {
            callFinished.countDown();
            try { context.unbindService(connection); } catch (Throwable ignored) {}
            AppLog.i("tunnel", "OpenVPNService not running during stop");
        }

        if (waitMs > 0) {
            try { callFinished.await(waitMs, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public void updateState(String state, String logmessage, int localizedResId,
                                      ConnectionStatus level, Intent intent) {
        String s = state == null ? "?" : state;
        String msg = logmessage == null ? "" : logmessage.replace('\n', ' ').replace('\r', ' ');
        AppLog.i("ovpn-state", s + " level=" + String.valueOf(level) + (msg.isEmpty() ? "" : " msg=" + msg));

        Attempt attempt = currentAttempt;
        if (attempt == null) return;

        attempt.stage(s, msg);

        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            attempt.sawProgress = true;
            connected = true;
            activeRelay = attempt.relay;
            connectionLost = new CountDownLatch(1);
            attempt.success = true;
            attempt.failure = "";
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

        // A rapid stop/start can make ics-openvpn emit NONETWORK for a few hundred ms even while
        // Android already has a usable cellular/Wi-Fi transport. Treat only that early case as a
        // teardown race; a real network loss still remains terminal.
        if (level == ConnectionStatus.LEVEL_NONETWORK) {
            long age = SystemClock.elapsedRealtime() - attempt.launchedAt;
            if (age < 2_200L && hasUsableUnderlyingNetwork()) {
                AppLog.w("ovpn-state", "ignoring transient NONETWORK ageMs=" + age
                        + " relay=" + attempt.relay.ip);
                attempt.meaningfulProgress("NETWORK_SETTLE", "базовая сеть уже доступна; продолжаю");
                return;
            }
        }

        boolean terminal = level == ConnectionStatus.LEVEL_AUTH_FAILED
                || level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
                || level == ConnectionStatus.LEVEL_NONETWORK
                || level == ConnectionStatus.LEVEL_NOTCONNECTED;

        if (!terminal) return;

        // Any NOTCONNECTED before this attempt has produced its first actual OpenVPN progress is
        // treated as a stale callback from teardown. The watchdog timeout will catch a true no-start.
        if (level == ConnectionStatus.LEVEL_NOTCONNECTED && !attempt.sawProgress) {
            long age = SystemClock.elapsedRealtime() - attempt.launchedAt;
            AppLog.w("ovpn-state", "ignoring pre-progress NOTCONNECTED ageMs=" + age + " relay=" + attempt.relay.ip);
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
            AppLog.w("tunnel", "connected tunnel ended: " + reason);
            CountDownLatch lost = connectionLost;
            if (lost != null) lost.countDown();
        }
    }

    @Override public void setConnectedVPN(String uuid) {
        AppLog.i("ovpn-state", "connected profile uuid=" + String.valueOf(uuid));
    }

    private volatile boolean suppressParameterDump;

    @Override public void newLog(LogItem logItem) {
        if (logItem == null) return;
        try {
            String line = logItem.getString(context);
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty()) return;

            // ics-openvpn prints hundreds of parameter lines at verbosity 4. They drown the useful
            // handshake diagnostics, so keep the start/end marker but suppress the body.
            if (line.contains("Current Parameter Settings:")) {
                suppressParameterDump = true;
                AppLog.i("openvpn", "Current Parameter Settings: <suppressed>");
                return;
            }
            if (suppressParameterDump) {
                if (line.startsWith("OpenVPN ")) {
                    suppressParameterDump = false;
                } else {
                    return;
                }
            }

            AppLog.i("openvpn", line);
            Attempt attempt = currentAttempt;
            if (attempt == null) return;

            String low = line.toLowerCase(java.util.Locale.US);
            if (line.contains("TCP connection established")) {
                attempt.meaningfulProgress("TCP_OK", "TCP соединение установлено");
            } else if (line.startsWith("TLS: Initial packet")) {
                attempt.serverReplied = true;
                attempt.meaningfulProgress("TLS", "сервер ответил, TLS handshake");
            } else if (line.contains("VERIFY OK: depth=0")) {
                attempt.serverReplied = true;
                attempt.meaningfulProgress("TLS_CERT_OK", "сертификат сервера проверен");
            } else if (line.contains("Peer Connection Initiated")) {
                attempt.serverReplied = true;
                attempt.meaningfulProgress("PEER_OK", "TLS завершён");
            } else if (line.contains("PUSH_REQUEST")) {
                attempt.meaningfulProgress("GET_CONFIG", "запрашиваю маршруты");
            } else if (line.contains("PUSH_REPLY")) {
                attempt.meaningfulProgress("PUSH_REPLY", "конфигурация получена");
            } else if (line.contains("Initialization Sequence Completed")) {
                attempt.meaningfulProgress("INIT_COMPLETE", "OpenVPN инициализирован");
            } else if (low.contains("auth_failed") || low.contains("tls error")
                    || low.contains("connection reset") || low.contains("certificate verify failed")
                    || low.contains("failed to negotiate cipher") || low.contains("options error")
                    || low.contains("tls key negotiation failed") || low.contains("inactivity timeout")) {
                attempt.meaningfulProgress("ENGINE_ERROR", line);
            }
        } catch (Throwable e) {
            AppLog.e("openvpn", "failed to render engine log item", e);
        }
    }

    private void applyVpnGateCompatibility(VpnProfile profile) {
        if (profile == null) return;

        // VPN Gate still has many old SoftEther/OpenVPN peers. OpenVPN 2.6/2.7 needs a
        // data-cipher fallback when talking to pre-NCP OpenVPN 2.3 peers. compat-mode 2.3.7
        // enables that fallback while avoiding the <=2.3.6 rule that also lowers TLS minimums.
        if (profile.mCompatMode <= 0) profile.mCompatMode = 20307;

        String cipher = safe(profile.mCipher).trim();
        String dataCiphers = safe(profile.mDataCiphers).trim();
        if (!cipher.isEmpty() && !containsCipher(dataCiphers, cipher)) {
            profile.mDataCiphers = dataCiphers.isEmpty() ? cipher : dataCiphers + ":" + cipher;
            dataCiphers = profile.mDataCiphers;
        }

        // BF-CBC is not used by the relays in the current log, but older cached VPN Gate
        // profiles can still contain it. Only enable OpenSSL's legacy provider when needed.
        String upperCipher = cipher.toUpperCase(java.util.Locale.US);
        String upperData = dataCiphers.toUpperCase(java.util.Locale.US);
        if ("BF-CBC".equals(upperCipher) || upperData.contains("BF-CBC")) {
            profile.mUseLegacyProvider = true;
        }

        // Require a TLS server certificate rather than accepting a generic certificate role.
        profile.mExpectTLSCert = true;
        AppLog.i("tunnel", "profile compatibility compatMode=" + profile.mCompatMode
                + " cipher=" + safe(profile.mCipher)
                + " dataCiphers=" + safe(profile.mDataCiphers)
                + " remoteCertTls=" + profile.mExpectTLSCert);
    }

    private static boolean containsCipher(String list, String cipher) {
        if (list == null || list.isEmpty() || cipher == null || cipher.isEmpty()) return false;
        for (String item : list.split(":")) if (cipher.equalsIgnoreCase(item.trim())) return true;
        return false;
    }

    private boolean hasUsableUnderlyingNetwork() {
        if (connectivity == null) return true;
        try {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        } catch (Throwable t) {
            AppLog.e("tunnel", "underlying network check failed", t);
            return true;
        }
    }

    private void waitForUnderlyingNetwork(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(300L, timeoutMs);
        do {
            if (hasUsableUnderlyingNetwork()) {
                sleepQuiet(180L);
                AppLog.i("tunnel", "underlying network ready after VPN teardown");
                return;
            }
            sleepQuiet(120L);
        } while (SystemClock.elapsedRealtime() < deadline);
        AppLog.w("tunnel", "underlying network settle timeout; starting next profile anyway");
        sleepQuiet(180L);
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private boolean waitForAndroidVpnTransport(long timeoutMs) {
        if (connectivity == null) return true;
        long deadline = SystemClock.elapsedRealtime() + Math.max(500L, timeoutMs);
        do {
            try {
                Network active = connectivity.getActiveNetwork();
                NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    AppLog.i("tunnel", "Android TRANSPORT_VPN verified");
                    return true;
                }
            } catch (Throwable t) {
                AppLog.e("tunnel", "VPN transport verification failed", t);
                return true;
            }
            try { Thread.sleep(120L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        } while (SystemClock.elapsedRealtime() < deadline);
        return false;
    }

    private boolean validateEngineInstall() {
        boolean serviceOk = false;
        boolean nativeOk = false;
        try {
            context.getPackageManager().getServiceInfo(new ComponentName(context, OpenVPNService.class), PackageManager.GET_META_DATA);
            serviceOk = true;
        } catch (Throwable t) {
            AppLog.e("engine", "OpenVPNService missing from merged manifest", t);
        }

        try {
            String nativeDir = context.getApplicationInfo().nativeLibraryDir;
            File exec = new File(nativeDir, "libovpnexec.so");
            nativeOk = exec.isFile() && exec.length() > 0;
            AppLog.i("engine", "nativeLibraryDir=" + nativeDir
                    + " libovpnexec=" + exec.exists() + " bytes=" + (exec.exists() ? exec.length() : 0)
                    + " abis=" + Arrays.toString(Build.SUPPORTED_ABIS));
        } catch (Throwable t) {
            AppLog.e("engine", "native OpenVPN engine check failed", t);
        }
        AppLog.i("engine", "self-check service=" + serviceOk + " native=" + nativeOk);
        return serviceOk && nativeOk;
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(
                    OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
                    "Yinglong VPN", NotificationManager.IMPORTANCE_MIN));
            nm.createNotificationChannel(new NotificationChannel(
                    OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
                    "Yinglong VPN status", NotificationManager.IMPORTANCE_LOW));
            nm.createNotificationChannel(new NotificationChannel(
                    OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
                    "Yinglong VPN requests", NotificationManager.IMPORTANCE_HIGH));
        } catch (Throwable e) {
            AppLog.e("tunnel", "notification channel setup failed", e);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
