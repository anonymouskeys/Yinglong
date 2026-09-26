#include <jni.h>
#include <netdb.h>
#include <string.h>
#include <strings.h>
#include <arpa/inet.h>

static JavaVM *dns_vm;
static jclass dns_class;
static jmethodID dns_resolve;

int YinglongDnsInit(JavaVM *vm, JNIEnv *env)
{
    jclass local = (*env)->FindClass(env, "org/yinglong/client/net/BootstrapDns");
    if (local == NULL) return 0;
    dns_class = (*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);
    if (dns_class == NULL) return 0;
    dns_resolve = (*env)->GetStaticMethodID(env, dns_class, "resolveIpv4",
                                          "(Ljava/lang/String;)Ljava/lang/String;");
    if (dns_resolve == NULL) return 0;
    dns_vm = vm;
    return 1;
}

static int nat_hostname(const char *host)
{
    const char *suffixes[] = {".servers.nat-traversal.softether-network.net",
                             ".servers.nat-traversal.uxcom.jp"};
    size_t n, i;
    if (host == NULL) return 0;
    n = strlen(host);
    if (n && host[n - 1] == '.') n--;
    for (i = 0; i < 2; i++) {
        size_t s = strlen(suffixes[i]);
        if (n > s && strncasecmp(host + n - s, suffixes[i], s) == 0) return 1;
    }
    return 0;
}

/* Only Mayaqua is compiled with getaddrinfo=YinglongGetAddrInfo.
 * Numeric relays and unrelated domains keep libc behavior. No wire protocol
 * or upstream Network.c changes; only NAT-T bootstrap DNS uses Android HTTPS.
 */
int YinglongGetAddrInfo(const char *node, const char *service,
                       const struct addrinfo *hints, struct addrinfo **result)
{
    JNIEnv *env = NULL;
    int detach = 0, rc = EAI_NONAME;
    jstring host = NULL, answer = NULL;
    char ip[INET_ADDRSTRLEN] = {0};
    struct in_addr checked;
    struct addrinfo numeric = {0};
    if (!nat_hostname(node)) return getaddrinfo(node, service, hints, result);
    *result = NULL;
    if (dns_vm == NULL || (hints && hints->ai_family == AF_INET6)) return EAI_NONAME;
    if ((*dns_vm)->GetEnv(dns_vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*dns_vm)->AttachCurrentThread(dns_vm, &env, NULL) != JNI_OK) return EAI_FAIL;
        detach = 1;
    }
    host = (*env)->NewStringUTF(env, node);
    if (host != NULL) answer = (*env)->CallStaticObjectMethod(env, dns_class, dns_resolve, host);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    } else if (answer != NULL) {
        const char *value = (*env)->GetStringUTFChars(env, answer, NULL);
        if (value != NULL) {
            if (strlen(value) < sizeof(ip)) strcpy(ip, value);
            (*env)->ReleaseStringUTFChars(env, answer, value);
        }
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (answer) (*env)->DeleteLocalRef(env, answer);
    if (host) (*env)->DeleteLocalRef(env, host);
    if (detach) (*dns_vm)->DetachCurrentThread(dns_vm);
    if (inet_pton(AF_INET, ip, &checked) == 1) {
        if (hints) numeric = *hints;
        numeric.ai_flags |= AI_NUMERICHOST;
        rc = getaddrinfo(ip, service, &numeric, result);
    }
    return rc;
}
