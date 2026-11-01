#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <stdatomic.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <android/log.h>

#include "lwip/init.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcp.h"
#include "lwip/udp.h"
#include "lwip/ethip6.h"
#include "lwip/timeouts.h"
#include "netif/ethernet.h"

#define TAG "xcertplay-lwip"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_RELAY_CONNS 32
#define MAX_UDP_RELAYS 32
#define BUFFER_SIZE 16384

// NO_SYS lwIP owns process-global heaps, PCB lists and cyclic timers.
// Reinitializing those globals for another USB session corrupts next_timeout.
static pthread_once_t lwip_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t lwip_core_lock = PTHREAD_MUTEX_INITIALIZER;
static void initialize_lwip_once(void) { lwip_init(); }

typedef struct {
    int active;
    struct tcp_pcb *lwip_pcb;
    int loopback_fd;
    uint8_t loopback_to_lwip_buf[BUFFER_SIZE];
    int loopback_to_lwip_len;
    uint8_t lwip_to_loopback_buf[BUFFER_SIZE];
    int lwip_to_loopback_len;
} TcpRelayConn;

typedef struct {
    int active;
    int port;
    struct udp_pcb *lwip_pcb;
    int loopback_fd;
    ip_addr_t peer_ip;
    u16_t peer_port;
    int has_peer;
} UdpRelay;

typedef struct {
    int active;
    int port;
    int is_tcp;
} PortMapping;

#define MAX_PORT_MAPPINGS 32

typedef struct {
    JavaVM *jvm;
    jobject bridge_obj_global;
    jmethodID on_tx_frame_mid;

    struct netif ncm_netif;
    uint8_t host_mac[6];
    ip6_addr_t host_ip6;

    struct tcp_pcb *listen_pcbs[MAX_PORT_MAPPINGS];
    int num_listen_pcbs;

    PortMapping port_mappings[MAX_PORT_MAPPINGS];
    int num_port_mappings;

    TcpRelayConn tcp_conns[MAX_RELAY_CONNS];
    UdpRelay udp_relays[MAX_UDP_RELAYS];

    pthread_t thread;
    atomic_int running;
    int wake_pipe[2];

    uint32_t stats_rx_frames;
    uint32_t stats_rx_bytes;
    uint32_t stats_rx_tcp;
    uint32_t stats_rx_tcp_syn;
    uint32_t stats_rx_udp;
    uint32_t stats_rx_icmp6;
    uint32_t stats_rx_drops;
    uint32_t stats_tx_frames;
    uint32_t stats_tx_bytes;
    uint32_t stats_tcp_conns_accepted;
    uint32_t stats_tcp_conns_closed;
} LwipContext;

static err_t ncm_netif_output_ip6(struct netif *netif, struct pbuf *p, const ip6_addr_t *ip6addr) {
    return ethip6_output(netif, p, ip6addr);
}

static err_t ncm_linkoutput(struct netif *netif, struct pbuf *p) {
    LwipContext *ctx = (LwipContext *)netif->state;
    if (ctx == NULL || !ctx->running) return ERR_OK;

    int total_len = p->tot_len;
    if (total_len <= 0 || total_len > 65535) return ERR_VAL;

    ctx->stats_tx_frames++;
    ctx->stats_tx_bytes += (uint32_t)total_len;

    JNIEnv *env = NULL;
    int attached = 0;
    if ((*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != 0) {
            return ERR_VAL;
        }
        attached = 1;
    }

    jbyteArray jframe = (*env)->NewByteArray(env, total_len);
    if (jframe != NULL) {
        jbyte *buf = (*env)->GetByteArrayElements(env, jframe, NULL);
        if (buf != NULL) {
            pbuf_copy_partial(p, buf, total_len, 0);
            uint8_t header[21] = {0};
            memcpy(header, buf, total_len < 21 ? (size_t)total_len : sizeof(header));
            (*env)->ReleaseByteArrayElements(env, jframe, buf, 0);

            LOGI("ncm_linkoutput sending frame len=%d proto=0x%02x%02x next=0x%02x", 
                 total_len, (unsigned char)header[12], (unsigned char)header[13],
                 total_len >= 21 ? (unsigned char)header[20] : 0);
            (*env)->CallVoidMethod(env, ctx->bridge_obj_global, ctx->on_tx_frame_mid, jframe);
        }
        (*env)->DeleteLocalRef(env, jframe);
    }

    if (attached) {
        (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    }
    return ERR_OK;
}

static err_t ncm_netif_init(struct netif *netif) {
    LwipContext *ctx = (LwipContext *)netif->state;
    netif->name[0] = 'n';
    netif->name[1] = 'c';
    netif->output_ip6 = ncm_netif_output_ip6;
    netif->linkoutput = ncm_linkoutput;
    netif->mtu = 1500;
    netif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHARP | NETIF_FLAG_ETHERNET | NETIF_FLAG_UP | NETIF_FLAG_LINK_UP;
    netif->hwaddr_len = 6;
    memcpy(netif->hwaddr, ctx->host_mac, 6);
    return ERR_OK;
}

static void close_tcp_relay_conn(LwipContext *ctx, TcpRelayConn *conn) {
    if (!conn->active) return;
    conn->active = 0;
    ctx->stats_tcp_conns_closed++;
    if (conn->loopback_fd >= 0) {
        close(conn->loopback_fd);
        conn->loopback_fd = -1;
    }
    if (conn->lwip_pcb != NULL) {
        tcp_arg(conn->lwip_pcb, NULL);
        tcp_recv(conn->lwip_pcb, NULL);
        tcp_sent(conn->lwip_pcb, NULL);
        tcp_err(conn->lwip_pcb, NULL);
        tcp_poll(conn->lwip_pcb, NULL, 0);
        // The local relay has ended. Do not leave a closing PCB referencing
        // an obsolete USB session or consuming the global PCB pool on retry.
        tcp_abort(conn->lwip_pcb);
        conn->lwip_pcb = NULL;
    }
    conn->loopback_to_lwip_len = 0;
    conn->lwip_to_loopback_len = 0;
}

static void close_udp_relay(LwipContext *ctx, UdpRelay *relay) {
    (void)ctx;
    if (!relay->active) return;
    relay->active = 0;
    if (relay->loopback_fd >= 0) {
        close(relay->loopback_fd);
        relay->loopback_fd = -1;
    }
    if (relay->lwip_pcb != NULL) {
        udp_recv(relay->lwip_pcb, NULL, NULL);
        udp_remove(relay->lwip_pcb);
        relay->lwip_pcb = NULL;
    }
    relay->has_peer = 0;
    relay->port = 0;
}

static void udp_relay_recv(void *arg, struct udp_pcb *upcb, struct pbuf *p,
                           const ip_addr_t *addr, u16_t port) {
    (void)upcb;
    if (p == NULL) return;

    UdpRelay *relay = (UdpRelay *)arg;
    if (relay == NULL || !relay->active || relay->loopback_fd < 0) {
        pbuf_free(p);
        return;
    }

    if (addr != NULL) {
        ip_addr_copy(relay->peer_ip, *addr);
        relay->peer_port = port;
        relay->has_peer = 1;
    }

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = htons((uint16_t)relay->port);
    sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    int tot_len = p->tot_len;
    if (tot_len > 0) {
        if (p->next == NULL) {
            sendto(relay->loopback_fd, p->payload, (size_t)p->len, 0,
                   (struct sockaddr *)&sin, sizeof(sin));
        } else {
            uint8_t stack_buf[2048];
            uint8_t *buf = stack_buf;
            if (tot_len > (int)sizeof(stack_buf)) {
                buf = (uint8_t *)malloc((size_t)tot_len);
            }
            if (buf != NULL) {
                pbuf_copy_partial(p, buf, (u16_t)tot_len, 0);
                sendto(relay->loopback_fd, buf, (size_t)tot_len, 0,
                       (struct sockaddr *)&sin, sizeof(sin));
                if (buf != stack_buf) {
                    free(buf);
                }
            }
        }
    }

    pbuf_free(p);
}

static void tcp_relay_err(void *arg, err_t err) {
    (void)err;
    TcpRelayConn *conn = (TcpRelayConn *)arg;
    if (conn != NULL) {
        conn->lwip_pcb = NULL; // PCB is already freed by lwIP before err callback
        if (conn->loopback_fd >= 0) {
            close(conn->loopback_fd);
            conn->loopback_fd = -1;
        }
        conn->active = 0;
    }
}

static void flush_loopback_to_lwip(TcpRelayConn *conn) {
    if (conn == NULL || !conn->active || conn->lwip_pcb == NULL) return;
    if (conn->loopback_to_lwip_len > 0) {
        u16_t snd_buf = tcp_sndbuf(conn->lwip_pcb);
        if (snd_buf > 0) {
            u16_t to_send = (u16_t)conn->loopback_to_lwip_len;
            if (to_send > snd_buf) {
                to_send = snd_buf;
            }
            err_t wr_err = tcp_write(conn->lwip_pcb, conn->loopback_to_lwip_buf, to_send, TCP_WRITE_FLAG_COPY);
            if (wr_err == ERR_OK) {
                tcp_output(conn->lwip_pcb);
                conn->loopback_to_lwip_len -= (int)to_send;
                if (conn->loopback_to_lwip_len > 0) {
                    memmove(conn->loopback_to_lwip_buf, conn->loopback_to_lwip_buf + to_send, (size_t)conn->loopback_to_lwip_len);
                }
            } else if (wr_err != ERR_MEM) {
                LOGW("tcp_write failed: %d", wr_err);
            }
        }
    }
}

static err_t tcp_relay_sent(void *arg, struct tcp_pcb *tpcb, u16_t len) {
    (void)tpcb;
    (void)len;
    TcpRelayConn *conn = (TcpRelayConn *)arg;
    if (conn != NULL && conn->active) {
        flush_loopback_to_lwip(conn);
    }
    return ERR_OK;
}

static err_t tcp_relay_recv(void *arg, struct tcp_pcb *tpcb, struct pbuf *p, err_t err) {
    (void)tpcb;
    (void)err;
    TcpRelayConn *conn = (TcpRelayConn *)arg;
    if (conn == NULL || !conn->active) {
        if (p != NULL) pbuf_free(p);
        return ERR_OK;
    }

    if (p == NULL) {
        // Peer closed connection
        if (conn->loopback_fd >= 0) {
            shutdown(conn->loopback_fd, SHUT_WR);
        }
        return ERR_OK;
    }

    // Never wait for a slow local reader while holding the global NO_SYS core
    // lock. Keep bounded data and let the worker forward it nonblockingly.
    if (p->tot_len > BUFFER_SIZE - conn->lwip_to_loopback_len) return ERR_MEM;
    pbuf_copy_partial(p, conn->lwip_to_loopback_buf + conn->lwip_to_loopback_len, p->tot_len, 0);
    conn->lwip_to_loopback_len += p->tot_len;
    pbuf_free(p);
    return ERR_OK;
}

static err_t tcp_relay_accept(void *arg, struct tcp_pcb *newpcb, err_t err) {
    (void)err;
    LwipContext *ctx = (LwipContext *)arg;
    if (ctx == NULL || newpcb == NULL) return ERR_VAL;

    int port = newpcb->local_port;
    LOGI("lwIP accepted TCP connection on port %d", port);

    TcpRelayConn *conn = NULL;
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        if (!ctx->tcp_conns[i].active) {
            conn = &ctx->tcp_conns[i];
            break;
        }
    }

    if (conn == NULL) {
        LOGE("No free relay slots for incoming TCP connection on port %d", port);
        return ERR_MEM;
    }

    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("Failed to create loopback socket: %d", errno);
        return ERR_CLSD;
    }

    struct sockaddr_in sin;
    memset(&sin, 0, sizeof(sin));
    sin.sin_family = AF_INET;
    sin.sin_port = htons((uint16_t)port);
    sin.sin_addr.s_addr = inet_addr("127.0.0.1");

    int res = connect(fd, (struct sockaddr *)&sin, sizeof(sin));
    if (res < 0) {
        LOGE("Failed to connect to 127.0.0.1:%d, errno=%d", port, errno);
        close(fd);
        return ERR_CONN;
    }

    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    conn->active = 1;
    conn->lwip_pcb = newpcb;
    conn->loopback_fd = fd;
    conn->loopback_to_lwip_len = 0;
    conn->lwip_to_loopback_len = 0;
    ctx->stats_tcp_conns_accepted++;

    tcp_arg(newpcb, conn);
    tcp_recv(newpcb, tcp_relay_recv);
    tcp_err(newpcb, tcp_relay_err);
    tcp_sent(newpcb, tcp_relay_sent);

    return ERR_OK;
}

static void check_and_forward_loopback(LwipContext *ctx) {
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        TcpRelayConn *conn = &ctx->tcp_conns[i];
        if (!conn->active || conn->loopback_fd < 0 || conn->lwip_pcb == NULL) continue;

        while (conn->lwip_to_loopback_len > 0) {
            ssize_t sent = send(conn->loopback_fd, conn->lwip_to_loopback_buf,
                                (size_t)conn->lwip_to_loopback_len, MSG_NOSIGNAL);
            if (sent > 0) {
                conn->lwip_to_loopback_len -= (int)sent;
                memmove(conn->lwip_to_loopback_buf, conn->lwip_to_loopback_buf + sent,
                        (size_t)conn->lwip_to_loopback_len);
                tcp_recved(conn->lwip_pcb, (u16_t)sent);
            } else if (sent < 0 && errno == EINTR) {
                continue;
            } else if (sent < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                break;
            } else {
                close_tcp_relay_conn(ctx, conn);
                break;
            }
        }
        if (!conn->active) continue;
        flush_loopback_to_lwip(conn);

        int space = BUFFER_SIZE - conn->loopback_to_lwip_len;
        if (space > 0) {
            ssize_t n = read(conn->loopback_fd, conn->loopback_to_lwip_buf + conn->loopback_to_lwip_len, (size_t)space);
            if (n > 0) {
                conn->loopback_to_lwip_len += (int)n;
                flush_loopback_to_lwip(conn);
            } else if (n == 0) {
                LOGI("loopback_fd EOF for port %d", conn->lwip_pcb ? conn->lwip_pcb->local_port : 0);
                close_tcp_relay_conn(ctx, conn);
            } else if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                LOGW("loopback_fd read error %d", errno);
                close_tcp_relay_conn(ctx, conn);
            }
        }
    }
}

static void check_and_forward_udp_loopback(LwipContext *ctx) {
    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        UdpRelay *relay = &ctx->udp_relays[i];
        if (!relay->active || relay->loopback_fd < 0 || relay->lwip_pcb == NULL) continue;
        if (!relay->has_peer) continue;

        uint8_t buf[2048];
        while (1) {
            ssize_t n = recvfrom(relay->loopback_fd, buf, sizeof(buf), 0, NULL, NULL);
            if (n > 0) {
                struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (u16_t)n, PBUF_RAM);
                if (p != NULL) {
                    pbuf_take(p, buf, (u16_t)n);
                    err_t err = udp_sendto(relay->lwip_pcb, p, &relay->peer_ip, relay->peer_port);
                    if (err != ERR_OK) {
                        LOGW("udp_sendto port %d failed: %d", relay->port, err);
                    }
                    pbuf_free(p);
                } else {
                    LOGW("pbuf_alloc failed for udp forwarding on port %d, len=%zd", relay->port, n);
                }
            } else {
                if (n < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                    LOGW("loopback_fd udp recvfrom error %d for port %d", errno, relay->port);
                }
                break;
            }
        }
    }
}

static void register_listeners_locked(LwipContext *ctx) {
    for (int m = 0; m < ctx->num_port_mappings; m++) {
        if (!ctx->port_mappings[m].active) continue;
        int port = ctx->port_mappings[m].port;
        int is_tcp = ctx->port_mappings[m].is_tcp;

        if (is_tcp) {
            int already_listening = 0;
            for (int i = 0; i < ctx->num_listen_pcbs; i++) {
                if (ctx->listen_pcbs[i] != NULL && ctx->listen_pcbs[i]->local_port == port) {
                    already_listening = 1;
                    break;
                }
            }
            if (already_listening) continue;

            struct tcp_pcb *pcb = tcp_new_ip6();
            if (pcb == NULL) {
                LOGE("tcp_new_ip6 failed for port %d", port);
                continue;
            }

            tcp_bind_netif(pcb, &ctx->ncm_netif);

            err_t err = tcp_bind(pcb, IP6_ADDR_ANY, (u16_t)port);
            if (err != ERR_OK) {
                LOGE("tcp_bind port %d failed: %d", port, err);
                tcp_close(pcb);
                continue;
            }

            struct tcp_pcb *listen_pcb = tcp_listen_with_backlog(pcb, 4);
            if (listen_pcb == NULL) {
                LOGE("tcp_listen_with_backlog port %d failed", port);
                tcp_close(pcb);
                continue;
            }

            tcp_arg(listen_pcb, ctx);
            tcp_accept(listen_pcb, tcp_relay_accept);

            if (ctx->num_listen_pcbs < MAX_PORT_MAPPINGS) {
                ctx->listen_pcbs[ctx->num_listen_pcbs++] = listen_pcb;
                LOGI("lwIP listening on [fe80::2]:%d (TCP)", port);
            }
        } else {
            int already_relayed = 0;
            for (int i = 0; i < MAX_UDP_RELAYS; i++) {
                if (ctx->udp_relays[i].active && ctx->udp_relays[i].port == port) {
                    already_relayed = 1;
                    break;
                }
            }
            if (already_relayed) continue;

            UdpRelay *relay = NULL;
            for (int i = 0; i < MAX_UDP_RELAYS; i++) {
                if (!ctx->udp_relays[i].active) {
                    relay = &ctx->udp_relays[i];
                    break;
                }
            }
            if (relay == NULL) {
                LOGE("No free UDP relay slots for port %d", port);
                continue;
            }

            struct udp_pcb *pcb = udp_new_ip6();
            if (pcb == NULL) {
                LOGE("udp_new_ip6 failed for port %d", port);
                continue;
            }

            udp_bind_netif(pcb, &ctx->ncm_netif);

            err_t err = udp_bind(pcb, IP6_ADDR_ANY, (u16_t)port);
            if (err != ERR_OK) {
                LOGE("udp_bind port %d failed: %d", port, err);
                udp_remove(pcb);
                continue;
            }

            int fd = socket(AF_INET, SOCK_DGRAM, 0);
            if (fd < 0) {
                LOGE("Failed to create loopback UDP socket for port %d: %d", port, errno);
                udp_remove(pcb);
                continue;
            }

            int flags = fcntl(fd, F_GETFL, 0);
            fcntl(fd, F_SETFL, flags | O_NONBLOCK);

            int rcvbuf = 256 * 1024;
            int sndbuf = 256 * 1024;
            setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf));
            setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

            relay->active = 1;
            relay->port = port;
            relay->lwip_pcb = pcb;
            relay->loopback_fd = fd;
            relay->has_peer = 0;
            memset(&relay->peer_ip, 0, sizeof(relay->peer_ip));
            relay->peer_port = 0;

            udp_recv(pcb, udp_relay_recv, relay);
            LOGI("lwIP listening on [fe80::2]:%d (UDP)", port);
        }
    }
}

static void *lwip_worker_thread(void *arg) {
    LwipContext *ctx = (LwipContext *)arg;
    LOGI("lwIP worker thread started");

    while (ctx->running) {
        pthread_mutex_lock(&lwip_core_lock);

        sys_check_timeouts();
        register_listeners_locked(ctx);
        check_and_forward_loopback(ctx);
        check_and_forward_udp_loopback(ctx);

        fd_set rfds;
        FD_ZERO(&rfds);
        int max_fd = ctx->wake_pipe[0];
        FD_SET(ctx->wake_pipe[0], &rfds);

        for (int i = 0; i < MAX_RELAY_CONNS; i++) {
            if (ctx->tcp_conns[i].active && ctx->tcp_conns[i].loopback_fd >= 0) {
                if (ctx->tcp_conns[i].loopback_to_lwip_len < BUFFER_SIZE) {
                    FD_SET(ctx->tcp_conns[i].loopback_fd, &rfds);
                    if (ctx->tcp_conns[i].loopback_fd > max_fd) {
                        max_fd = ctx->tcp_conns[i].loopback_fd;
                    }
                }
            }
        }

        for (int i = 0; i < MAX_UDP_RELAYS; i++) {
            if (ctx->udp_relays[i].active && ctx->udp_relays[i].loopback_fd >= 0) {
                FD_SET(ctx->udp_relays[i].loopback_fd, &rfds);
                if (ctx->udp_relays[i].loopback_fd > max_fd) {
                    max_fd = ctx->udp_relays[i].loopback_fd;
                }
            }
        }

        pthread_mutex_unlock(&lwip_core_lock);

        struct timeval tv = { .tv_sec = 0, .tv_usec = 20000 };
        int sel = select(max_fd + 1, &rfds, NULL, NULL, &tv);

        if (sel > 0 && FD_ISSET(ctx->wake_pipe[0], &rfds)) {
            char ch;
            while (read(ctx->wake_pipe[0], &ch, 1) > 0);
        }
    }

    LOGI("lwIP worker thread stopping");
    return NULL;
}

static void trigger_wake(LwipContext *ctx) {
    if (ctx->wake_pipe[1] >= 0) {
        char ch = 'w';
        (void)write(ctx->wake_pipe[1], &ch, 1);
    }
}

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeCreate(
        JNIEnv *env, jobject thiz, jbyteArray hostMacArr, jstring linkLocalStr) {
    (void)thiz;
    if (hostMacArr == NULL || linkLocalStr == NULL) return 0;

    LwipContext *ctx = (LwipContext *)calloc(1, sizeof(LwipContext));
    if (ctx == NULL) return 0;
    atomic_init(&ctx->running, 0);

    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->bridge_obj_global = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    ctx->on_tx_frame_mid = (*env)->GetMethodID(env, cls, "onTxFrame", "([B)V");
    if (ctx->on_tx_frame_mid == NULL) {
        LOGE("Failed to find onTxFrame method");
        free(ctx);
        return 0;
    }

    jbyte *mac_bytes = (*env)->GetByteArrayElements(env, hostMacArr, NULL);
    memcpy(ctx->host_mac, mac_bytes, 6);
    (*env)->ReleaseByteArrayElements(env, hostMacArr, mac_bytes, JNI_ABORT);

    const char *ll_str = (*env)->GetStringUTFChars(env, linkLocalStr, NULL);
    ip6addr_aton(ll_str, &ctx->host_ip6);
    (*env)->ReleaseStringUTFChars(env, linkLocalStr, ll_str);

    if (pipe(ctx->wake_pipe) != 0) {
        (*env)->DeleteGlobalRef(env, ctx->bridge_obj_global);
        free(ctx);
        return 0;
    }
    for (int end = 0; end < 2; end++) {
        int flags = fcntl(ctx->wake_pipe[end], F_GETFL, 0);
        fcntl(ctx->wake_pipe[end], F_SETFL, flags | O_NONBLOCK);
    }

    pthread_mutex_lock(&lwip_core_lock);
    pthread_once(&lwip_once, initialize_lwip_once);

    netif_add_noaddr(&ctx->ncm_netif, ctx, ncm_netif_init, ethernet_input);
    netif_set_link_up(&ctx->ncm_netif);
    netif_set_up(&ctx->ncm_netif);

    s8_t idx = 0;
    netif_add_ip6_address(&ctx->ncm_netif, &ctx->host_ip6, &idx);
    netif_ip6_addr_set_state(&ctx->ncm_netif, idx, IP6_ADDR_VALID);

    ip6_addr_t ip6_alt;
    ip6addr_aton("fe80::1", &ip6_alt);
    if (!ip6_addr_cmp(&ctx->host_ip6, &ip6_alt)) {
        s8_t idx1 = 1;
        netif_add_ip6_address(&ctx->ncm_netif, &ip6_alt, &idx1);
        netif_ip6_addr_set_state(&ctx->ncm_netif, idx1, IP6_ADDR_VALID);
    }

    pthread_mutex_unlock(&lwip_core_lock);
    ctx->running = 1;
    pthread_create(&ctx->thread, NULL, lwip_worker_thread, ctx);

    LOGI("LwipContext initialized");
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeInputFrame(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray frameArr, jint length) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL || !ctx->running || frameArr == NULL || length <= 0) return;

    // lwIP is built with NO_SYS=1 / SYS_LIGHTWEIGHT_PROT=0: its heap (mem.c)
    // has no internal locking, so every heap access must hold ctx->lock.
    // pbuf_alloc() -> mem_malloc() used to run outside the lock here while the
    // worker thread concurrently freed pbufs (mem_free) under the lock, which
    // corrupted the heap and later aborted the process in mem_free (SIGABRT).
    pthread_mutex_lock(&lwip_core_lock);
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)length, PBUF_RAM);
    if (p == NULL) {
        pthread_mutex_unlock(&lwip_core_lock);
        LOGW("pbuf_alloc failed for length %d", length);
        return;
    }

    (*env)->GetByteArrayRegion(env, frameArr, 0, length, (jbyte *)p->payload);

    ctx->stats_rx_frames++;
    ctx->stats_rx_bytes += (uint32_t)length;
    if (length >= 14) {
        uint8_t *raw = (uint8_t *)p->payload;
        uint16_t ethertype = (((uint16_t)raw[12]) << 8) | raw[13];
        if (ethertype == 0x86dd && length >= 54) {
            uint8_t next_hdr = raw[20]; // 14 + 6
            if (next_hdr == 6) { // TCP
                ctx->stats_rx_tcp++;
                if (length >= 68) {
                    uint8_t tcp_flags = raw[67]; // 14 + 40 + 13
                    if (tcp_flags & 0x02) { // SYN
                        ctx->stats_rx_tcp_syn++;
                    }
                }
            } else if (next_hdr == 17) {
                ctx->stats_rx_udp++;
            } else if (next_hdr == 58) {
                ctx->stats_rx_icmp6++;
            }
        }
    }

    err_t err = ctx->ncm_netif.input(p, &ctx->ncm_netif);
    if (err != ERR_OK) {
        ctx->stats_rx_drops++;
        pbuf_free(p);
    }
    pthread_mutex_unlock(&lwip_core_lock);

    trigger_wake(ctx);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeRegisterPort(
        JNIEnv *env, jobject thiz, jlong handle, jint port, jboolean isTcp) {
    (void)env;
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL || !ctx->running || port <= 0) return;

    pthread_mutex_lock(&lwip_core_lock);
    for (int i = 0; i < ctx->num_port_mappings; i++) {
        if (ctx->port_mappings[i].port == port &&
            ctx->port_mappings[i].is_tcp == (isTcp ? 1 : 0)) {
            pthread_mutex_unlock(&lwip_core_lock);
            return;
        }
    }
    if (ctx->num_port_mappings < MAX_PORT_MAPPINGS) {
        ctx->port_mappings[ctx->num_port_mappings].port = port;
        ctx->port_mappings[ctx->num_port_mappings].is_tcp = isTcp ? 1 : 0;
        ctx->port_mappings[ctx->num_port_mappings].active = 1;
        ctx->num_port_mappings++;
        LOGI("Registered port %d (tcp=%d) for relay", port, isTcp ? 1 : 0);
    }
    pthread_mutex_unlock(&lwip_core_lock);

    trigger_wake(ctx);
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeDestroy(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL) return;

    LOGI("Destroying LwipContext");
    ctx->running = 0;
    trigger_wake(ctx);
    pthread_join(ctx->thread, NULL);

    pthread_mutex_lock(&lwip_core_lock);

    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        close_tcp_relay_conn(ctx, &ctx->tcp_conns[i]);
    }

    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        close_udp_relay(ctx, &ctx->udp_relays[i]);
    }

    for (int i = 0; i < ctx->num_listen_pcbs; i++) {
        if (ctx->listen_pcbs[i] != NULL) {
            tcp_close(ctx->listen_pcbs[i]);
            ctx->listen_pcbs[i] = NULL;
        }
    }

    netif_remove(&ctx->ncm_netif);

    if (ctx->wake_pipe[0] >= 0) close(ctx->wake_pipe[0]);
    if (ctx->wake_pipe[1] >= 0) close(ctx->wake_pipe[1]);

    pthread_mutex_unlock(&lwip_core_lock);

    if (ctx->bridge_obj_global != NULL) {
        (*env)->DeleteGlobalRef(env, ctx->bridge_obj_global);
        ctx->bridge_obj_global = NULL;
    }

    free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_network_LwipNcmNative_nativeGetSnapshot(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    LwipContext *ctx = (LwipContext *)(intptr_t)handle;
    if (ctx == NULL) return (*env)->NewStringUTF(env, "lwip handle null");

    pthread_mutex_lock(&lwip_core_lock);
    int active_tcp = 0;
    for (int i = 0; i < MAX_RELAY_CONNS; i++) {
        if (ctx->tcp_conns[i].active) active_tcp++;
    }
    int active_udp = 0;
    for (int i = 0; i < MAX_UDP_RELAYS; i++) {
        if (ctx->udp_relays[i].active) active_udp++;
    }

    char buf[512];
    snprintf(buf, sizeof(buf),
             "listeners=%d tcp_act=%d (accepted=%u closed=%u) udp_relays=%d "
             "rx_frames=%u (tcp=%u syn=%u udp=%u icmp6=%u drop=%u) tx_frames=%u tx_bytes=%u",
             ctx->num_listen_pcbs,
             active_tcp, ctx->stats_tcp_conns_accepted, ctx->stats_tcp_conns_closed,
             active_udp,
             ctx->stats_rx_frames, ctx->stats_rx_tcp, ctx->stats_rx_tcp_syn,
             ctx->stats_rx_udp, ctx->stats_rx_icmp6, ctx->stats_rx_drops,
             ctx->stats_tx_frames, ctx->stats_tx_bytes);
    pthread_mutex_unlock(&lwip_core_lock);

    return (*env)->NewStringUTF(env, buf);
}
