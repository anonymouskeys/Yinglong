package org.yinglong.client.net

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.yinglong.client.MainActivity
import org.yinglong.client.catalog.Relay
import org.yinglong.client.diag.AppLog
import vn.unlimit.softether.SoftEtherVpnService
import vn.unlimit.softether.model.ConnectionConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SoftEtherTunnel private constructor(context: Context) : SoftEtherVpnService.StateListener {
    interface Progress {
        fun onProgress(stage: String, message: String)
    }

    private val appContext = context.applicationContext

    @Volatile private var currentAttempt: Attempt? = null
    @Volatile private var connected = false
    @Volatile private var lostLatch = CountDownLatch(0)
    @Volatile private var failure = ""
    @Volatile private var healthy = false

    private class Attempt(
        val relay: Relay,
        val port: Int,
        val progress: Progress?
    ) {
        val done = CountDownLatch(1)
        @Volatile var success = false
        @Volatile var sawStart = false
        @Volatile var lastStage = "START"

        fun stage(stage: String, message: String) {
            lastStage = stage
            if (stage != "DISCONNECTED") sawStart = true
            progress?.onProgress(stage, message)
        }
    }

    init {
        healthy = validateInstall()
        SoftEtherVpnService.notificationTargetActivity = MainActivity::class.java
        SoftEtherVpnService.addStateListener(this)
        AppLog.i("se-tunnel", "SoftEther backend initialized healthy=$healthy")
    }

    fun engineHealthy(): Boolean = healthy

    fun isPermissionGranted(): Boolean {
        return try {
            VpnService.prepare(appContext) == null
        } catch (t: Throwable) {
            AppLog.e("se-tunnel", "VpnService.prepare check failed", t)
            false
        }
    }

    fun lastFailure(): String = failure

    fun connectBlocking(relay: Relay, port: Int, timeoutMs: Long, progress: Progress?): Boolean {
        failure = ""
        stopInternal(900L)

        val attempt = Attempt(relay, port, progress)
        currentAttempt = attempt

        val config = ConnectionConfig(
            serverHost = relay.ip,
            serverPort = port,
            username = "vpn",
            password = "vpn",
            virtualHub = "vpngate",
            sessionName = "Yinglong ${relay.countryShort} ${relay.ip}",
            localAddress = "10.21.0.2",
            prefixLength = 19,
            dnsServer = "8.8.8.8",
            secondaryDnsServer = "8.8.4.4",
            mtu = 1500,
            useTcp = true,
            useUdp = false,
            udpPort = 0,
            udpOnly = false,
            connectTimeoutMs = 7000,
            country = relay.countryShort ?: "",
            clientProductName = "Yinglong",
            clientVersion = "0.3.9",
            clientBuild = 13
        )

        val intent = Intent(appContext, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        }

        AppLog.i("se-tunnel", "START SoftEther relay=${relay.ip} port=$port")
        attempt.stage("ENGINE_START", "SoftEther TLS tcp:$port")

        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            AppLog.e("se-tunnel", "unable to start SoftEther service", t)
            currentAttempt = null
            return false
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(5000L)
        var signaled = false
        try {
            while (!signaled && SystemClock.elapsedRealtime() < deadline) {
                signaled = attempt.done.await(250L, TimeUnit.MILLISECONDS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = "interrupted"
        }

        if (!signaled) {
            failure = "SoftEther timeout at ${attempt.lastStage}"
            AppLog.w("se-tunnel", "$failure relay=${relay.ip} port=$port")
            attempt.stage("TIMEOUT", failure)
            stopInternal(1400L)
            return false
        }

        if (!attempt.success) {
            if (failure.isBlank()) failure = "SoftEther failed at ${attempt.lastStage}"
            AppLog.w("se-tunnel", "connect failed relay=${relay.ip} port=$port reason=$failure")
            stopInternal(1400L)
            return false
        }

        AppLog.i("se-tunnel", "CONNECTED relay=${relay.ip} port=$port")
        return true
    }

    fun awaitConnectionLoss() {
        val latch = lostLatch
        if (connected && latch.count > 0) {
            try {
                latch.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun disconnect() {
        connected = false
        lostLatch.countDown()
        stopInternal(1400L)
    }

    override fun onSoftEtherStateChanged(state: String, assignedIp: String) {
        AppLog.i("se-state", "$state" + if (assignedIp.isNotBlank()) " ip=$assignedIp" else "")
        val attempt = currentAttempt

        when (state) {
            SoftEtherVpnService.STATE_CONNECTING -> attempt?.stage("CONNECTING", "TCP соединение")
            SoftEtherVpnService.STATE_TLS_HANDSHAKE -> attempt?.stage("TLS", "SoftEther TLS handshake")
            SoftEtherVpnService.STATE_PROTOCOL_HANDSHAKE -> attempt?.stage("SOFTETHER", "SoftEther protocol handshake")
            SoftEtherVpnService.STATE_AUTHENTICATING -> attempt?.stage("AUTH", "VPN Gate vpn/vpn")
            SoftEtherVpnService.STATE_SESSION_SETUP -> attempt?.stage("SESSION", "сессия + DHCP")
            SoftEtherVpnService.STATE_CONNECTED -> {
                connected = true
                lostLatch = CountDownLatch(1)
                failure = ""
                attempt?.stage("CONNECTED", assignedIp.ifBlank { attempt.relay.ip })
                if (attempt != null) {
                    attempt.success = true
                    attempt.done.countDown()
                }
            }
            SoftEtherVpnService.STATE_ERROR -> {
                failure = "SoftEther ERROR after ${attempt?.lastStage ?: "unknown"}"
                attempt?.stage("ERROR", failure)
                if (attempt != null && attempt.done.count > 0) {
                    attempt.success = false
                    attempt.done.countDown()
                }
                if (connected) {
                    connected = false
                    lostLatch.countDown()
                }
            }
            SoftEtherVpnService.STATE_DISCONNECTED -> {
                if (attempt != null && attempt.sawStart && attempt.done.count > 0) {
                    failure = "SoftEther disconnected after ${attempt.lastStage}"
                    attempt.stage("DISCONNECTED", failure)
                    attempt.success = false
                    attempt.done.countDown()
                }
                if (connected) {
                    connected = false
                    lostLatch.countDown()
                }
            }
        }
    }

    private fun stopInternal(waitMs: Long) {
        val state = SoftEtherVpnService.currentState
        if (state != SoftEtherVpnService.STATE_DISCONNECTED) {
            try {
                val intent = Intent(appContext, SoftEtherVpnService::class.java).apply {
                    action = SoftEtherVpnService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(appContext, intent)
            } catch (t: Throwable) {
                AppLog.e("se-tunnel", "SoftEther stop request failed", t)
            }

            val deadline = SystemClock.elapsedRealtime() + waitMs.coerceAtLeast(0L)
            while (waitMs > 0L
                && SoftEtherVpnService.currentState != SoftEtherVpnService.STATE_DISCONNECTED
                && SystemClock.elapsedRealtime() < deadline
            ) {
                try {
                    Thread.sleep(80L)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        currentAttempt = null
    }

    private fun validateInstall(): Boolean {
        var serviceOk = false
        var nativeOk = false
        try {
            Class.forName("vn.unlimit.softether.SoftEtherVpnService")
            serviceOk = true
        } catch (t: Throwable) {
            AppLog.e("se-engine", "SoftEtherVpnService class missing", t)
        }

        try {
            val dir = appContext.applicationInfo.nativeLibraryDir
            val so = File(dir, "libsoftether.so")
            nativeOk = so.isFile && so.length() > 0L
            AppLog.i(
                "se-engine",
                "nativeLibraryDir=$dir libsoftether=${so.exists()} bytes=${if (so.exists()) so.length() else 0} " +
                    "abis=${Build.SUPPORTED_ABIS.contentToString()}"
            )
        } catch (t: Throwable) {
            AppLog.e("se-engine", "SoftEther native library check failed", t)
        }

        AppLog.i("se-engine", "self-check service=$serviceOk native=$nativeOk")
        return serviceOk && nativeOk
    }

    companion object {
        @Volatile private var instance: SoftEtherTunnel? = null

        @JvmStatic
        fun get(context: Context): SoftEtherTunnel {
            return instance ?: synchronized(this) {
                instance ?: SoftEtherTunnel(context).also { instance = it }
            }
        }
    }
}
