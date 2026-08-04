package com.tndmadman.rts;

final class ClientAttackPrediction {
    private ClientAttackPrediction() { }

    static void apply(World world, Unit unit) {
        AttackRangeRules.predictAttackMovement(world, unit);
    }
}
