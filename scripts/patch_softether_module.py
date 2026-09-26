#!/usr/bin/env python3
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

# Fix Controller -> native handle wiring.
p, s = load("src/main/java/vn/unlimit/softether/controller/ConnectionController.kt")
s = exact_n(
    s,
    "client.setTimeout(config.connectTimeoutMs)",
    "client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_TIMEOUT, config.connectTimeoutMs.toLong())",
    2,
    "timeout wiring"
)
s = once(s, "client.setHalfConnection(!fullDuplex)",
         "client.nativeSetHalfConnection(nativeHandle, false)",
         "initial full duplex")
s = once(s, "client.setHalfConnection(!reconnectFullDuplex)",
         "client.nativeSetHalfConnection(nativeHandle, false)",
         "reconnect full duplex")
needle = "client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_TIMEOUT, config.connectTimeoutMs.toLong())"
s = exact_n(s, needle,
            needle + "\n            client.nativeSetMaxConnection(nativeHandle, 1)",
            2, "baseline single connection")
p.write_text(s)
print("patched ConnectionController.kt")

# Fix helper FD lookup after the controller hands ownership through externalHandle.
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
    "socket FD handle"
)
p.write_text(s)
print("patched SoftEtherClient.kt")

# Bring the custom native login PACK closer to official ClientUploadAuth().
p, s = load("src/main/cpp/softether-core/src/proto/softether_protocol.c")

s = once(
    s,
    '''// SoftEther NODE_INFO stores int fields in big-endian byte order via Endian32().
// On little-endian ARM, Endian32 = byte-swap. We need the same for NODE_INFO fields.
static uint32_t softether_endian32(uint32_t x) {
    return __builtin_bswap32(x);
}
''',
    '''// Official OutRpcNodeInfo() applies LittleEndian32() before PackAddInt().
// Android ABIs are little-endian, so the numeric value is unchanged here.
static uint32_t softether_node_le32(uint32_t x) {
    return x;
}
''',
    "NODE_INFO endian"
)
s = s.replace("softether_endian32(", "softether_node_le32(")
s = s.replace('"ClientHostName"', '"ClientHostname"')
s = s.replace('"ServerHostName"', '"ServerHostname"')
s = s.replace("client_ip_uint = (uint32_t)inet_addr(conn->client_ip_address);",
              "client_ip_uint = ntohl((uint32_t)inet_addr(conn->client_ip_address));")
s = s.replace("server_ip_uint = (uint32_t)inet_addr(conn->server_ip_address);",
              "server_ip_uint = ntohl((uint32_t)inet_addr(conn->server_ip_address));")

s = once(
    s,
    'pack_add_int(&p, "max_connection", 4);  // Request 4 connections for multi-connection throughput',
    'pack_add_int(&p, "max_connection", (uint32_t)(conn->max_connection > 0 ? conn->max_connection : 1));',
    "max_connection PACK"
)

s = once(
    s,
    '''    // RUDP-related fields (only sent when RUDP mode is active)
    if (rudp != NULL) {
        num_elems += 5;
        size += PACK_INT_SZ("support_bulk_on_rudp");
        size += PACK_INT_SZ("support_hmac_on_bulk_of_rudp");
        size += PACK_INT_SZ("support_udp_recovery");
        size += PACK_DATA_SZ("unique_id", SHA1_SIZE);
        size += PACK_INT_SZ("rudp_bulk_max_version");
    }
''',
    '''    // Official ClientUploadAuth() sends these capability fields for TCP too.
    num_elems += 5;
    size += PACK_INT_SZ("support_bulk_on_rudp");
    size += PACK_INT_SZ("support_hmac_on_bulk_of_rudp");
    size += PACK_INT_SZ("support_udp_recovery");
    size += PACK_DATA_SZ("unique_id", SHA1_SIZE);
    size += PACK_INT_SZ("rudp_bulk_max_version");
''',
    "TCP capability sizing"
)
s = once(
    s,
    '''    // RUDP-related fields (only sent when RUDP mode is active)
    if (rudp != NULL) {
        pack_add_int(&p, "support_bulk_on_rudp", 1);
        pack_add_int(&p, "support_hmac_on_bulk_of_rudp", 1);
        pack_add_int(&p, "support_udp_recovery", 1);
        pack_add_data(&p, "unique_id", unique_id, SHA1_SIZE);
        pack_add_int(&p, "rudp_bulk_max_version", 2);
    }
''',
    '''    // Official ClientUploadAuth() sends these capability fields for TCP too.
    pack_add_int(&p, "support_bulk_on_rudp", 1);
    pack_add_int(&p, "support_hmac_on_bulk_of_rudp", 1);
    pack_add_int(&p, "support_udp_recovery", 1);
    pack_add_data(&p, "unique_id", unique_id, SHA1_SIZE);
    pack_add_int(&p, "rudp_bulk_max_version", 2);
''',
    "TCP capability fields"
)

anchor = '''// Read an HTTP response precisely: headers byte-by-byte until \\r\\n\\r\\n,
// then exactly Content-Length bytes for the body.
'''
helper = '''// Write an entire HTTP/PACK TLS control message.
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
    "primary HTTP writes"
)
s = exact_n(
    s,
    "int write_ret = ssl_write(ssl_ctx, combined, (int)combined_len);",
    "int write_ret = ssl_write_all_control(ssl_ctx, combined, combined_len);",
    1,
    "additional connect watermark write"
)
s = exact_n(
    s,
    "int write_ret = ssl_write(ssl_ctx, auth_combined, (int)auth_combined_len);",
    "int write_ret = ssl_write_all_control(ssl_ctx, auth_combined, auth_combined_len);",
    1,
    "additional connect auth write"
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
    "login timeout mapping"
)
p.write_text(s)
print("patched softether_protocol.c")

# Surface exact ConnectionController error to Yinglong.
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
    "lastErrorMessage field"
)
s = once(
    s,
    '''        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY''',
    '''        Log.d(TAG, "Starting VPN with config: ${config.serverHost}:${config.serverPort}")
        lastErrorMessage = ""
        lastTrafficSnapshot = SoftEtherTrafficSnapshot.EMPTY''',
    "clear lastErrorMessage"
)
s = once(
    s,
    '''                    onError = { error ->
                        Log.e(TAG, "VPN Error: $error")''',
    '''                    onError = { error ->
                        lastErrorMessage = error
                        Log.e(TAG, "VPN Error: $error")''',
    "capture lastErrorMessage"
)
p.write_text(s)
print("patched SoftEtherVpnService.kt")

checks = {
    "write-all": "ssl_write_all_control" in (root/"src/main/cpp/softether-core/src/proto/softether_protocol.c").read_text(),
    "timeout-handle": "client.nativeSetOption(nativeHandle, SoftEtherClient.OPTION_TIMEOUT" in (root/"src/main/java/vn/unlimit/softether/controller/ConnectionController.kt").read_text(),
    "single-link": "client.nativeSetMaxConnection(nativeHandle, 1)" in (root/"src/main/java/vn/unlimit/softether/controller/ConnectionController.kt").read_text(),
    "error-propagation": "lastErrorMessage = error" in (root/"src/main/java/vn/unlimit/softether/SoftEtherVpnService.kt").read_text(),
}
bad=[k for k,v in checks.items() if not v]
if bad:
    raise SystemExit("core verification failed: "+", ".join(bad))
print("SoftEther native core parity patch: OK")
