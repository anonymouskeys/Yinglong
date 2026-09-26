#!/usr/bin/env python3
# Yinglong v0.9.4 SoftEther Android integration patch.
# Keep the VPN Gate-specific PACK serialization from the pinned module intact.
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "third_party/SoftEtherClient")
if not root.exists():
    raise SystemExit(f"SoftEther module not found: {root}")

def load(rel):
    p = root / rel
    if not p.exists():
        raise SystemExit(f"missing pinned module file: {p}")
    return p, p.read_text()

def once(s, old, new, label):
    n = s.count(old)
    if n != 1:
        raise SystemExit(f"{label}: expected 1 match, got {n}")
    return s.replace(old, new, 1)

def exact_n(s, old, new, expected, label):
    n = s.count(old)
    if n != expected:
        raise SystemExit(f"{label}: expected {expected} matches, got {n}")
    return s.replace(old, new)

# Controller owns nativeHandle. Use that handle directly for timeout/duplex.
p, s = load("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt")
s = once(
    s,
    "private const val MAX_RECONNECT_ATTEMPTS = 3",
    "private const val MAX_RECONNECT_ATTEMPTS = 1",
    "controller retry count",
)
s = exact_n(
    s,
    "client.setTimeout(config.connectTimeoutMs)",
    "client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_TIMEOUT, config.connectTimeoutMs.toLong())",
    2,
    "timeout native-handle wiring",
)
s = once(
    s,
    "client.setHalfConnection(!fullDuplex)",
    "client.nativeSetHalfConnection(nativeHandle, !fullDuplex)",
    "initial duplex native-handle wiring",
)
s = once(
    s,
    "client.setHalfConnection(!reconnectFullDuplex)",
    "client.nativeSetHalfConnection(nativeHandle, !reconnectFullDuplex)",
    "reconnect duplex native-handle wiring",
)
p.write_text(s)
print("patched ConnectionController.kt")

# Helper FD lookup must honor the controller-owned external handle.
p, s = load("src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt")
s = once(
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
    "socket-FD handle ownership",
)
p.write_text(s)
print("patched SoftEtherClient.kt")

# Native core: make AUTO distinct from explicit ANONYMOUS, complete TLS writes,
# and map no login response to timeout. Do not rewrite VPN Gate PACK quirks.
p, s = load("src/main/cpp/softether-core/src/proto/softether_protocol.c")

s = once(
    s,
    '''    conn->ssl_ctx = NULL;
    conn->ssl = NULL;
''',
    '''    conn->ssl_ctx = NULL;
    conn->ssl = NULL;
    conn->forced_auth_type = -1;  // -1=AUTO, 0=ANONYMOUS, 1=PASSWORD, 2=PLAIN
''',
    "native auth AUTO sentinel",
)

old_auth = '''    // Decide auth type: use forced_auth_type if set, otherwise auto-detect
    // NOTE: CLIENT_AUTHTYPE_ANONYMOUS == 0 doubles as the "auto-detect"
    // sentinel, so a non-zero forced value selects that type explicitly and
    // 0 (the default) falls through to auto-detection below.
    int auth_type;
    uint8_t secure_password[SHA1_SIZE];
    memset(secure_password, 0, sizeof(secure_password));

    if (conn->forced_auth_type == CLIENT_AUTHTYPE_PLAIN_PASSWORD) {
        // Plain password auth (used for RADIUS): send password in plaintext
        auth_type = CLIENT_AUTHTYPE_PLAIN_PASSWORD;
        LOGD("Using PLAIN_PASSWORD auth (RADIUS mode)");
    } else if (conn->forced_auth_type == CLIENT_AUTHTYPE_PASSWORD) {
        auth_type = CLIENT_AUTHTYPE_PASSWORD;
        LOGD("Using PASSWORD auth (forced)");
    } else {
        // Auto-detect (default 0): hashed password if non-empty, else anonymous
        auth_type = (strlen(password) > 0) ? CLIENT_AUTHTYPE_PASSWORD : CLIENT_AUTHTYPE_ANONYMOUS;
    }
'''
new_auth = '''    // Authentication selection. Keep AUTO distinct from ANONYMOUS.
    // -1=AUTO, 0=ANONYMOUS, 1=PASSWORD, 2=PLAIN_PASSWORD.
    int auth_type;
    uint8_t secure_password[SHA1_SIZE];
    memset(secure_password, 0, sizeof(secure_password));

    if (conn->forced_auth_type == CLIENT_AUTHTYPE_ANONYMOUS) {
        auth_type = CLIENT_AUTHTYPE_ANONYMOUS;
        LOGD("Using ANONYMOUS auth (forced)");
    } else if (conn->forced_auth_type == CLIENT_AUTHTYPE_PLAIN_PASSWORD) {
        auth_type = CLIENT_AUTHTYPE_PLAIN_PASSWORD;
        LOGD("Using PLAIN_PASSWORD auth (RADIUS mode)");
    } else if (conn->forced_auth_type == CLIENT_AUTHTYPE_PASSWORD) {
        auth_type = CLIENT_AUTHTYPE_PASSWORD;
        LOGD("Using PASSWORD auth (forced)");
    } else {
        auth_type = (strlen(password) > 0)
            ? CLIENT_AUTHTYPE_PASSWORD
            : CLIENT_AUTHTYPE_ANONYMOUS;
        LOGD("Using AUTO auth -> %s",
             auth_type == CLIENT_AUTHTYPE_PASSWORD ? "PASSWORD" : "ANONYMOUS");
    }
'''
s = once(s, old_auth, new_auth, "native auth selector")

anchor = '''// Read an HTTP response precisely: headers byte-by-byte until \\r\\n\\r\\n,
// then exactly Content-Length bytes for the body.
'''
helper = '''// Write a complete TLS control message. The HTTP Content-Length describes
// the entire PACK body, so a short SSL_write must be completed before reading.
static int ssl_write_all_control(ssl_context_t* ssl, const uint8_t* data, size_t len) {
    size_t off = 0;
    if (ssl == NULL || data == NULL) return -1;
    while (off < len) {
        int n = ssl_write(ssl, data + off, len - off);
        if (n <= 0) return -1;
        off += (size_t)n;
    }
    return (int)off;
}

''' + anchor
s = once(s, anchor, helper, "TLS write-all helper")

s = exact_n(
    s,
    "int sent = ssl_write((ssl_context_t*)conn->ssl, combined, (int)combined_len);",
    "int sent = ssl_write_all_control((ssl_context_t*)conn->ssl, combined, combined_len);",
    2,
    "primary control writes",
)
s = exact_n(
    s,
    "int write_ret = ssl_write(ssl_ctx, combined, (int)combined_len);",
    "int write_ret = ssl_write_all_control(ssl_ctx, combined, combined_len);",
    1,
    "additional watermark write",
)
s = exact_n(
    s,
    "int write_ret = ssl_write(ssl_ctx, auth_combined, (int)auth_combined_len);",
    "int write_ret = ssl_write_all_control(ssl_ctx, auth_combined, auth_combined_len);",
    1,
    "additional auth write",
)
s = once(
    s,
    '''    if (total <= 0) {
        LOGE("No response received for login PACK");
        return ERR_AUTHENTICATION;
    }''',
    '''    if (total <= 0) {
        LOGE("No response received for login PACK");
        return ERR_TIMEOUT;
    }''',
    "login no-response mapping",
)
p.write_text(s)
print("patched softether_protocol.c")

p, s = load("src/main/cpp/softether-core/include/softether_protocol.h")
s = once(
    s,
    "int forced_auth_type;  // 0=auto-detect, 1=hashed password, 2=plain password (RADIUS)",
    "int forced_auth_type;  // -1=auto, 0=anonymous, 1=hashed password, 2=plain password",
    "auth field documentation",
)
s = once(
    s,
    "// Set authentication type explicitly (use CLIENT_AUTHTYPE_* constants; 0=auto)",
    "// Set authentication type explicitly (CLIENT_AUTHTYPE_*); -1 means auto",
    "auth API documentation",
)
p.write_text(s)
print("patched softether_protocol.h")

# Surface exact controller error to Yinglong.
p, s = load("src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt")
s = once(
    s,
    '''        var currentAssignedIp: String = ""
            private set
        var currentTrafficSnapshot:''',
    '''        var currentAssignedIp: String = ""
            private set
        var lastErrorMessage: String = ""
            private set
        var currentTrafficSnapshot:''',
    "service lastErrorMessage property",
)
s = once(
    s,
    '''        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY''',
    '''        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastErrorMessage = ""
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY''',
    "clear service error",
)
s = once(
    s,
    '''                    onError = { error ->
                        Log.e(TAG, "VPN Error: $error")''',
    '''                    onError = { error ->
                        lastErrorMessage = error
                        Log.e(TAG, "VPN Error: $error")''',
    "capture service error",
)
p.write_text(s)
print("patched SoftEtherVpnService.kt")

proto = (root / "src/main/cpp/softether-core/src/proto/softether_protocol.c").read_text()
controller = (root / "src/main/java/vn/unlimit/softether/controller/ConnectionController.kt").read_text()
client = (root / "src/main/java/vn/unlimit/softether/client/SoftEtherClient.kt").read_text()

checks = {
    "real timeout handle": "client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_TIMEOUT" in controller,
    "real duplex handle": "client.nativeSetHalfConnection(nativeHandle, !fullDuplex)" in controller,
    "single outer retry": "MAX_RECONNECT_ATTEMPTS = 1" in controller,
    "external FD handle": "externalHandle.takeIf { it != 0L } ?: nativeHandle" in client,
    "explicit anonymous": "forced_auth_type = -1" in proto and "Using ANONYMOUS auth (forced)" in proto,
    "write-all": "ssl_write_all_control" in proto,
    "VPNGate ClientHostName preserved": '"ClientHostName"' in proto,
    "VPNGate ServerHostName preserved": '"ServerHostName"' in proto,
    "VPNGate endian helper preserved": "softether_endian32" in proto,
}
bad = [name for name, ok in checks.items() if not ok]
if bad:
    raise SystemExit("SoftEther v0.9.4 verification failed: " + ", ".join(bad))

print("SoftEther v0.9.4 integration/core patch: OK")
