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
import vn.unlimit.softether.model.AuthMethod
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
    @Volatile private var failureStage = ""
    @Volatile private var healthy = false

    private class Attempt(
        val relay: Relay,
        val port: Int,
        val authLabel: String,
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

    private data class AuthVariant(
        val label: String,
        val password: String,
        val method: AuthMethod
    )

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
        // VPN Gate's SoftEther hub is VPNGATE/user vpn.  In the wild two client
        // conventions exist: anonymous login (the actual hub account type) and
        // vpn/vpn password login.  Try both on the SAME endpoint before throwing
        // a good TLS listener away.
        // VPN Gate convention: username vpn / password vpn.
        // Give one auth attempt the full budget; if it fails, the session manager
        // falls back to the relay's official OpenVPN profile.
        val variants = arrayOf(
            AuthVariant("PASSWORD", "vpn", AuthMethod.PASSWORD),
            AuthVariant("ANONYMOUS", "", AuthMethod.ANONYMOUS)
        )
        // ConnectionController inside the SoftEther module already retries three times.
        // The old wrapper killed the service at 18s, before the native timeout/retry
        // path could finish. Give the module enough wall-clock time for a real result.
        val perVariantTimeout = timeoutMs
            .coerceAtLeast(25_000L)
            .coerceAtMost(35_000L)

        var lastReason = ""
        for ((index, variant) in variants.withIndex()) {
            AppLog.i(
                "se-auth",
                "auth attempt ${index + 1}/${variants.size} mode=${variant.label} " +
                    "hub=VPNGATE user=vpn relay=${relay.ip} port=$port"
            )

            if (connectVariant(relay, port, perVariantTimeout, variant, progress)) {
                return true
            }

            lastReason = failure
            val failedStage = failureStage
            val authRelated = failedStage.equals("AUTH", ignoreCase = true) ||
                failedStage.equals("AUTHENTICATING", ignoreCase = true) ||
                lastReason.contains("authentication failed", ignoreCase = true)

            if (index + 1 < variants.size && authRelated) {
                AppLog.w(
                    "se-auth",
                    "mode=${variant.label} failed at auth; retrying same endpoint with " +
                        variants[index + 1].label + " reason=$lastReason"
                )
                progress?.onProgress(
                    "AUTH_FALLBACK",
                    "${variant.label} не ответил • пробую ${variants[index + 1].label}"
                )
                continue
            }

            if (!authRelated) {
                AppLog.w(
                    "se-auth",
                    "failure is not auth-related; no credential fallback reason=$lastReason"
                )
            }
            break
        }

        if (lastReason.isNotBlank()) failure = lastReason
        return false
    }

    private fun connectVariant(
        relay: Relay,
        port: Int,
        timeoutMs: Long,
        variant: AuthVariant,
        progress: Progress?
    ): Boolean {
        failure = ""
        failureStage = ""
        stopInternal(900L)

        val attempt = Attempt(relay, port, variant.label, progress)
        currentAttempt = attempt

        val config = ConnectionConfig(
            serverHost = relay.ip,
            serverPort = port,
            username = "vpn",
            password = variant.password,
            virtualHub = "VPNGATE",
            authMethod = variant.method,
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
            // Per-native-attempt timeout. Three attempts plus retry delays fit
            // inside the outer 55s attempt budget.
            connectTimeoutMs = 8_000,
            country = relay.countryShort ?: "",
            clientProductName = "Yinglong",
            clientVersion = "1.0.3",
            clientBuild = 37,
            fullDuplex = true
        )

        val intent = Intent(appContext, SoftEtherVpnService::class.java).apply {
            action = SoftEtherVpnService.ACTION_CONNECT
            putExtra(SoftEtherVpnService.EXTRA_CONFIG, config)
        }

        val passwordState = if (variant.password.isEmpty()) "<empty>" else "<set>"
        AppLog.i(
            "se-tunnel",
            "START SoftEther relay=${relay.ip} port=$port auth=${variant.label} " +
                "hub=VPNGATE password=$passwordState"
        )
        attempt.stage(
            "ENGINE_START",
            "SoftEther TLS tcp:$port • VPNGATE/${variant.label}"
        )

        try {
            ContextCompat.startForegroundService(appContext, intent)
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message ?: ""}"
            AppLog.e("se-tunnel", "unable to start SoftEther service", t)
            currentAttempt = null
            return false
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(5_000L)
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
            failureStage = attempt.lastStage
            failure = "SoftEther timeout at ${attempt.lastStage} auth=${variant.label}"
            AppLog.w("se-tunnel", "$failure relay=${relay.ip} port=$port")
            attempt.stage("TIMEOUT", failure)
            stopInternal(1400L)
            return false
        }

        if (!attempt.success) {
            if (failureStage.isBlank()) failureStage = attempt.lastStage
            if (failure.isBlank()) {
                failure = "SoftEther failed at ${attempt.lastStage} auth=${variant.label}"
            }
            AppLog.w(
                "se-tunnel",
                "connect failed relay=${relay.ip} port=$port auth=${variant.label} reason=$failure"
            )
            stopInternal(1400L)
            return false
        }

        AppLog.i(
            "se-tunnel",
            "CONNECTED relay=${relay.ip} port=$port auth=${variant.label}"
        )
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
            SoftEtherVpnService.STATE_AUTHENTICATING -> attempt?.stage("AUTH", "VPNGATE • vpn • ${attempt.authLabel}")
            SoftEtherVpnService.STATE_SESSION_SETUP -> attempt?.stage("SESSION", "сессия + DHCP")
            SoftEtherVpnService.STATE_CONNECTED -> {
                connected = true
                lostLatch = CountDownLatch(1)
                failure = ""
                failureStage = ""
                attempt?.stage("CONNECTED", assignedIp.ifBlank { attempt.relay.ip })
                if (attempt != null) {
                    attempt.success = true
                    attempt.done.countDown()
                }
            }
            SoftEtherVpnService.STATE_ERROR -> {
                val failedAt = attempt?.lastStage ?: "unknown"
                failureStage = failedAt
                val nativeReason = SoftEtherVpnService.lastErrorMessage
                failure = if (nativeReason.isBlank()) {
                    "SoftEther ERROR after $failedAt"
                } else {
                    "SoftEther $nativeReason after $failedAt"
                }
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
                    if (failureStage.isBlank()) failureStage = attempt.lastStage
                    if (attempt.lastStage == "TIMEOUT" && failure.isNotBlank()) {
                        AppLog.i("se-tunnel", "disconnect after timeout; preserving reason=$failure")
                        attempt.stage("DISCONNECTED", failure)
                    } else {
                        failure = "SoftEther disconnected after ${attempt.lastStage} auth=${attempt.authLabel}"
                        attempt.stage("DISCONNECTED", failure)
                    }
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
