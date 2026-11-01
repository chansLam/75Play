#ifndef LWIP_LWIPOPTS_H
#define LWIP_LWIPOPTS_H

#define NO_SYS                          1
#define SYS_LIGHTWEIGHT_PROT            0
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS 1
#define MEM_ALIGNMENT                   4
#define MEM_SIZE                        (128 * 1024)

#define MEMP_NUM_PBUF                   64
#define MEMP_NUM_RAW_PCB                4
#define MEMP_NUM_UDP_PCB                32
#define MEMP_NUM_TCP_PCB                32
#define MEMP_NUM_TCP_PCB_LISTEN         16
#define MEMP_NUM_TCP_SEG                128

#define PBUF_POOL_SIZE                  128
#define PBUF_POOL_BUFSIZE               1600

#define LWIP_ARP                        1
#define LWIP_ETHERNET                   1
#define LWIP_RAW                        1

#define LWIP_IPV4                       1
#define LWIP_IPV6                       1
#define LWIP_IPV6_SCOPES                0
#define LWIP_IPV6_SCOPES_DEBUG          0
#define LWIP_IPV6_NUM_ADDRESSES         3
#define LWIP_IPV6_FORWARD               0
#define LWIP_IPV6_FRAG                  0
#define LWIP_IPV6_REASS                 0
#define LWIP_IPV6_AUTOCONFIG            1
#define LWIP_IPV6_DUP_DETECT_ATTEMPTS   0

#define LWIP_ICMP6                      1
#define LWIP_ND6                        1
#define LWIP_ND6_NUM_NEIGHBORS          4
#define LWIP_ND6_NUM_DESTINATIONS       4
#define LWIP_ND6_NUM_PREFIXES           4
#define LWIP_ND6_MAX_MULTICAST_SOLICIT  0

#define LWIP_TCP                        1
#define TCP_MSS                         1440
#define TCP_WND                         (32 * 1024)
#define TCP_SND_BUF                     (32 * 1024)
#define TCP_SND_QUEUELEN                64
#define TCP_LISTEN_BACKLOG              1

#define LWIP_UDP                        1
#define UDP_TTL                         255

#define LWIP_DNS                        0
#define LWIP_DHCP                       0
#define LWIP_AUTOIP                     0
#define LWIP_NETCONN                    0
#define LWIP_SOCKET                     0
#define LWIP_STATS                      0

#define CHECKSUM_GEN_IP                 1
#define CHECKSUM_GEN_UDP                1
#define CHECKSUM_GEN_TCP                1
#define CHECKSUM_GEN_ICMP6              1
#define CHECKSUM_CHECK_IP               1
#define CHECKSUM_CHECK_UDP              1
#define CHECKSUM_CHECK_TCP              1
#define CHECKSUM_CHECK_ICMP6            1

#endif /* LWIP_LWIPOPTS_H */
