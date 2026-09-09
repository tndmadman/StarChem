package com.tndmadman.rts;

record SystemStrategicDefinition(
        double miningYield,
        double shieldRegen,
        double productionThroughput,
        double researchThroughput,
        double refitThroughput,
        double sensorRange,
        double logisticsThroughput,
        double repairThroughput,
        double controlX,
        double controlY,
        double controlRadius
) {
    static final SystemStrategicDefinition STANDARD = new SystemStrategicDefinition(
            1, 1, 1, 1, 1, 1, 1, 1, 0.5, 0.5, 0.24);

    SystemStrategicDefinition {
        miningYield = positive(miningYield);
        shieldRegen = positive(shieldRegen);
        productionThroughput = positive(productionThroughput);
        researchThroughput = positive(researchThroughput);
        refitThroughput = positive(refitThroughput);
        sensorRange = positive(sensorRange);
        logisticsThroughput = positive(logisticsThroughput);
        repairThroughput = positive(repairThroughput);
        controlX = fraction(controlX, 0.5);
        controlY = fraction(controlY, 0.5);
        controlRadius = Math.max(0.05, Math.min(0.5,
                Double.isFinite(controlRadius) ? controlRadius : 0.24));
    }

    boolean standardBenefits() {
        return close(miningYield, 1) && close(shieldRegen, 1)
                && close(productionThroughput, 1) && close(researchThroughput, 1)
                && close(refitThroughput, 1) && close(sensorRange, 1)
                && close(logisticsThroughput, 1) && close(repairThroughput, 1);
    }

    private static double positive(double value) {
        return Double.isFinite(value) && value > 0 ? value : 1;
    }

    private static double fraction(double value, double fallback) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) < 0.000001;
    }
}
