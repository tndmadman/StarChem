package com.tndmadman.rts;

record SystemModifiers(
        double miningYield,
        double resourceRespawn,
        double sensorRange,
        double shieldRegen,
        double movementSpeed,
        double weaponRange,
        double environmentalDamagePerSecond
) {
    static final SystemModifiers STANDARD = new SystemModifiers(1, 1, 1, 1, 1, 1, 0);

    SystemModifiers {
        miningYield = positive(miningYield);
        resourceRespawn = positive(resourceRespawn);
        sensorRange = positive(sensorRange);
        shieldRegen = positive(shieldRegen);
        movementSpeed = positive(movementSpeed);
        weaponRange = positive(weaponRange);
        environmentalDamagePerSecond = Math.max(0, environmentalDamagePerSecond);
    }

    private static double positive(double value) {
        return Double.isFinite(value) && value > 0 ? value : 1;
    }
}
