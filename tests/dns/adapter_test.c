#include <assert.h>
#include <stdio.h>
#include <stdarg.h>
#include <netdb.h>
#include <string.h>
#include "jni.h"

static int native_calls, java_calls, attached, detached, needs_attach, pending_exception;
static const char *dns_answer = "8.8.4.4";
static char last_node[256];
static struct addrinfo returned;
static int fake_getaddrinfo(const char *node, const char *service,
                            const struct addrinfo *hints, struct addrinfo **result)
{
    (void)service;
    native_calls++;
    snprintf(last_node, sizeof(last_node), "%s", node);
    if (java_calls) assert(hints && (hints->ai_flags & AI_NUMERICHOST));
    *result = &returned;
    return 0;
}
#define getaddrinfo fake_getaddrinfo
#include "../../official_softether/android_dns.c"
#undef getaddrinfo

static jclass find_class(JNIEnv *env, const char *name) {
    (void)env; assert(!strcmp(name, "org/yinglong/client/net/BootstrapDns")); return (void *)1;
}
static jobject global_ref(JNIEnv *env, jobject o) { (void)env; return o; }
static void delete_ref(JNIEnv *env, jobject o) { (void)env; (void)o; }
static jmethodID method(JNIEnv *env, jclass cls, const char *name, const char *sig) {
    (void)env; (void)cls; assert(!strcmp(name, "resolveIpv4"));
    assert(!strcmp(sig, "(Ljava/lang/String;)Ljava/lang/String;")); return (void *)2;
}
static jstring make_string(JNIEnv *env, const char *s) { (void)env; return (void *)s; }
static jobject resolve(JNIEnv *env, jclass cls, jmethodID id, ...) {
    (void)env; (void)cls; (void)id; java_calls++; return (void *)dns_answer;
}
static int has_exception(JNIEnv *env) { (void)env; return pending_exception; }
static void clear_exception(JNIEnv *env) { (void)env; pending_exception = 0; }
static const char *utf(JNIEnv *env, jstring s, void *copy) { (void)env; (void)copy; return s; }
static void release_utf(JNIEnv *env, jstring s, const char *v) { (void)env; (void)s; (void)v; }
static const struct JNINativeInterface env_table = {
    find_class, global_ref, delete_ref, method, make_string, resolve,
    has_exception, clear_exception, utf, release_utf
};
static JNIEnv test_env = &env_table;
static jint get_env(JavaVM *vm, void **env, jint version) {
    (void)vm; assert(version == JNI_VERSION_1_6); *env = &test_env; return needs_attach ? -2 : JNI_OK;
}
static jint attach(JavaVM *vm, JNIEnv **env, void *args) {
    (void)vm; (void)args; *env = &test_env; attached++; return JNI_OK;
}
static jint detach(JavaVM *vm) { (void)vm; detached++; return JNI_OK; }
static const struct JNIInvokeInterface vm_table = {get_env, attach, detach};

int main(void)
{
    JavaVM vm = &vm_table;
    struct addrinfo *out = NULL, hint = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM};
    assert(YinglongDnsInit(&vm, &test_env));
    assert(!YinglongGetAddrInfo("219.100.37.96", NULL, &hint, &out));
    assert(native_calls == 1 && java_calls == 0);
    assert(!YinglongGetAddrInfo("example.org", NULL, &hint, &out));
    assert(java_calls == 0);
    assert(!nat_hostname("x.servers.nat-traversal.softether-network.net.evil.org"));
    assert(nat_hostname("x1.x2.servers.nat-traversal.uxcom.jp."));
    needs_attach = 1;
    assert(!YinglongGetAddrInfo("x1.x2.servers.nat-traversal.softether-network.net.", NULL, &hint, &out));
    assert(java_calls == 1 && attached == 1 && detached == 1);
    assert(!strcmp(last_node, "8.8.4.4"));
    dns_answer = "";
    assert(YinglongGetAddrInfo("x1.x2.servers.nat-traversal.uxcom.jp", NULL, &hint, &out) == EAI_NONAME);
    assert(out == NULL && native_calls == 3 && attached == detached);
    dns_answer = "8.8.4.4"; pending_exception = 1;
    assert(YinglongGetAddrInfo("x1.x2.servers.nat-traversal.uxcom.jp", NULL, &hint, &out) == EAI_NONAME);
    assert(!pending_exception && native_calls == 3 && attached == detached);
    hint.ai_family = AF_INET6;
    int previous = java_calls;
    assert(YinglongGetAddrInfo("x1.x2.servers.nat-traversal.uxcom.jp", NULL, &hint, &out) == EAI_NONAME);
    assert(java_calls == previous);
    puts("DNS adapter tests passed: numeric/unrelated passthrough, NAT-T routing, failed DNS, JNI cleanup, IPv6.");
}
