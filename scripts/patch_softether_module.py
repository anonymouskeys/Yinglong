#!/usr/bin/env python3
# Yinglong v0.9.5 SoftEther Android integration patch.
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


# Bound TLS handshakes and keep the OpenSSL provider lock out of network waits.
# v0.9.4 could sit forever in SSL_do_handshake() because socket_connect_timeout()
# restored the fd to blocking mode before TLS. Worse, SSL_do_handshake() was
# called while holding g_openssl_lock, so one stalled network read also
# serialized the NAT-T/DNS fallback handshakes behind it.
p, s = load("src/main/cpp/softether-core/include/softether_crypto.h")
s = once(
    s,
    "int ssl_connect(ssl_context_t* ctx, int socket_fd, const char* hostname);\n",
    "int ssl_connect(ssl_context_t* ctx, int socket_fd, const char* hostname);\n"
    "int ssl_connect_timeout(ssl_context_t* ctx, int socket_fd, const char* hostname, int timeout_ms);\n",
    "TLS timeout API declaration",
)
p.write_text(s)
print("patched softether_crypto.h")

p, s = load("src/main/cpp/softether-core/src/crypto/aes_wrapper.c")
s = once(
    s,
    "#include <unistd.h>\n#include <pthread.h>\n",
    "#include <unistd.h>\n#include <pthread.h>\n#include <fcntl.h>\n#include <poll.h>\n#include <time.h>\n#include <stdint.h>\n",
    "TLS timeout includes",
)

start = s.find("int ssl_connect(ssl_context_t* ctx, int socket_fd, const char* hostname) {")
end = s.find("\nint ssl_read(ssl_context_t* ctx, uint8_t* buffer, size_t len) {", start)
if start < 0 or end < 0:
    raise SystemExit("ssl_connect function span not found")

new_ssl_connect = r'''static uint64_t ssl_monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((uint64_t)ts.tv_sec * 1000ULL) +
           ((uint64_t)ts.tv_nsec / 1000000ULL);
}

int ssl_connect_timeout(ssl_context_t* ctx, int socket_fd, const char* hostname,
                        int timeout_ms) {
    if (ctx == NULL || ctx->ctx == NULL || socket_fd < 0) {
        LOGE("Invalid SSL context/socket");
        return -1;
    }
    if (timeout_ms <= 0) {
        timeout_ms = 15000;
    }

    pthread_rwlock_wrlock(&g_tls_use_lock);
    ctx->ssl = SSL_new(ctx->ctx);
    if (ctx->ssl == NULL) {
        LOGE("Failed to create SSL object");
        pthread_rwlock_unlock(&g_tls_use_lock);
        return -1;
    }

    if (hostname != NULL && hostname[0] != '\0') {
        SSL_set_tlsext_host_name(ctx->ssl, hostname);
    }

    if (SSL_set_fd(ctx->ssl, socket_fd) != 1) {
        LOGE("Failed to set SSL fd");
        pthread_mutex_lock(&g_openssl_lock);
        SSL_free(ctx->ssl);
        pthread_mutex_unlock(&g_openssl_lock);
        ctx->ssl = NULL;
        pthread_rwlock_unlock(&g_tls_use_lock);
        return -1;
    }

    SSL_set_connect_state(ctx->ssl);
    pthread_rwlock_unlock(&g_tls_use_lock);

    /*
     * The fd must be non-blocking during the handshake. The old code called
     * SSL_do_handshake() on a blocking fd while holding g_openssl_lock. A peer
     * which accepted TCP but did not answer TLS could block the native core
     * forever and prevent the transport fallback from progressing.
     */
    int old_flags = fcntl(socket_fd, F_GETFL, 0);
    if (old_flags >= 0) {
        if (fcntl(socket_fd, F_SETFL, old_flags | O_NONBLOCK) != 0) {
            LOGE("Failed to set TLS fd non-blocking: errno=%d (%s)",
                 errno, strerror(errno));
        }
    }

    uint64_t deadline = ssl_monotonic_ms() + (uint64_t)timeout_ms;
    int result = -1;
    int ssl_error = SSL_ERROR_NONE;

    pthread_rwlock_rdlock(&g_tls_use_lock);

    for (;;) {
        // Serialize only the OpenSSL step. Never hold this global mutex while
        // waiting in poll(), otherwise NAT-T/DNS race threads are serialized.
        pthread_mutex_lock(&g_openssl_lock);
        result = SSL_do_handshake(ctx->ssl);
        pthread_mutex_unlock(&g_openssl_lock);

        if (result == 1) {
            break;
        }

        ssl_error = SSL_get_error(ctx->ssl, result);
        if (ssl_error != SSL_ERROR_WANT_READ &&
            ssl_error != SSL_ERROR_WANT_WRITE) {
            unsigned long err_detail = ERR_get_error();
            if (ssl_error == SSL_ERROR_SYSCALL) {
                LOGE("SSL handshake failed: error=%d errno=%d (%s) detail=%lu (%s)",
                     ssl_error, errno, strerror(errno), err_detail,
                     err_detail ? ERR_error_string(err_detail, NULL) : "EOF/no-detail");
            } else {
                LOGE("SSL handshake failed: error=%d detail=%lu (%s)",
                     ssl_error, err_detail,
                     err_detail ? ERR_error_string(err_detail, NULL) : "none");
            }
            goto HANDSHAKE_FAIL_LOCKED;
        }

        uint64_t now = ssl_monotonic_ms();
        if (now >= deadline) {
            LOGE("SSL handshake timeout after %d ms fd=%d host=%s",
                 timeout_ms, socket_fd, hostname ? hostname : "");
            goto HANDSHAKE_FAIL_LOCKED;
        }

        int remaining = (int)(deadline - now);
        struct pollfd pfd;
        memset(&pfd, 0, sizeof(pfd));
        pfd.fd = socket_fd;
        pfd.events = (ssl_error == SSL_ERROR_WANT_WRITE) ? POLLOUT : POLLIN;

        int pr;
        do {
            pr = poll(&pfd, 1, remaining);
        } while (pr < 0 && errno == EINTR);

        if (pr == 0) {
            LOGE("SSL handshake poll timeout after %d ms fd=%d host=%s",
                 timeout_ms, socket_fd, hostname ? hostname : "");
            goto HANDSHAKE_FAIL_LOCKED;
        }
        if (pr < 0) {
            LOGE("SSL handshake poll failed fd=%d errno=%d (%s)",
                 socket_fd, errno, strerror(errno));
            goto HANDSHAKE_FAIL_LOCKED;
        }
        if (pfd.revents & (POLLERR | POLLNVAL | POLLHUP)) {
            int so_error = 0;
            socklen_t so_len = sizeof(so_error);
            getsockopt(socket_fd, SOL_SOCKET, SO_ERROR, &so_error, &so_len);
            LOGE("SSL handshake socket failure fd=%d revents=0x%x so_error=%d (%s)",
                 socket_fd, pfd.revents, so_error,
                 so_error ? strerror(so_error) : "none");
            goto HANDSHAKE_FAIL_LOCKED;
        }
    }

    ctx->connected = 1;
    SSL_set_mode(ctx->ssl, SSL_MODE_AUTO_RETRY);
    SSL_set_mode(ctx->ssl, SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER);

    {
        int tls_ver = SSL_version(ctx->ssl);
        const char* ver_str = SSL_get_version(ctx->ssl);
        const char* cipher = SSL_get_cipher(ctx->ssl);
        LOGD("SSL handshake successful (version: %s / 0x%04X, cipher: %s)",
             ver_str ? ver_str : "unknown", tls_ver,
             cipher ? cipher : "unknown");
    }

    pthread_rwlock_unlock(&g_tls_use_lock);

    if (old_flags >= 0) {
        fcntl(socket_fd, F_SETFL, old_flags);
    }
    return 0;

HANDSHAKE_FAIL_LOCKED:
    pthread_rwlock_unlock(&g_tls_use_lock);

    if (old_flags >= 0) {
        fcntl(socket_fd, F_SETFL, old_flags);
    }

    pthread_rwlock_wrlock(&g_tls_use_lock);
    pthread_mutex_lock(&g_openssl_lock);
    if (ctx->ssl != NULL) {
        SSL_free(ctx->ssl);
        ctx->ssl = NULL;
    }
    pthread_mutex_unlock(&g_openssl_lock);
    pthread_rwlock_unlock(&g_tls_use_lock);

    ctx->connected = 0;
    return -1;
}

int ssl_connect(ssl_context_t* ctx, int socket_fd, const char* hostname) {
    return ssl_connect_timeout(ctx, socket_fd, hostname, 15000);
}
'''
s = s[:start] + new_ssl_connect + s[end:]
p.write_text(s)
print("patched aes_wrapper.c")

# socket_connect_timeout() restores blocking mode after connect. Apply the same
# deadline to later blocking TLS/HTTP I/O too.
p, s = load("src/main/cpp/softether-core/src/socket/tcp_socket.c")
s = exact_n(
    s,
    '''            sock->connected = 1;
            sock->timeout_ms = timeout_ms;
            LOGD("Connected to %s:%d", last_ip[0] ? last_ip : host, port);''',
    '''            sock->connected = 1;
            sock->timeout_ms = timeout_ms;
            {
                struct timeval tv;
                tv.tv_sec = timeout_ms / 1000;
                tv.tv_usec = (timeout_ms % 1000) * 1000;
                setsockopt(sock->fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
                setsockopt(sock->fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
            }
            LOGD("Connected to %s:%d", last_ip[0] ? last_ip : host, port);''',
    2,
    "connected socket I/O timeout",
)
p.write_text(s)
print("patched tcp_socket.c")

# Feed the real per-connection timeout into every TLS handshake:
# primary, five transport-race paths, and one additional TCP link.
p, s = load("src/main/cpp/softether-core/src/proto/softether_protocol.c")
s = once(
    s,
    "ssl_connect(ssl_ctx, conn->socket_fd, hostname)",
    "ssl_connect_timeout(ssl_ctx, conn->socket_fd, hostname, conn->timeout_ms)",
    "primary TLS timeout",
)
s = exact_n(
    s,
    "ssl_connect(ssl_ctx, fd, ctx->connect_host)",
    "ssl_connect_timeout(ssl_ctx, fd, ctx->connect_host, (int)ctx->timeout_ms)",
    5,
    "transport race TLS timeout",
)
s = once(
    s,
    "ssl_connect(ssl_ctx, fd, conn->server_ip)",
    "ssl_connect_timeout(ssl_ctx, fd, conn->server_ip, conn->timeout_ms)",
    "additional TLS timeout",
)
p.write_text(s)
print("patched TLS call sites")

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
    "bounded TLS": "ssl_connect_timeout" in (root / "src/main/cpp/softether-core/src/crypto/aes_wrapper.c").read_text(),
    "TLS call sites": "ssl_connect_timeout(ssl_ctx, conn->socket_fd" in proto,
    "VPNGate ClientHostName preserved": '"ClientHostName"' in proto,
    "VPNGate ServerHostName preserved": '"ServerHostName"' in proto,
    "VPNGate endian helper preserved": "softether_endian32" in proto,
}
bad = [name for name, ok in checks.items() if not ok]
if bad:
    raise SystemExit("SoftEther v0.9.5 verification failed: " + ", ".join(bad))

print("SoftEther v0.9.5 integration/core patch: OK")
