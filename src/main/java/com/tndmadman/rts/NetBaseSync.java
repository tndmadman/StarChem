package com.tndmadman.rts;

final class NetBaseSync {
    private NetBaseSync() { }

    static Base fromState(BaseState state) {
        Base base = new Base(state.id(), state.playerId(), state.typeId(), state.x(), state.y());
        base.hp = state.hp();
        base.shield = state.shield();
        CargoCodec.readInto(state.cargo(), base.inventory);
        return base;
    }

    static BaseState toState(Base base) {
        return new BaseState(base.id, base.playerId, base.typeId, base.x, base.y, base.hp, base.shield, CargoCodec.write(base.inventory));
    }
}
