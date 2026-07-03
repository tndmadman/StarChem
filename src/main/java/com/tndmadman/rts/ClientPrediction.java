package com.tndmadman.rts;

final class ClientPrediction {
    private ClientPrediction() { }

    static void update(World world, double dt) {
        for (Unit unit : world.units.values()) {
            if (unit.task == UnitTask.MOVE || unit.task == UnitTask.RETURN_TO_STATION || unit.task == UnitTask.AUTO_HARVEST || unit.task == UnitTask.IDLE) {
                unit.updatePosition(dt, world.width, world.height);
            }
        }
    }
}
