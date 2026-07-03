package com.tndmadman.rts;

final class NetBaseSync {
    private NetBaseSync() { }

    static Base fromState(BaseState state) {
        Base base = new Base(state.id(), state.playerId(), state.typeId(), state.x(), state.y());
        CargoCodec.readInto(state.cargo(), base.inventory);
        return base;
    }

    static BaseState toState(Base base) {
        return new BaseState(base.id, base.playerId, base.typeId, base.x, base.y, CargoCodec.write(base.inventory));
    }
}
