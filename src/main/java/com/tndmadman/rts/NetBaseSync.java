package com.tndmadman.rts;

final class NetBaseSync {
    private NetBaseSync() { }

    static Base fromState(BaseState state) {
        return fromState(state, "");
    }

    static Base fromState(BaseState state, String systemId) {
        Base base = new Base(state.id(), state.playerId(), state.typeId(), state.x(), state.y());
        base.hp = state.hp();
        base.shield = state.shield();
        CargoCodec.readInto(state.cargo(), base.inventory);
        StrictProductionQueueCodec.readInto(state.productionQueue(), base, systemId);
        base.logisticsStatus = boundedLogisticsStatus(state.logisticsStatus());
        return base;
    }

    static BaseState toState(Base base) {
        return new BaseState(base.id, base.playerId, base.typeId, base.x, base.y, base.hp, base.shield,
                CargoCodec.write(base.inventory), ProductionQueueCodec.write(base.productionQueue),
                boundedLogisticsStatus(base.logisticsStatus));
    }

    private static String boundedLogisticsStatus(String value) {
        if (value == null || value.isBlank()) return "";
        StringBuilder clean = new StringBuilder(Math.min(value.length(), BaseStateParser.MAX_LOGISTICS_STATUS_CHARS));
        for (int i = 0; i < value.length() && clean.length() < BaseStateParser.MAX_LOGISTICS_STATUS_CHARS; i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) clean.append(c);
        }
        return clean.toString();
    }
}
