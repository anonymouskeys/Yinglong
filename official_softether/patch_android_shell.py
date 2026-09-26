#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1])

def load(rel):
    p = root / rel
    if not p.exists():
        raise SystemExit(f"missing Android shell file: {p}")
    return p, p.read_text()

def once(s, old, new, label):
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 match, got {n}")
    return s.replace(old, new, 1)

def exact_n(s, old, new, n, label):
    got=s.count(old)
    if got != n:
        raise SystemExit(f"{label}: expected {n} matches, got {got}")
    return s.replace(old,new)

p,s=load("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt")
s=s.replace("private const val MAX_RECONNECT_ATTEMPTS = 3",
            "private const val MAX_RECONNECT_ATTEMPTS = 1")
s=exact_n(
    s,
    "client.setTimeout(config.connectTimeoutMs)",
    "client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_TIMEOUT, config.connectTimeoutMs.toLong())",
    2,
    "timeout handle"
)
s=once(s,
       "client.setHalfConnection(!fullDuplex)",
       "client.nativeSetHalfConnection(nativeHandle, !fullDuplex)",
       "initial duplex handle")
s=once(s,
       "client.setHalfConnection(!reconnectFullDuplex)",
       "client.nativeSetHalfConnection(nativeHandle, !reconnectFullDuplex)",
       "reconnect duplex handle")
p.write_text(s)

p,s=load("src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt")
s=once(
    s,
    '''    fun getAllSocketFds(): IntArray? {
        if (nativeHandle == 0L) return null
        return nativeGetAllSocketFds(nativeHandle)
    }''',
    '''    fun getAllSocketFds(): IntArray? {
        val handle = externalHandle.takeIf { it != 0L } ?: nativeHandle
        if (handle == 0L) return null
        return nativeGetAllSocketFds(handle)
    }''',
    "external handle fd list"
)
p.write_text(s)

p,s=load("src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt")
s=once(
    s,
    '''        var currentAssignedIp: String = ""
            private set
        var currentTrafficSnapshot:''',
    '''        var currentAssignedIp: String = ""
            private set
        var lastErrorMessage: String = ""
            internal set
        var currentTrafficSnapshot:''',
    "lastErrorMessage property"
)
s=once(
    s,
    '''        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY''',
    '''        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastErrorMessage = ""
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY''',
    "clear lastErrorMessage"
)
s=once(
    s,
    '''                    onError = { error ->
                        Log.e(TAG, "VPN Error: $error")''',
    '''                    onError = { error ->
                        lastErrorMessage = error
                        Log.e(TAG, "VPN Error: $error")''',
    "capture lastErrorMessage"
)
p.write_text(s)

# Preserve the mapped native error before ERROR state listeners are notified.
# Without this, Yinglong sees STATE_ERROR first and loses the useful native
# reason (for example ERR_TIMEOUT vs ERR_TCP_CONNECT).
p,s=load("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt")
native_error_old = """        if (result != 0) {
            currentState = ConnectionState.ERROR
            throw Exception("Connection failed: ${SoftEtherError.getErrorString(result)} ($result)")
        }
"""
native_error_new = """        if (result != 0) {
            val nativeFailure = "Connection failed: ${SoftEtherError.getErrorString(result)} ($result)"
            SoftEtherVpnService.lastErrorMessage = nativeFailure
            currentState = ConnectionState.ERROR
            throw Exception(nativeFailure)
        }
"""
if native_error_new not in s:
    s=once(s,native_error_old,native_error_new,"native error before state")
p.write_text(s)


# Strict Android networking semantics for official Cedar.
p,s=load("src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt")

builder_anchor = """        val builder = Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu)
            .addAddress(config.localAddress, config.prefixLength)
            .addDnsServer(config.dnsServer)
"""
builder_new = """        val builder = Builder()
            .setSession(config.sessionName)
            .setMtu(config.mtu)
            .addAddress(config.localAddress, config.prefixLength)
            .addDnsServer(config.dnsServer)

        try {
            builder.addDisallowedApplication(packageName)
            Log.d(TAG, "VPN provider package excluded from its own tunnel: $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to exclude VPN provider package; native protect(fd) remains active", e)
        }
"""
if "VPN provider package excluded from its own tunnel" not in s:
    s=once(s,builder_anchor,builder_new,"exclude provider package")

v6_start = s.find("        // IPv6 tunnel: unique per-install ULA address")
v6_end = s.find("        // Exclude apps from VPN tunnel", v6_start)
if v6_start >= 0 and v6_end >= 0:
    v6_new = """        // IPv6 is enabled only when an explicit address is supplied.
        // Full desktop parity requires RA/NDP learned from the remote L2 segment.
        if (config.localAddressV6.isNotBlank()) {
            try {
                builder.addAddress(config.localAddressV6, config.prefixLengthV6)
                if (config.dnsServerV6.isNotBlank()) {
                    builder.addDnsServer(config.dnsServerV6)
                }
                config.routesV6.forEach { route ->
                    builder.addRoute(route.address, route.prefixLength)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Explicit IPv6 configuration rejected; continuing IPv4-only", e)
            }
        }

"""
    s = s[:v6_start] + v6_new + s[v6_end:]
elif "IPv6 is enabled only when an explicit address is supplied." not in s:
    raise SystemExit("IPv6 synthetic block not found")
p.write_text(s)

p,s=load("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt")
dhcp_old = """        } else {
            Log.w(TAG, "DHCP failed, falling back to hardcoded IP config")
            assignedLocalIp = config.localAddress
            vpnInterface = service.establishVpnInterface(config)
                ?: throw Exception("Failed to establish VPN interface")
        }
"""
dhcp_new = """        } else {
            throw Exception("SoftEther L2 session established, but DHCP returned no IPv4 lease")
        }
"""
if dhcp_new not in s:
    s=once(s,dhcp_old,dhcp_new,"strict DHCP")
p.write_text(s)


print("Android VpnService shell patched for official native core")
