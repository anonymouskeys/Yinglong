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
        volatile String failure = "not connected";

        Attempt(Relay relay, ProgressListener progress) {
            this.relay = relay;
            this.progress = progress;
        }

        void stage(String stage, String message) {
            if (progress == null) return;
            try { progress.onStage(stage, message == null ? "" : message); }
            catch (Throwable ignored) {}
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

        boolean signaled;
        try {
            signaled = attempt.done.await(Math.max(5000L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            attempt.failure = "interrupted";
            signaled = false;
        }

        if (!signaled) {
            attempt.failure = "connect timeout after " + timeoutMs + " ms";
            lastFailure = attempt.failure;
            AppLog.w("tunnel", attempt.failure + " relay=" + relay.ip + " sawProgress=" + attempt.sawProgress);
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

    @Override public void newLog(LogItem logItem) {
        if (logItem == null) return;
        try {
            String line = logItem.getString(context);
            if (line != null && !line.trim().isEmpty()) AppLog.i("openvpn", line);
        } catch (Throwable e) {
            AppLog.e("openvpn", "failed to render engine log item", e);
        }
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
