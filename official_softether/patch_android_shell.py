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
            private set
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

print("Android VpnService shell patched for official native core")
