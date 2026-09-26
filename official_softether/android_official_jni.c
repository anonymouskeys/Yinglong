#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <errno.h>

#include <Cedar/CedarPch.h>

#define TAG "YinglongOfficialSE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define YS_STATE_DISCONNECTED        0
#define YS_STATE_CONNECTING          1
#define YS_STATE_TLS_HANDSHAKE       2
#define YS_STATE_PROTOCOL_HANDSHAKE  3
#define YS_STATE_AUTHENTICATING      4
#define YS_STATE_SESSION_SETUP       5
#define YS_STATE_CONNECTED           6
#define YS_STATE_DISCONNECTING       7

#define YS_ERR_NONE                  0
#define YS_ERR_TCP_CONNECT           1
#define YS_ERR_TLS_HANDSHAKE         2
#define YS_ERR_PROTOCOL_VERSION      3
#define YS_ERR_AUTHENTICATION        4
#define YS_ERR_SESSION               5
#define YS_ERR_DATA_TRANSMISSION     6
#define YS_ERR_TIMEOUT               7
#define YS_ERR_UNKNOWN              99

#define OPT_TIMEOUT                  1
#define OPT_UDP_PORT                 4
#define OPT_UDP_ONLY                 5

#define ETH_HLEN                    14
#define ETH_P_IP                0x0800
#define ETH_P_ARP               0x0806
#define ETH_P_IPV6              0x86DD

#define DHCP_DISCOVER                1
#define DHCP_OFFER                   2
#define DHCP_REQUEST                 3
#define DHCP_ACK                     5
#define DHCP_NAK                     6
#define DHCP_MAGIC_COOKIE   0x63825363U

typedef struct frame_node
{
    UCHAR *data;
    UINT size;
    struct frame_node *next;
} FRAME_NODE;

typedef struct official_ctx
{
    pthread_mutex_t lock;
    pthread_cond_t rx_cond;

    FRAME_NODE *tx_head;
    FRAME_NODE *tx_tail;
    FRAME_NODE *rx_head;
    FRAME_NODE *rx_tail;

    CANCEL *adapter_cancel;
    CLIENT *client;
    CEDAR *cedar;
    SESSION *session;

    volatile int stopping;
    volatile int adapter_active;
    volatile int state;

    int timeout_ms;
    char last_error[192];
    int forced_auth_type;
    int max_connection;
    int half_connection;
    int udp_port;
    int udp_only;

    UCHAR client_mac[6];
    UCHAR gateway_mac[6];
    int gateway_mac_valid;

    UINT assigned_ip;
    UINT subnet_mask;
    UINT gateway_ip;
    UINT dns1;
    UINT dns2;
    UINT lease_time;

    UINT64 tx_packets;
    UINT64 tx_bytes;
    UINT64 rx_packets;
    UINT64 rx_bytes;
} OFFICIAL_CTX;

static pthread_once_t g_init_once = PTHREAD_ONCE_INIT;

static void official_global_init(void)
{
    char *argv[2];
    argv[0] = (char *)"/system/bin/sh";
    argv[1] = NULL;

    InitProcessCallOnce();
    MayaquaMinimalMode();
    InitMayaqua(false, false, 1, argv);
    InitCedar();

    LOGI("Official SoftEther initialized: Cedar %u build %u",
         (unsigned int)CEDAR_VER, (unsigned int)CEDAR_BUILD);
}

static void ensure_official_init(void)
{
    pthread_once(&g_init_once, official_global_init);
}

static void free_frame_node(FRAME_NODE *n)
{
    if (n == NULL) return;
    if (n->data != NULL) Free(n->data);
    free(n);
}

static void clear_queue(FRAME_NODE **head, FRAME_NODE **tail)
{
    FRAME_NODE *n = *head;
    while (n != NULL)
    {
        FRAME_NODE *next = n->next;
        free_frame_node(n);
        n = next;
    }
    *head = NULL;
    *tail = NULL;
}

static int enqueue_owned(FRAME_NODE **head, FRAME_NODE **tail, UCHAR *data, UINT size)
{
    FRAME_NODE *n = (FRAME_NODE *)calloc(1, sizeof(FRAME_NODE));
    if (n == NULL)
    {
        if (data != NULL) Free(data);
        return 0;
    }

    n->data = data;
    n->size = size;

    if (*tail != NULL)
    {
        (*tail)->next = n;
    }
    else
    {
        *head = n;
    }
    *tail = n;
    return 1;
}

static FRAME_NODE *dequeue_node(FRAME_NODE **head, FRAME_NODE **tail)
{
    FRAME_NODE *n = *head;
    if (n == NULL) return NULL;

    *head = n->next;
    if (*head == NULL) *tail = NULL;
    n->next = NULL;
    return n;
}

static void wake_session(OFFICIAL_CTX *ctx)
{
    if (ctx != NULL && ctx->adapter_cancel != NULL)
    {
        Cancel(ctx->adapter_cancel);
    }
}

static int tx_copy_frame(OFFICIAL_CTX *ctx, const void *data, UINT size)
{
    UCHAR *copy;
    int ok;

    if (ctx == NULL || data == NULL || size == 0 || ctx->stopping) return 0;

    copy = (UCHAR *)Malloc(size);
    if (copy == NULL) return 0;
    Copy(copy, data, size);

    pthread_mutex_lock(&ctx->lock);
    ok = enqueue_owned(&ctx->tx_head, &ctx->tx_tail, copy, size);
    pthread_mutex_unlock(&ctx->lock);

    if (ok) wake_session(ctx);
    return ok;
}

static int rx_wait_pop(OFFICIAL_CTX *ctx, FRAME_NODE **out, int timeout_ms)
{
    int ret = 0;
    struct timespec ts;

    if (ctx == NULL || out == NULL) return 0;
    *out = NULL;

    pthread_mutex_lock(&ctx->lock);

    if (ctx->rx_head == NULL && !ctx->stopping && timeout_ms > 0)
    {
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += timeout_ms / 1000;
        ts.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (ts.tv_nsec >= 1000000000L)
        {
            ts.tv_sec++;
            ts.tv_nsec -= 1000000000L;
        }

        while (ctx->rx_head == NULL && !ctx->stopping)
        {
            int e = pthread_cond_timedwait(&ctx->rx_cond, &ctx->lock, &ts);
            if (e == ETIMEDOUT) break;
            if (e != 0) break;
        }
    }

    if (ctx->rx_head != NULL)
    {
        *out = dequeue_node(&ctx->rx_head, &ctx->rx_tail);
        ret = (*out != NULL);
    }

    pthread_mutex_unlock(&ctx->lock);
    return ret;
}

/* ------------------------------------------------------------------------- */
/* Official Cedar PACKET_ADAPTER                                             */
/* ------------------------------------------------------------------------- */

static bool AndroidPaInit(SESSION *s)
{
    OFFICIAL_CTX *ctx;

    if (s == NULL || s->PacketAdapter == NULL) return false;
    ctx = (OFFICIAL_CTX *)s->PacketAdapter->Param;
    if (ctx == NULL || ctx->stopping) return false;

    ctx->adapter_active = 1;
    ctx->state = YS_STATE_SESSION_SETUP;
    LOGI("Cedar packet adapter initialized");
    return true;
}

static CANCEL *AndroidPaGetCancel(SESSION *s)
{
    OFFICIAL_CTX *ctx;

    if (s == NULL || s->PacketAdapter == NULL) return NULL;
    ctx = (OFFICIAL_CTX *)s->PacketAdapter->Param;
    if (ctx == NULL || ctx->adapter_cancel == NULL) return NULL;

    AddRef(ctx->adapter_cancel->ref);
    return ctx->adapter_cancel;
}

static UINT AndroidPaGetNextPacket(SESSION *s, void **data)
{
    OFFICIAL_CTX *ctx;
    FRAME_NODE *n;

    if (data == NULL) return INFINITE;
    *data = NULL;

    if (s == NULL || s->PacketAdapter == NULL) return INFINITE;
    ctx = (OFFICIAL_CTX *)s->PacketAdapter->Param;
    if (ctx == NULL || ctx->stopping) return INFINITE;

    pthread_mutex_lock(&ctx->lock);
    n = dequeue_node(&ctx->tx_head, &ctx->tx_tail);
    pthread_mutex_unlock(&ctx->lock);

    if (n == NULL) return 0;

    *data = n->data;
    {
        UINT size = n->size;
        n->data = NULL; /* Cedar takes ownership of the returned buffer. */
        free(n);
        return size;
    }
}

static bool AndroidPaPutPacket(SESSION *s, void *data, UINT size)
{
    OFFICIAL_CTX *ctx;
    int ok;

    if (s == NULL || s->PacketAdapter == NULL)
    {
        if (data != NULL) Free(data);
        return false;
    }

    ctx = (OFFICIAL_CTX *)s->PacketAdapter->Param;
    if (ctx == NULL || ctx->stopping)
    {
        if (data != NULL) Free(data);
        return false;
    }

    /* Cedar uses NULL/0 as a flush marker. */
    if (data == NULL || size == 0) return true;

    pthread_mutex_lock(&ctx->lock);
    ok = enqueue_owned(&ctx->rx_head, &ctx->rx_tail, (UCHAR *)data, size);
    pthread_cond_signal(&ctx->rx_cond);
    pthread_mutex_unlock(&ctx->lock);

    return ok ? true : false;
}

static void AndroidPaFree(SESSION *s)
{
    OFFICIAL_CTX *ctx;

    if (s == NULL || s->PacketAdapter == NULL) return;
    ctx = (OFFICIAL_CTX *)s->PacketAdapter->Param;
    if (ctx == NULL) return;

    ctx->adapter_active = 0;
    pthread_mutex_lock(&ctx->lock);
    pthread_cond_broadcast(&ctx->rx_cond);
    pthread_mutex_unlock(&ctx->lock);

    LOGI("Cedar packet adapter released");
}

/* ------------------------------------------------------------------------- */
/* Ethernet / ARP / DHCP glue: Android VpnService is L3, Cedar client is L2 */
/* ------------------------------------------------------------------------- */

static USHORT ip_checksum(const UCHAR *data, UINT len)
{
    UINT sum = 0;
    UINT i;

    for (i = 0; i + 1 < len; i += 2)
    {
        sum += ((UINT)data[i] << 8) | data[i + 1];
    }
    if (len & 1) sum += ((UINT)data[len - 1] << 8);

    while (sum >> 16) sum = (sum & 0xffffU) + (sum >> 16);
    return (USHORT)(~sum & 0xffffU);
}

static UINT read_u32_be(const UCHAR *p)
{
    return ((UINT)p[0] << 24) | ((UINT)p[1] << 16) | ((UINT)p[2] << 8) | p[3];
}

static void write_u32_be(UCHAR *p, UINT v)
{
    p[0] = (UCHAR)(v >> 24);
    p[1] = (UCHAR)(v >> 16);
    p[2] = (UCHAR)(v >> 8);
    p[3] = (UCHAR)v;
}

static int build_dhcp_frame(UCHAR *frame, UINT cap, const UCHAR *mac,
                            UINT xid, UCHAR type, UINT req_ip, UINT server_id)
{
    UCHAR dhcp[320];
    UCHAR ip[20];
    UCHAR udp[8];
    UINT p = 0;
    UINT dhcp_len, udp_len, ip_len, total;
    USHORT csum;

    Zero(dhcp, sizeof(dhcp));
    dhcp[p++] = 1;
    dhcp[p++] = 1;
    dhcp[p++] = 6;
    dhcp[p++] = 0;
    write_u32_be(dhcp + p, xid); p += 4;
    dhcp[p++] = 0; dhcp[p++] = 0;
    dhcp[p++] = 0x80; dhcp[p++] = 0;
    p = 28;
    Copy(dhcp + p, mac, 6);
    p = 236;
    dhcp[p++] = 0x63; dhcp[p++] = 0x82; dhcp[p++] = 0x53; dhcp[p++] = 0x63;
    dhcp[p++] = 53; dhcp[p++] = 1; dhcp[p++] = type;

    if (type == DHCP_REQUEST)
    {
        if (req_ip != 0)
        {
            dhcp[p++] = 50; dhcp[p++] = 4;
            write_u32_be(dhcp + p, req_ip); p += 4;
        }
        if (server_id != 0)
        {
            dhcp[p++] = 54; dhcp[p++] = 4;
            write_u32_be(dhcp + p, server_id); p += 4;
        }
    }

    dhcp[p++] = 55; dhcp[p++] = 4;
    dhcp[p++] = 1; dhcp[p++] = 3; dhcp[p++] = 6; dhcp[p++] = 51;
    dhcp[p++] = 255;
    dhcp_len = p;

    udp_len = 8 + dhcp_len;
    Zero(udp, sizeof(udp));
    udp[0] = 0; udp[1] = 68;
    udp[2] = 0; udp[3] = 67;
    udp[4] = (UCHAR)(udp_len >> 8);
    udp[5] = (UCHAR)udp_len;

    ip_len = 20 + udp_len;
    Zero(ip, sizeof(ip));
    ip[0] = 0x45;
    ip[2] = (UCHAR)(ip_len >> 8);
    ip[3] = (UCHAR)ip_len;
    ip[8] = 128;
    ip[9] = 17;
    ip[16] = 255; ip[17] = 255; ip[18] = 255; ip[19] = 255;
    csum = ip_checksum(ip, 20);
    ip[10] = (UCHAR)(csum >> 8);
    ip[11] = (UCHAR)csum;

    total = 14 + ip_len;
    if (total > cap) return -1;

    memset(frame, 0xff, 6);
    Copy(frame + 6, mac, 6);
    frame[12] = 0x08; frame[13] = 0x00;
    Copy(frame + 14, ip, 20);
    Copy(frame + 34, udp, 8);
    Copy(frame + 42, dhcp, dhcp_len);

    return (int)total;
}

static int parse_dhcp(const UCHAR *frame, UINT len, UINT xid, const UCHAR *mac,
                      UINT *assigned, UINT *mask, UINT *gateway,
                      UINT *dns1, UINT *dns2, UINT *lease, UINT *server_id)
{
    const UCHAR *ip, *udp, *d;
    UINT ip_hlen, dlen, pos;
    int msg = 0;

    if (len < 14 + 20 + 8 + 240) return 0;
    if ((((UINT)frame[12] << 8) | frame[13]) != ETH_P_IP) return 0;

    ip = frame + 14;
    if ((ip[0] >> 4) != 4 || ip[9] != 17) return 0;
    ip_hlen = (ip[0] & 0x0f) * 4;
    if (len < 14 + ip_hlen + 8 + 240) return 0;

    udp = ip + ip_hlen;
    if (udp[0] != 0 || udp[1] != 67 || udp[2] != 0 || udp[3] != 68) return 0;

    d = udp + 8;
    dlen = len - 14 - ip_hlen - 8;
    if (d[0] != 2 || read_u32_be(d + 4) != xid || Cmp(d + 28, mac, 6) != 0) return 0;
    if (read_u32_be(d + 236) != DHCP_MAGIC_COOKIE) return 0;

    *assigned = read_u32_be(d + 16);
    pos = 240;

    while (pos < dlen)
    {
        UCHAR opt = d[pos++];
        UCHAR olen;
        if (opt == 255) break;
        if (opt == 0) continue;
        if (pos >= dlen) break;
        olen = d[pos++];
        if (pos + olen > dlen) break;

        switch (opt)
        {
            case 1:
                if (olen >= 4) *mask = read_u32_be(d + pos);
                break;
            case 3:
                if (olen >= 4) *gateway = read_u32_be(d + pos);
                break;
            case 6:
                if (olen >= 4) *dns1 = read_u32_be(d + pos);
                if (olen >= 8) *dns2 = read_u32_be(d + pos + 4);
                break;
            case 51:
                if (olen >= 4) *lease = read_u32_be(d + pos);
                break;
            case 53:
                if (olen >= 1) msg = d[pos];
                break;
            case 54:
                if (olen >= 4) *server_id = read_u32_be(d + pos);
                break;
        }
        pos += olen;
    }

    return msg;
}

static int wait_dhcp(OFFICIAL_CTX *ctx, UINT xid, int timeout_ms,
                     UINT *assigned, UINT *mask, UINT *gateway,
                     UINT *dns1, UINT *dns2, UINT *lease, UINT *server_id)
{
    UINT64 start = Tick64();

    while (!ctx->stopping && (int)(Tick64() - start) < timeout_ms)
    {
        FRAME_NODE *n = NULL;
        int remaining = timeout_ms - (int)(Tick64() - start);
        int msg;

        if (remaining <= 0) break;
        if (!rx_wait_pop(ctx, &n, remaining > 200 ? 200 : remaining)) continue;
        if (n == NULL) continue;

        msg = parse_dhcp(n->data, n->size, xid, ctx->client_mac,
                         assigned, mask, gateway, dns1, dns2, lease, server_id);
        free_frame_node(n);
        if (msg != 0) return msg;
    }

    return 0;
}

static void send_gratuitous_arp(OFFICIAL_CTX *ctx)
{
    UCHAR f[42];
    if (ctx == NULL || ctx->assigned_ip == 0) return;

    Zero(f, sizeof(f));
    memset(f, 0xff, 6);
    Copy(f + 6, ctx->client_mac, 6);
    f[12] = 0x08; f[13] = 0x06;
    f[14] = 0; f[15] = 1;
    f[16] = 0x08; f[17] = 0;
    f[18] = 6; f[19] = 4;
    f[20] = 0; f[21] = 1;
    Copy(f + 22, ctx->client_mac, 6);
    write_u32_be(f + 28, ctx->assigned_ip);
    write_u32_be(f + 38, ctx->assigned_ip);
    tx_copy_frame(ctx, f, sizeof(f));
}

static int resolve_gateway_mac(OFFICIAL_CTX *ctx)
{
    UCHAR arp[42];
    int attempt;

    if (ctx == NULL || ctx->gateway_ip == 0 || ctx->assigned_ip == 0) return 0;

    Zero(arp, sizeof(arp));
    memset(arp, 0xff, 6);
    Copy(arp + 6, ctx->client_mac, 6);
    arp[12] = 0x08; arp[13] = 0x06;
    arp[14] = 0; arp[15] = 1;
    arp[16] = 0x08; arp[17] = 0;
    arp[18] = 6; arp[19] = 4;
    arp[20] = 0; arp[21] = 1;
    Copy(arp + 22, ctx->client_mac, 6);
    write_u32_be(arp + 28, ctx->assigned_ip);
    write_u32_be(arp + 38, ctx->gateway_ip);

    for (attempt = 0; attempt < 3 && !ctx->stopping; attempt++)
    {
        UINT64 start;
        tx_copy_frame(ctx, arp, sizeof(arp));
        start = Tick64();

        while (Tick64() - start < 1200)
        {
            FRAME_NODE *n = NULL;
            if (!rx_wait_pop(ctx, &n, 100)) continue;
            if (n == NULL) continue;

            if (n->size >= 42 &&
                n->data[12] == 0x08 && n->data[13] == 0x06 &&
                n->data[20] == 0 && n->data[21] == 2 &&
                read_u32_be(n->data + 28) == ctx->gateway_ip)
            {
                Copy(ctx->gateway_mac, n->data + 22, 6);
                ctx->gateway_mac_valid = 1;
                free_frame_node(n);
                LOGI("gateway MAC resolved");
                return 1;
            }

            free_frame_node(n);
        }
    }

    LOGW("gateway MAC not resolved; using broadcast fallback");
    return 0;
}

static void reply_arp_if_needed(OFFICIAL_CTX *ctx, const UCHAR *f, UINT len)
{
    UCHAR r[42];
    if (ctx == NULL || f == NULL || len < 42 || ctx->assigned_ip == 0) return;
    if (f[12] != 0x08 || f[13] != 0x06) return;
    if (f[20] != 0 || f[21] != 1) return;
    if (read_u32_be(f + 38) != ctx->assigned_ip) return;

    Zero(r, sizeof(r));
    Copy(r, f + 6, 6);
    Copy(r + 6, ctx->client_mac, 6);
    r[12] = 0x08; r[13] = 0x06;
    r[14] = 0; r[15] = 1;
    r[16] = 0x08; r[17] = 0;
    r[18] = 6; r[19] = 4;
    r[20] = 0; r[21] = 2;
    Copy(r + 22, ctx->client_mac, 6);
    write_u32_be(r + 28, ctx->assigned_ip);
    Copy(r + 32, f + 22, 6);
    Copy(r + 38, f + 28, 4);
    tx_copy_frame(ctx, r, sizeof(r));
}

static int send_ip_packet(OFFICIAL_CTX *ctx, const UCHAR *ip, UINT len)
{
    UCHAR *f;
    USHORT type;
    int ok;

    if (ctx == NULL || ip == NULL || len == 0 || len > 65520 || !ctx->adapter_active) return -1;

    f = (UCHAR *)Malloc(len + ETH_HLEN);
    if (f == NULL) return -1;

    if (ctx->gateway_mac_valid)
        Copy(f, ctx->gateway_mac, 6);
    else
        memset(f, 0xff, 6);

    Copy(f + 6, ctx->client_mac, 6);
    type = ((ip[0] >> 4) == 6) ? ETH_P_IPV6 : ETH_P_IP;
    f[12] = (UCHAR)(type >> 8);
    f[13] = (UCHAR)type;
    Copy(f + ETH_HLEN, ip, len);

    pthread_mutex_lock(&ctx->lock);
    ok = enqueue_owned(&ctx->tx_head, &ctx->tx_tail, f, len + ETH_HLEN);
    pthread_mutex_unlock(&ctx->lock);
    if (!ok) return -1;

    wake_session(ctx);
    ctx->tx_packets++;
    ctx->tx_bytes += len;
    return (int)len;
}

static int recv_ip_packet(OFFICIAL_CTX *ctx, UCHAR *out, UINT cap, int timeout_ms)
{
    int loops;

    if (ctx == NULL || out == NULL || cap == 0) return -1;

    for (loops = 0; loops < 16 && !ctx->stopping; loops++)
    {
        FRAME_NODE *n = NULL;
        USHORT type;
        UINT ip_len;

        if (!rx_wait_pop(ctx, &n, loops == 0 ? timeout_ms : 0)) return 0;
        if (n == NULL) return 0;

        if (n->size >= 14 && n->data[12] == 0x08 && n->data[13] == 0x06)
        {
            reply_arp_if_needed(ctx, n->data, n->size);
            free_frame_node(n);
            continue;
        }

        if (n->size <= ETH_HLEN)
        {
            free_frame_node(n);
            continue;
        }

        type = ((USHORT)n->data[12] << 8) | n->data[13];
        if (type != ETH_P_IP && type != ETH_P_IPV6)
        {
            free_frame_node(n);
            continue;
        }

        ip_len = n->size - ETH_HLEN;
        if (ip_len > cap)
        {
            free_frame_node(n);
            return -1;
        }

        Copy(out, n->data + ETH_HLEN, ip_len);
        free_frame_node(n);

        ctx->rx_packets++;
        ctx->rx_bytes += ip_len;
        return (int)ip_len;
    }

    return 0;
}

/* ------------------------------------------------------------------------- */
/* Session helpers                                                           */
/* ------------------------------------------------------------------------- */

static int map_official_error(UINT e)
{
    switch (e)
    {
        case ERR_NO_ERROR:
            return YS_ERR_NONE;
        case ERR_CONNECT_FAILED:
            return YS_ERR_TCP_CONNECT;
        case ERR_SERVER_IS_NOT_VPN:
        case ERR_PROTOCOL_ERROR:
        case ERR_INVALID_PROTOCOL:
            return YS_ERR_PROTOCOL_VERSION;
        case ERR_AUTH_FAILED:
        case ERR_ACCESS_DENIED:
            return YS_ERR_AUTHENTICATION;
        case ERR_USER_CANCEL:
            return YS_ERR_SESSION;
        default:
            return YS_ERR_SESSION;
    }
}

static int physical_fd_from_sock(SOCK *s)
{
    if (s == NULL) return -1;

    if (s->IsRUDPSocket && s->R_UDP_Stack != NULL &&
        s->R_UDP_Stack->UdpSock != NULL)
    {
        return (int)s->R_UDP_Stack->UdpSock->socket;
    }

    return (int)s->socket;
}

static int collect_fds(OFFICIAL_CTX *ctx, int *fds, int maxfds, int rudp_only)
{
    SESSION *sess;
    CONNECTION *c;
    int count = 0;
    UINT i;

    if (ctx == NULL || fds == NULL || maxfds <= 0) return 0;
    sess = ctx->session;
    if (sess == NULL) return 0;
    c = sess->Connection;
    if (c == NULL) return 0;

    if (c->Tcp != NULL && c->Tcp->TcpSockList != NULL)
    {
        LockList(c->Tcp->TcpSockList);
        for (i = 0; i < LIST_NUM(c->Tcp->TcpSockList) && count < maxfds; i++)
        {
            TCPSOCK *ts = LIST_DATA(c->Tcp->TcpSockList, i);
            SOCK *s;
            int fd, dup = 0, j;

            if (ts == NULL || ts->Sock == NULL) continue;
            s = ts->Sock;
            if (rudp_only && !s->IsRUDPSocket) continue;
            if (!rudp_only && s->IsRUDPSocket) continue;

            fd = physical_fd_from_sock(s);
            if (fd < 0) continue;

            for (j = 0; j < count; j++) if (fds[j] == fd) dup = 1;
            if (!dup) fds[count++] = fd;
        }
        UnlockList(c->Tcp->TcpSockList);
    }

    if (count == 0 && c->FirstSock != NULL)
    {
        SOCK *s = c->FirstSock;
        if ((!rudp_only && !s->IsRUDPSocket) || (rudp_only && s->IsRUDPSocket))
        {
            int fd = physical_fd_from_sock(s);
            if (fd >= 0) fds[count++] = fd;
        }
    }

    return count;
}

static int native_state(OFFICIAL_CTX *ctx)
{
    SESSION *s;
    if (ctx == NULL) return YS_STATE_DISCONNECTED;
    if (ctx->stopping) return YS_STATE_DISCONNECTING;

    s = ctx->session;
    if (s == NULL) return ctx->state;

    switch (s->ClientStatus)
    {
        case CLIENT_STATUS_CONNECTING:
            return YS_STATE_CONNECTING;
        case CLIENT_STATUS_NEGOTIATION:
            return YS_STATE_PROTOCOL_HANDSHAKE;
        case CLIENT_STATUS_AUTH:
            return YS_STATE_AUTHENTICATING;
        case CLIENT_STATUS_ESTABLISHED:
            return ctx->adapter_active ? YS_STATE_CONNECTED : YS_STATE_SESSION_SETUP;
        case CLIENT_STATUS_RETRY:
            return YS_STATE_CONNECTING;
        case CLIENT_STATUS_IDLE:
        default:
            return ctx->state;
    }
}

static void stop_official_session(OFFICIAL_CTX *ctx)
{
    SESSION *s = NULL;

    if (ctx == NULL) return;
    ctx->stopping = 1;
    ctx->state = YS_STATE_DISCONNECTING;

    pthread_mutex_lock(&ctx->lock);
    s = ctx->session;
    pthread_cond_broadcast(&ctx->rx_cond);
    pthread_mutex_unlock(&ctx->lock);

    if (ctx->adapter_cancel != NULL) Cancel(ctx->adapter_cancel);

    if (s != NULL)
    {
        StopSession(s);
        pthread_mutex_lock(&ctx->lock);
        if (ctx->session == s) ctx->session = NULL;
        pthread_mutex_unlock(&ctx->lock);
        ReleaseSession(s);
    }

    ctx->adapter_active = 0;
    ctx->state = YS_STATE_DISCONNECTED;
}

/* ------------------------------------------------------------------------- */
/* JNI                                                                       */
/* ------------------------------------------------------------------------- */

int YinglongDnsInit(JavaVM *vm, JNIEnv *env);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    (void)reserved;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK ||
        !YinglongDnsInit(vm, env)) return JNI_ERR;
    ensure_official_init();
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeCreate(
        JNIEnv *env, jobject thiz)
{
    OFFICIAL_CTX *ctx;
    (void)env; (void)thiz;

    ensure_official_init();

    ctx = (OFFICIAL_CTX *)calloc(1, sizeof(OFFICIAL_CTX));
    if (ctx == NULL) return 0;

    pthread_mutex_init(&ctx->lock, NULL);
    pthread_cond_init(&ctx->rx_cond, NULL);

    ctx->timeout_ms = 15000;
    ctx->forced_auth_type = -1;
    ctx->max_connection = 1;
    ctx->half_connection = 0;
    ctx->state = YS_STATE_DISCONNECTED;
    ctx->adapter_cancel = NewCancel();

    /*
     * The official desktop vpnclient creates a CLIENT before sessions.
     * NewClientSession()/ClientThread use client-side globals initialized by
     * CiNewClient(), including the active-session lock/counters.
     */
    ctx->client = CiNewClient();
    ctx->cedar = (ctx->client != NULL) ? ctx->client->Cedar : NULL;

    if (ctx->adapter_cancel == NULL || ctx->client == NULL || ctx->cedar == NULL)
    {
        if (ctx->adapter_cancel != NULL) ReleaseCancel(ctx->adapter_cancel);
        if (ctx->client != NULL) CtReleaseClient(ctx->client);
        ctx->client = NULL;
        ctx->cedar = NULL;
        pthread_cond_destroy(&ctx->rx_cond);
        pthread_mutex_destroy(&ctx->lock);
        free(ctx);
        return 0;
    }

    Rand(ctx->client_mac, 6);
    ctx->client_mac[0] = (ctx->client_mac[0] | 0x02) & 0xFE;

    LOGI("nativeCreate official Cedar context=%p", ctx);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    (void)env; (void)thiz;
    if (ctx == NULL) return;

    stop_official_session(ctx);

    pthread_mutex_lock(&ctx->lock);
    clear_queue(&ctx->tx_head, &ctx->tx_tail);
    clear_queue(&ctx->rx_head, &ctx->rx_tail);
    pthread_mutex_unlock(&ctx->lock);

    if (ctx->adapter_cancel != NULL)
    {
        ReleaseCancel(ctx->adapter_cancel);
        ctx->adapter_cancel = NULL;
    }

    if (ctx->client != NULL)
    {
        CtReleaseClient(ctx->client);
        ctx->client = NULL;
        ctx->cedar = NULL;
    }

    pthread_cond_destroy(&ctx->rx_cond);
    pthread_mutex_destroy(&ctx->lock);
    free(ctx);
}

JNIEXPORT jstring JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetLastError(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    (void)thiz;
    return (*env)->NewStringUTF(env, ctx == NULL ? "No native context" : ctx->last_error);
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnect(
        JNIEnv *env, jobject thiz, jlong handle,
        jstring host, jint port, jstring username, jstring password);

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnectWithHub(
        JNIEnv *env, jobject thiz, jlong handle,
        jstring jhost, jint port, jstring juser, jstring jpass,
        jstring jhub, jboolean use_tcp,
        jstring client_product_name, jstring client_version, jint client_build,
        jstring client_os_name, jstring client_os_version, jstring client_os_product_id,
        jstring client_host_name, jstring client_ip, jint client_port,
        jstring server_host_name, jstring server_ip, jint server_port)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    const char *host = NULL, *user = NULL, *pass = NULL, *hub = NULL;
    CLIENT_OPTION o;
    CLIENT_AUTH a;
    PACKET_ADAPTER *pa;
    SESSION *s;
    UINT64 started;
    int result = YS_ERR_UNKNOWN;

    (void)thiz;
    (void)client_product_name; (void)client_version; (void)client_build;
    (void)client_os_name; (void)client_os_version; (void)client_os_product_id;
    (void)client_host_name; (void)client_ip; (void)client_port;
    (void)server_host_name; (void)server_ip; (void)server_port;

    if (ctx == NULL || jhost == NULL || juser == NULL || jpass == NULL || jhub == NULL)
        return YS_ERR_UNKNOWN;

    host = (*env)->GetStringUTFChars(env, jhost, NULL);
    user = (*env)->GetStringUTFChars(env, juser, NULL);
    pass = (*env)->GetStringUTFChars(env, jpass, NULL);
    hub  = (*env)->GetStringUTFChars(env, jhub, NULL);

    if (host == NULL || user == NULL || pass == NULL || hub == NULL)
        goto CLEANUP_STRINGS;

    ctx->stopping = 0;
    ctx->last_error[0] = 0;
    ctx->adapter_active = 0;
    ctx->state = YS_STATE_CONNECTING;
    ctx->gateway_mac_valid = 0;

    Zero(&o, sizeof(o));
    StrCpy(o.Hostname, sizeof(o.Hostname), (char *)host);
    o.Port = (port > 0) ? (UINT)port : 443;
    o.PortUDP = (ctx->udp_only && ctx->udp_port > 0) ? (UINT)ctx->udp_port : 0;
    o.ProxyType = PROXY_DIRECT;
    o.NumRetry = 0;
    o.RetryInterval = 1;
    StrCpy(o.HubName, sizeof(o.HubName), (char *)hub);
    o.MaxConnection = 1; /* MVP: one underlay socket, easy to protect on Android. */
    o.UseEncrypt = true;
    o.UseCompress = false;
    o.HalfConnection = false;
    o.NoRoutingTracking = true;
    StrCpy(o.DeviceName, sizeof(o.DeviceName), "ANDROID_VPNSERVICE");
    o.AdditionalConnectionInterval = 1;
    o.DisableQoS = true;
    o.NoTls1 = false;
    o.NoUdpAcceleration = true;

    /* If direct TCP fails, official ClientConnectGetSocket/TcpIpConnectEx
       automatically races the official NAT-T/R-UDP fallback. */
    if (!use_tcp && ctx->udp_port > 0)
        o.PortUDP = (UINT)ctx->udp_port;

    Zero(&a, sizeof(a));
    StrCpy(a.Username, sizeof(a.Username), (char *)user);

    if (ctx->forced_auth_type == CLIENT_AUTHTYPE_ANONYMOUS)
    {
        a.AuthType = CLIENT_AUTHTYPE_ANONYMOUS;
    }
    else if (ctx->forced_auth_type == CLIENT_AUTHTYPE_PLAIN_PASSWORD)
    {
        a.AuthType = CLIENT_AUTHTYPE_PLAIN_PASSWORD;
        StrCpy(a.PlainPassword, sizeof(a.PlainPassword), (char *)pass);
    }
    else
    {
        a.AuthType = CLIENT_AUTHTYPE_PASSWORD;
        HashPassword(a.HashedPassword, a.Username, (char *)pass);
    }
    a.CheckCertProc = NULL;
    a.SecureSignProc = NULL;

    pa = NewPacketAdapter(AndroidPaInit, AndroidPaGetCancel,
                          AndroidPaGetNextPacket, AndroidPaPutPacket,
                          AndroidPaFree);
    if (pa == NULL)
    {
        result = YS_ERR_UNKNOWN;
        goto CLEANUP_STRINGS;
    }
    pa->Param = ctx;

    LOGI("official connect host=%s port=%u hub=%s user=%s auth=%u",
         host, o.Port, hub, user, a.AuthType);

    s = NewClientSession(ctx->cedar, &o, &a, pa);
    if (s == NULL)
    {
        FreePacketAdapter(pa);
        result = YS_ERR_SESSION;
        goto CLEANUP_STRINGS;
    }

    pthread_mutex_lock(&ctx->lock);
    ctx->session = s;
    pthread_mutex_unlock(&ctx->lock);

    started = Tick64();
    while (!ctx->stopping)
    {
        int st = s->ClientStatus;
        ctx->state = native_state(ctx);

        if (st == CLIENT_STATUS_ESTABLISHED && s->ConnectSucceed && ctx->adapter_active)
        {
            ctx->state = YS_STATE_CONNECTED;
            LOGI("official SoftEther session established underlay=%s",
                 (s->Connection && s->Connection->FirstSock)
                    ? s->Connection->FirstSock->UnderlayProtocol : "session");
            result = YS_ERR_NONE;
            goto CLEANUP_STRINGS;
        }

        if (st == CLIENT_STATUS_IDLE)
        {
            UINT err = s->Err;
            LOGE("official session stopped during connect err=%u", err);
            snprintf(ctx->last_error, sizeof(ctx->last_error),
                     "Cedar error=%u status=%d elapsedMs=%llu", err, st,
                     (unsigned long long)(Tick64() - started));
            result = (err == ERR_NO_ERROR) ? YS_ERR_SESSION : map_official_error(err);
            break;
        }

        if ((int)(Tick64() - started) >= ctx->timeout_ms)
        {
            LOGE("official connect watchdog timeout after %d ms", ctx->timeout_ms);
            snprintf(ctx->last_error, sizeof(ctx->last_error),
                     "Cedar watchdog=%dms status=%d error=%u", ctx->timeout_ms, st, s->Err);
            result = YS_ERR_TIMEOUT;
            break;
        }

        SleepThread(50);
    }

    if (ctx->session != NULL)
    {
        stop_official_session(ctx);
    }

CLEANUP_STRINGS:
    if (host != NULL) (*env)->ReleaseStringUTFChars(env, jhost, host);
    if (user != NULL) (*env)->ReleaseStringUTFChars(env, juser, user);
    if (pass != NULL) (*env)->ReleaseStringUTFChars(env, jpass, pass);
    if (hub  != NULL) (*env)->ReleaseStringUTFChars(env, jhub, hub);
    return result;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnect(
        JNIEnv *env, jobject thiz, jlong handle,
        jstring host, jint port, jstring username, jstring password)
{
    jstring hub = (*env)->NewStringUTF(env, "VPNGATE");
    jstring empty = (*env)->NewStringUTF(env, "");
    jint ret = Java_vn_unlimit_softether_client_SoftEtherClient_nativeConnectWithHub(
        env, thiz, handle, host, port, username, password, hub, JNI_TRUE,
        empty, empty, 0, empty, empty, empty, empty, empty, 0, empty, empty, port);
    (*env)->DeleteLocalRef(env, hub);
    (*env)->DeleteLocalRef(env, empty);
    return ret;
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeDisconnect(
        JNIEnv *env, jobject thiz, jlong handle)
{
    (void)env; (void)thiz;
    stop_official_session((OFFICIAL_CTX *)(intptr_t)handle);
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetState(
        JNIEnv *env, jobject thiz, jlong handle)
{
    (void)env; (void)thiz;
    return native_state((OFFICIAL_CTX *)(intptr_t)handle);
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetOption(
        JNIEnv *env, jobject thiz, jlong handle, jint option, jlong value)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    (void)env; (void)thiz;
    if (ctx == NULL) return;

    switch (option)
    {
        case OPT_TIMEOUT:
            ctx->timeout_ms = (int)value;
            if (ctx->timeout_ms < 3000) ctx->timeout_ms = 3000;
            break;
        case OPT_UDP_PORT:
            ctx->udp_port = (int)value;
            break;
        case OPT_UDP_ONLY:
            ctx->udp_only = value ? 1 : 0;
            break;
    }
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetAuthType(
        JNIEnv *env, jobject thiz, jlong handle, jint auth_type)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    (void)env; (void)thiz;
    if (ctx != NULL) ctx->forced_auth_type = (int)auth_type;
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetMaxConnection(
        JNIEnv *env, jobject thiz, jlong handle, jint max_connections)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    (void)env; (void)thiz;
    if (ctx != NULL) ctx->max_connection = 1; /* Android MVP intentionally clamps to 1. */
    (void)max_connections;
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetHalfConnection(
        JNIEnv *env, jobject thiz, jlong handle, jboolean half_connection)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    (void)env; (void)thiz;
    if (ctx != NULL) ctx->half_connection = half_connection ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNumConnections(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    int fds[8];
    (void)env; (void)thiz;
    if (ctx == NULL || ctx->state != YS_STATE_CONNECTED) return 0;
    return collect_fds(ctx, fds, 8, 0) + collect_fds(ctx, fds, 8, 1);
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetSocketFd(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    int fds[8];
    int n;
    (void)env; (void)thiz;
    n = collect_fds(ctx, fds, 8, 0);
    return n > 0 ? fds[0] : -1;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetRudpSocketFd(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    int fds[8];
    int n;
    (void)env; (void)thiz;
    n = collect_fds(ctx, fds, 8, 1);
    return n > 0 ? fds[0] : -1;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetNatTUdpSocketFd(
        JNIEnv *env, jobject thiz, jlong handle)
{
    return Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetRudpSocketFd(
        env, thiz, handle);
}

JNIEXPORT jintArray JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetAllSocketFds(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    int direct[8], rudp[8], all[16];
    int nd, nr, n = 0, i, j;
    jintArray arr;
    (void)thiz;

    nd = collect_fds(ctx, direct, 8, 0);
    nr = collect_fds(ctx, rudp, 8, 1);

    for (i = 0; i < nd && n < 16; i++) all[n++] = direct[i];
    for (i = 0; i < nr && n < 16; i++)
    {
        int dup = 0;
        for (j = 0; j < n; j++) if (all[j] == rudp[i]) dup = 1;
        if (!dup) all[n++] = rudp[i];
    }

    arr = (*env)->NewIntArray(env, n);
    if (arr != NULL && n > 0)
        (*env)->SetIntArrayRegion(env, arr, 0, n, (const jint *)all);
    return arr;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSend(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    jsize alen;
    UCHAR *tmp;
    int ret;
    (void)thiz;

    if (ctx == NULL || data == NULL || length <= 0) return -1;
    alen = (*env)->GetArrayLength(env, data);
    if (length > alen) length = alen;

    tmp = (UCHAR *)malloc((size_t)length);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)tmp);
    ret = send_ip_packet(ctx, tmp, (UINT)length);
    free(tmp);
    return ret;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSendSlice(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray data,
        jint offset, jint length)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    jsize alen;
    UCHAR *tmp;
    int ret;
    (void)thiz;

    if (ctx == NULL || data == NULL || offset < 0 || length <= 0) return -1;
    alen = (*env)->GetArrayLength(env, data);
    if (offset + length > alen) return -1;

    tmp = (UCHAR *)malloc((size_t)length);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, data, offset, length, (jbyte *)tmp);
    ret = send_ip_packet(ctx, tmp, (UINT)length);
    free(tmp);
    return ret;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceive(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint max_length)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    UCHAR *tmp;
    jsize alen;
    int ret;
    (void)thiz;

    if (ctx == NULL || buffer == NULL || max_length <= 0) return -1;
    alen = (*env)->GetArrayLength(env, buffer);
    if (max_length > alen) max_length = alen;

    tmp = (UCHAR *)malloc((size_t)max_length);
    if (tmp == NULL) return -1;
    ret = recv_ip_packet(ctx, tmp, (UINT)max_length, 100);
    if (ret > 0) (*env)->SetByteArrayRegion(env, buffer, 0, ret, (jbyte *)tmp);
    free(tmp);
    return ret;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeReceiveBatch(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer,
        jint max_length, jintArray lengths, jint max_packets)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    UCHAR *tmp;
    jint *lens;
    jsize bcap, lcap;
    int total = 0, count = 0;
    (void)thiz;

    if (ctx == NULL || buffer == NULL || lengths == NULL ||
        max_length <= 0 || max_packets <= 0) return -1;

    bcap = (*env)->GetArrayLength(env, buffer);
    lcap = (*env)->GetArrayLength(env, lengths);
    if (max_length > bcap) max_length = bcap;
    if (max_packets > lcap) max_packets = lcap;

    tmp = (UCHAR *)malloc((size_t)max_length);
    lens = (jint *)calloc((size_t)max_packets, sizeof(jint));
    if (tmp == NULL || lens == NULL)
    {
        free(tmp); free(lens);
        return -1;
    }

    while (count < max_packets && total < max_length)
    {
        int n = recv_ip_packet(ctx, tmp + total, (UINT)(max_length - total),
                               count == 0 ? 100 : 0);
        if (n < 0)
        {
            if (count == 0) total = -1;
            break;
        }
        if (n == 0) break;
        lens[count++] = n;
        total += n;
    }

    if (total > 0)
        (*env)->SetByteArrayRegion(env, buffer, 0, total, (jbyte *)tmp);
    (*env)->SetIntArrayRegion(env, lengths, 0, max_packets, lens);

    free(tmp);
    free(lens);
    return total;
}

JNIEXPORT jintArray JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeDoDhcp(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    UCHAR frame[640];
    UINT xid, server_id = 0;
    UINT assigned = 0, mask = 0, gateway = 0, dns1 = 0, dns2 = 0, lease = 0;
    int len, msg = 0, retry;
    jint values[7];
    jintArray arr;
    (void)thiz;

    if (ctx == NULL || !ctx->adapter_active) return NULL;

    xid = Rand32();

    for (retry = 0; retry < 3 && !ctx->stopping; retry++)
    {
        len = build_dhcp_frame(frame, sizeof(frame), ctx->client_mac,
                               xid, DHCP_DISCOVER, 0, 0);
        if (len <= 0 || !tx_copy_frame(ctx, frame, (UINT)len)) continue;

        msg = wait_dhcp(ctx, xid, 5000, &assigned, &mask, &gateway,
                        &dns1, &dns2, &lease, &server_id);
        if (msg != DHCP_OFFER) continue;

        len = build_dhcp_frame(frame, sizeof(frame), ctx->client_mac,
                               xid, DHCP_REQUEST, assigned, server_id);
        if (len <= 0 || !tx_copy_frame(ctx, frame, (UINT)len)) continue;

        msg = wait_dhcp(ctx, xid, 5000, &assigned, &mask, &gateway,
                        &dns1, &dns2, &lease, &server_id);
        if (msg == DHCP_ACK) break;
        if (msg == DHCP_NAK) return NULL;
    }

    if (msg != DHCP_ACK || assigned == 0)
    {
        LOGE("DHCP failed over official Cedar L2 session");
        return NULL;
    }

    ctx->assigned_ip = assigned;
    ctx->subnet_mask = mask;
    ctx->gateway_ip = gateway;
    ctx->dns1 = dns1;
    ctx->dns2 = dns2;
    ctx->lease_time = lease;

    send_gratuitous_arp(ctx);
    resolve_gateway_mac(ctx);

    values[0] = 1;
    values[1] = (jint)assigned;
    values[2] = (jint)mask;
    values[3] = (jint)gateway;
    values[4] = (jint)dns1;
    values[5] = (jint)dns2;
    values[6] = (jint)lease;

    arr = (*env)->NewIntArray(env, 7);
    if (arr != NULL) (*env)->SetIntArrayRegion(env, arr, 0, 7, values);

    LOGI("DHCP official-core success ip=%u.%u.%u.%u",
         (assigned >> 24) & 255, (assigned >> 16) & 255,
         (assigned >> 8) & 255, assigned & 255);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetStats(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    jlong v[9];
    jlongArray arr;
    (void)thiz;

    if (ctx == NULL) return NULL;
    Zero(v, sizeof(v));
    v[0] = (jlong)ctx->tx_packets;
    v[1] = (jlong)ctx->tx_bytes;
    v[2] = (jlong)ctx->rx_packets;
    v[3] = (jlong)ctx->rx_bytes;

    arr = (*env)->NewLongArray(env, 9);
    if (arr != NULL) (*env)->SetLongArrayRegion(env, arr, 0, 9, v);
    return arr;
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeForceCloseSocket(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    SESSION *s;
    (void)env; (void)thiz;
    if (ctx == NULL) return;

    ctx->stopping = 1;
    s = ctx->session;
    if (s != NULL) StopSessionEx(s, true);
    wake_session(ctx);
    pthread_mutex_lock(&ctx->lock);
    pthread_cond_broadcast(&ctx->rx_cond);
    pthread_mutex_unlock(&ctx->lock);
}

JNIEXPORT void JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSetClientMac(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray mac)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    jsize n;
    (void)thiz;
    if (ctx == NULL || mac == NULL) return;
    n = (*env)->GetArrayLength(env, mac);
    if (n != 6) return;
    (*env)->GetByteArrayRegion(env, mac, 0, 6, (jbyte *)ctx->client_mac);
    ctx->client_mac[0] = (ctx->client_mac[0] | 0x02) & 0xFE;
}

JNIEXPORT jbyteArray JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeGetClientMac(
        JNIEnv *env, jobject thiz, jlong handle)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    jbyteArray arr;
    (void)thiz;
    if (ctx == NULL) return NULL;
    arr = (*env)->NewByteArray(env, 6);
    if (arr != NULL) (*env)->SetByteArrayRegion(env, arr, 0, 6, (jbyte *)ctx->client_mac);
    return arr;
}

JNIEXPORT jint JNICALL
Java_vn_unlimit_softether_client_SoftEtherClient_nativeSendRaw(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint length)
{
    OFFICIAL_CTX *ctx = (OFFICIAL_CTX *)(intptr_t)handle;
    UCHAR *tmp;
    jsize n;
    int ok;
    (void)thiz;
    if (ctx == NULL || data == NULL || length <= 0) return -1;
    n = (*env)->GetArrayLength(env, data);
    if (length > n) length = n;

    tmp = (UCHAR *)malloc((size_t)length);
    if (tmp == NULL) return -1;
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)tmp);
    ok = tx_copy_frame(ctx, tmp, (UINT)length);
    free(tmp);
    return ok ? length : -1;
}
