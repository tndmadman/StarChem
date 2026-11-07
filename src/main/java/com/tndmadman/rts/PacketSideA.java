package com.tndmadman.rts;

final class PacketSideA {
    private PacketSideA() { }

    private static boolean handleRadarBelt(PeerServerSide server, String[] parts, ConnectionId connectionId) {
        if (parts.length == 0 || !"RADAR_BELT_CMD".equals(parts[0])) return false;
        boolean accepted = false;
        if (parts.length == 6 && server.owns(connectionId, parts[1])
                && server.views.view(server.world, parts[1]).equals(parts[2])
                && parts[2].length() <= 128 && parts[3].length() <= 128
                && parts[4].startsWith("RADAR_BELT_") && parts[4].length() <= 40
                && parts[5].length() <= 80) {
            final boolean[] changed = {false};
            server.change(parts[1], () -> changed[0] = StationControlCommands.apply(
                    server.world, parts[1], parts[3], parts[4], parts[5]));
            accepted = changed[0];
            if (accepted) {
                // Immediate authoritative replacement removes a deselected marker without
                // waiting for the next 250 ms periodic snapshot.
                server.transport.sendOrdered(
                        RadarBeltTrackingSystem.packet(server.world, parts[1], parts[2]), connectionId);
            }
        }
        server.transport.sendOrdered("RADAR_BELT_RESULT|" + (accepted ? "OK" : "REJECTED"), connectionId);
        return true;
    }

    static void handle(PeerServerSide s, String message, NetPacket packet) {
        try {
            if (MultiplayerComms.handleServer(s, message, packet)) return;
            if (FleetWire.handleServer(s, message, packet)) return;
            String[] p = message == null ? new String[0] : message.split("\\|", -1);
            ConnectionId connectionId = packet == null ? ConnectionId.NONE : packet.connectionId();
            if (SideAJoin.handle(s, p, connectionId, packet)) return;
            if (ObserverSessions.handleObserverControl(s, p, connectionId)) return;
            if (SideADev.handle(s, p, connectionId)) return;
            if (handleRadarBelt(s, p, connectionId)) return;
            SideAOrders.handle(s, p, connectionId);
        } catch (Exception ex) {
            s.transport.recordMalformedPacket();
        }
    }
}
