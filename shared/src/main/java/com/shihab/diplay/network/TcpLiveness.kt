package com.shihab.diplay.network

import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        diagnostic("TCP keepalive enabled; API 19 does not support keepalive interval tuning")
    }
}
