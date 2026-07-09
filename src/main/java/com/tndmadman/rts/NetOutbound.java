package com.tndmadman.rts;

import java.net.InetAddress;

interface NetOutbound {
    void send(String message, InetAddress address, int port);
}
