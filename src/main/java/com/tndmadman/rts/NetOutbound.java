package com.tndmadman.rts;

interface NetOutbound {
    void send(String message, ConnectionId connectionId, DeliveryClass deliveryClass);
}
