package com.tndmadman.rts;

record WormholeTouchRequest(String playerId, int unitId, String fromSystemId, String gateId) {
    static WormholeTouchRequest detect(World world, String playerId) {
        if (world == null || !validPlayerId(playerId)) return null;
        String from = world.activeSystemId();
        if (!validSystemId(from)) return null;
        for (Unit unit : world.units.values()) {
            if (!playerId.equals(unit.playerId) || unit.wormholeCooldown > 0) continue;
            WormholeGate gate = touchingGate(world, unit);
            if (gate == null || !validSystemId(gate.fromSystemId) || !validSystemId(gate.toSystemId)) continue;
            return new WormholeTouchRequest(playerId, unit.unitId, from, gate.id);
        }
        return null;
    }

    String packet() {
        return "WHTOUCH|" + clean(playerId) + "|" + unitId + "|" + clean(fromSystemId) + "|" + clean(gateId);
    }

    boolean valid() {
        return validPlayerId(playerId) && unitId > 0 && validSystemId(fromSystemId) && gateId != null && !gateId.isBlank() && !gateId.contains("|") && !gateId.contains("WAIT");
    }

    static boolean validPlayerId(String id) {
        return id != null && !id.isBlank() && !"WAIT".equals(id);
    }

    static boolean validSystemId(String id) {
        return id != null && !id.isBlank() && !id.contains("WAIT");
    }

    static WormholeGate touchingGate(World world, Unit unit) {
        if (world == null || unit == null) return null;
        for (WormholeGate gate : world.wormholes) if (gate.containsForTransit(world, unit)) return gate;
        return null;
    }

    static WormholeGate gateById(World world, String gateId) {
        if (world == null || gateId == null || gateId.isBlank()) return null;
        for (WormholeGate gate : world.wormholes) if (gateId.equals(gate.id)) return gate;
        return null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace("|", "").trim();
    }
}
