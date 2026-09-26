#!/usr/bin/env python3
"""Exercise the generated TLS function with real OpenSSL and local sockets."""
from pathlib import Path
import subprocess
import sys
import tempfile

module = Path(sys.argv[1] if len(sys.argv) > 1 else 'third_party/SoftEtherClient')
source = (module / 'src/main/cpp/softether-core/src/crypto/aes_wrapper.c').read_text()
start = source.index('static uint64_t ssl_monotonic_ms(void)')
end = source.index('\nint ssl_read(', start)
harness = r'''
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <pthread.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <time.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/time.h>
#define LOGE(...) ((void)0)
#define LOGD(...) ((void)0)
typedef struct { SSL_CTX *ctx; SSL *ssl; int connected; } ssl_context_t;
static pthread_rwlock_t g_tls_use_lock = PTHREAD_RWLOCK_INITIALIZER;
static pthread_mutex_t g_openssl_lock = PTHREAD_MUTEX_INITIALIZER;
static int fail_command = -1;
static int test_fcntl(int fd, int command, ...) {
    if (command == fail_command) { errno = EIO; return -1; }
    if (command == F_GETFL) return fcntl(fd, command);
    va_list args;
    va_start(args, command);
    int flags = va_arg(args, int);
    va_end(args);
    return fcntl(fd, command, flags);
}
#define fcntl test_fcntl
''' + source[start:end] + r'''
#undef fcntl
static volatile sig_atomic_t signals_seen;
static void on_alarm(int sig) { (void)sig; signals_seen++; }
static void check(int ok, const char *message) {
    if (!ok) { fprintf(stderr, "FAIL: %s\n", message); exit(1); }
}
static void run_case(const char *name, int failing_command, int with_signals) {
    int sockets[2];
    check(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0, "socketpair");
    int flags = fcntl(sockets[0], F_GETFL);
    ssl_context_t ctx = { .ctx = SSL_CTX_new(TLS_client_method()) };
    check(ctx.ctx != NULL, "SSL_CTX_new");
    fail_command = failing_command;
    struct itimerval timer = {0};
    if (with_signals) {
        timer.it_interval.tv_usec = 10000;
        timer.it_value = timer.it_interval;
        check(setitimer(ITIMER_REAL, &timer, NULL) == 0, "enable signals");
    }
    uint64_t began = ssl_monotonic_ms();
    int result = ssl_connect_timeout(&ctx, sockets[0], "localhost", 150);
    uint64_t elapsed = ssl_monotonic_ms() - began;
    timer.it_interval.tv_usec = timer.it_value.tv_usec = 0;
    setitimer(ITIMER_REAL, &timer, NULL);
    fail_command = -1;
    check(result == -1, "silent peer must fail");
    check(ctx.ssl == NULL && !ctx.connected, "failed SSL object must be freed");
    check(fcntl(sockets[0], F_GETFL) == flags, "socket flags must be restored");
    check(elapsed < 1000, "deadline must hold even with signals");
    if (failing_command < 0) check(elapsed >= 100, "must actually wait for peer");
    if (with_signals) check(signals_seen > 5, "poll must receive repeated signals");
    SSL_CTX_free(ctx.ctx);
    close(sockets[0]); close(sockets[1]);
    printf("PASS: %s (%llu ms)\n", name, (unsigned long long)elapsed);
}
int main(void) {
    signal(SIGPIPE, SIG_IGN);
    struct sigaction action = { .sa_handler = on_alarm };
    sigemptyset(&action.sa_mask);
    sigaction(SIGALRM, &action, NULL);
    run_case("silent TLS peer", -1, 0);
    run_case("TLS deadline under repeated EINTR", -1, 1);
    run_case("F_GETFL failure", F_GETFL, 0);
    run_case("F_SETFL failure", F_SETFL, 0);
    return 0;
}
'''
with tempfile.TemporaryDirectory() as directory:
    c = Path(directory) / 'tls_timeout.c'
    binary = Path(directory) / 'tls_timeout'
    c.write_text('#include <stdarg.h>\n' + harness)
    subprocess.run(['cc', '-std=gnu11', '-Wall', '-Wextra', '-Wno-unused-variable',
                    str(c), '-o', str(binary), '-lssl', '-lcrypto', '-lpthread'], check=True)
    subprocess.run([str(binary)], check=True, timeout=5)
