package com.tndmadman.rts;

final class GameplayCommandNumbers {
    static final double MAX_ORDER_RADIUS = 1200.0;

    private GameplayCommandNumbers() { }

    static double parseFinite(String value) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed)) throw new IllegalArgumentException("numeric value must be finite");
        return parsed;
    }

    static boolean finite(double... values) {
        if (values == null) return false;
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    static boolean worldCoordinate(World world, double x, double y) {
        return world != null && finite(x, y)
                && x >= 0 && x <= world.width
                && y >= 0 && y <= world.height;
    }

    static boolean orderRadius(double radius) {
        return Double.isFinite(radius) && radius >= 0 && radius <= MAX_ORDER_RADIUS;
    }

    static double repairedCoordinate(double value, double fallback, double maximum) {
        double safeMaximum = Double.isFinite(maximum) && maximum >= 0 ? maximum : 0;
        double safeFallback = Double.isFinite(fallback) ? Calc.clamp(fallback, 0, safeMaximum) : safeMaximum * 0.5;
        return Double.isFinite(value) ? Calc.clamp(value, 0, safeMaximum) : safeFallback;
    }
}
