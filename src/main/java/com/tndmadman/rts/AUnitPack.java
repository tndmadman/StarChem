package com.tndmadman.rts;

final class AUnitPack {
    private AUnitPack() { }

    static void apply(World world, String mode, String id, String packageType) {
        if ("LOAD".equals(mode)) world.loadBasePackage(id, packageType);
        else world.placePackage(world.units.get(id));
    }
}
