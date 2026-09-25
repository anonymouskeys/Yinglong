package org.yinglong.client.net;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;

import org.yinglong.client.catalog.Relay;
import org.yinglong.client.diag.AppLog;

import java.io.IOException;
import java.io.StringReader;
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
 * Thin application-facing wrapper around the embedded ics-openvpn engine.
 *
 * One instance owns state/log listeners for the whole process. A connect attempt
 * blocks only the session worker thread; the Android UI thread is never blocked.
 */
public final class OpenVpnTunnel implements VpnStatus.StateListener, VpnStatus.LogListener {
    private static volatile OpenVpnTunnel instance;

    private final Context context;
    private final Object lock = new Object();
    private volatile Attempt currentAttempt;
    private volatile CountDownLatch connectionLost = new CountDownLatch(0);
    private volatile CountDownLatch stoppedLatch;
    private volatile boolean connected;
    private volatile Relay activeRelay;

    private static final class Attempt {
        final Relay relay;
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean success;
        volatile String failure = "not connected";

        Attempt(Relay relay) { this.relay = relay; }
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
        createNotificationChannels();
        VpnStatus.addStateListener(this);
        VpnStatus.addLogListener(this);
        AppLog.i("tunnel", "ics-openvpn backend initialized");
    }

    public boolean isPermissionGranted() {
        return VpnService.prepare(context) == null;
    }

    public boolean isConnected() { return connected; }

    public Relay activeRelay() { return activeRelay; }

    /**
     * Parse a VPN Gate profile, force its direct IP, and wait for a real CONNECTED state.
     * Returns false on auth/config/network failure or timeout so the caller can fail over.
     */
    public boolean connectBlocking(Relay relay, long timeoutMs) throws Exception {
        if (relay == null) throw new IllegalArgumentException("relay == null");
        if (!isPermissionGranted()) throw new IllegalStateException("Android VPN permission is not granted");

        disconnectBlocking(2500L);

        String config = OpenVpnProfileUtil.configWithDirectIp(relay);
        if (config == null || config.trim().isEmpty()) throw new IOException("empty OpenVPN profile");

        VpnProfile profile;
        try {
            ConfigParser parser = new ConfigParser();
            parser.parseConfig(new StringReader(config));
            profile = parser.convertProfile();
        } catch (Exception e) {
            AppLog.e("tunnel", "profile parse failed for " + relay.ip, e);
            throw e;
        }

        profile.mName = "Yinglong " + safe(relay.countryShort) + " " + relay.ip;
        // Block traffic for address families the tunnel cannot route. We deliberately do not
        // enable persist-tun yet: clean teardown between relay attempts makes failover reliable.
        profile.mBlockUnusedAddressFamilies = true;
        profile.mPersistTun = false;

        int check = profile.checkProfile(context);
        if (check != R.string.no_error_found) {
            String message;
            try { message = context.getString(check); }
            catch (Exception ignored) { message = "profile validation failed: resource=" + check; }
            throw new IOException(message);
        }

        ProfileManager.setTemporaryProfile(context, profile);

        Attempt attempt = new Attempt(relay);
        synchronized (lock) {
            currentAttempt = attempt;
            connected = false;
            activeRelay = null;
        }

        AppLog.i("tunnel", "starting OpenVPN relay=" + relay.ip + " country=" + safe(relay.countryShort));
        VPNLaunchHelper.startOpenVpn(profile, context, "Yinglong", true);

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
            AppLog.w("tunnel", attempt.failure + " relay=" + relay.ip);
            disconnectBlocking(2500L);
            return false;
        }

        if (!attempt.success) {
            AppLog.w("tunnel", "connect failed relay=" + relay.ip + " reason=" + attempt.failure);
            disconnectBlocking(2500L);
            return false;
        }

        AppLog.i("tunnel", "CONNECTED relay=" + relay.ip);
        return true;
    }

    /** Wait until a previously connected tunnel reaches a terminal state or stop() is called. */
    public void awaitConnectionLoss() throws InterruptedException {
        CountDownLatch latch = connectionLost;
        if (connected && latch != null) latch.await();
    }

    public void disconnect() {
        disconnectBlocking(2500L);
    }

    private void disconnectBlocking(long waitMs) {
        Attempt attempt = currentAttempt;
        if (attempt != null && attempt.done.getCount() > 0) {
            attempt.failure = "stopped";
            attempt.done.countDown();
        }

        CountDownLatch lost = connectionLost;
        if (lost != null) lost.countDown();
        connected = false;
        activeRelay = null;

        CountDownLatch stop = new CountDownLatch(1);
        stoppedLatch = stop;

        Intent intent = new Intent(context, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IOpenVPNServiceInternal service = IOpenVPNServiceInternal.Stub.asInterface(binder);
                    if (service != null) service.stopVPN(false);
                    AppLog.i("tunnel", "stopVPN sent");
                } catch (Exception e) {
                    AppLog.e("tunnel", "stopVPN failed", e);
                } finally {
                    try { context.unbindService(this); } catch (Exception ignored) {}
                }
            }

            @Override public void onServiceDisconnected(ComponentName name) { stop.countDown(); }
        };

        boolean bound = false;
        try { bound = context.bindService(intent, connection, 0); }
        catch (Exception e) { AppLog.e("tunnel", "bind OpenVPNService for stop failed", e); }

        if (!bound) {
            stop.countDown();
            try { context.unbindService(connection); } catch (Exception ignored) {}
        }

        if (waitMs > 0) {
            try { stop.await(waitMs, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (stoppedLatch == stop) stoppedLatch = null;
        currentAttempt = null;
    }

    @Override public void updateState(String state, String logmessage, int localizedResId,
                                      ConnectionStatus level, Intent intent) {
        String s = state == null ? "?" : state;
        String msg = logmessage == null ? "" : logmessage.replace('\n', ' ');
        AppLog.i("ovpn-state", s + " level=" + String.valueOf(level) + (msg.isEmpty() ? "" : " msg=" + msg));

        Attempt attempt = currentAttempt;
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            connected = true;
            if (attempt != null) {
                activeRelay = attempt.relay;
                connectionLost = new CountDownLatch(1);
                attempt.success = true;
                attempt.failure = "";
                attempt.done.countDown();
            }
            return;
        }

        boolean terminal = level == ConnectionStatus.LEVEL_AUTH_FAILED
                || level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
                || level == ConnectionStatus.LEVEL_NOTCONNECTED;

        if (terminal) {
            boolean wasConnected = connected;
            connected = false;
            if (attempt != null && attempt.done.getCount() > 0) {
                attempt.success = false;
                attempt.failure = s + (msg.isEmpty() ? "" : ": " + msg);
                attempt.done.countDown();
            }
            if (wasConnected) {
                AppLog.w("tunnel", "connected tunnel ended: " + s + " " + msg);
                CountDownLatch lost = connectionLost;
                if (lost != null) lost.countDown();
            }
            CountDownLatch stop = stoppedLatch;
            if (stop != null) stop.countDown();
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
        } catch (Exception e) {
            AppLog.e("openvpn", "failed to render engine log item", e);
        }
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
        } catch (Exception e) {
            AppLog.e("tunnel", "notification channel setup failed", e);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
