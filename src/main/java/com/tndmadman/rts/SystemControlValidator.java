package com.tndmadman.rts;

import java.util.Set;

public final class SystemControlValidator {
    private static final Set<String> NO_NPCS = Set.of(Config.RAIDERS_ID, Config.FREE_MINERS_ID, Config.CORSAIRS_ID);

    private SystemControlValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem system control validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("P1", "Blue", 0x3388FF);
        PlayerRegistry.register("P1", "Blue", 0x3388FF, true);
        PlayerRegistry.register("P2", "Red", 0xFF5544, false);
        World world = new World("Control Validator", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, false);
        double x = world.width * 0.5;
        double y = world.height * 0.5;
        world.bases.put("P1:B1", new Base("P1:B1", "P1", Rules.DEFAULT_BASE, x, y));
        world.updateCurrentSystem(76.0);
        GalaxyMapSystem controlled = system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId());
        require(controlled.controlStatus() == SystemControlStatus.CONTROLLED && "P1".equals(controlled.controllerId()),
                "uncontested player influence did not capture the system");
        require(controlled.controlColorRgb() == 0x3388FF, "map control color does not match controlling player");
        require(SystemControlBonuses.miningYield(world, "P1") > 1.0
                        && SystemControlBonuses.shieldRegen(world, "P1") > 1.0,
                "system controller received no territorial bonuses");
        require(SystemControlBonuses.miningYield(world, "P2") == 1.0,
                "non-controller received territorial bonuses");

        world.bases.put("P2:B1", new Base("P2:B1", "P2", Rules.DEFAULT_BASE, x + 80, y + 60));
        world.updateCurrentSystem(1.0);
        GalaxyMapSystem contested = system(world.authoritativeGalaxyMapSnapshot(), world.activeSystemId());
        require(contested.controlStatus() == SystemControlStatus.CONTESTED,
                "hostile presence in the command zone did not contest control");

        PlayerRegistry.reset("SOLO", "Home", 0x50BEFF);
        World home = new World("Home", NO_NPCS, StarSystems.DEFAULT_SYSTEM_ID, true);
        home.bases.put("P2:B2", new Base("P2:B2", "P2", Rules.DEFAULT_BASE, home.width * 0.5, home.height * 0.5));
        home.updateCurrentSystem(90.0);
        GalaxyMapSystem protectedHome = system(home.authoritativeGalaxyMapSnapshot(), home.activeSystemId());
        require(protectedHome.controlStatus() == SystemControlStatus.PROTECTED,
                "protected player home became capturable");

        GalaxyMapSystem corsairs = system(home.authoritativeGalaxyMapSnapshot(), StarSystems.CORSAIR_SYSTEM_ID);
        require(corsairs != null && Config.CORSAIRS_ID.equals(corsairs.controllerId()),
                "Corsair Den did not begin under Corsair control");
    }

    private static GalaxyMapSystem system(GalaxyMapSnapshot snapshot, String id) {
        for (GalaxyMapSystem system : snapshot.systems()) if (id.equals(system.id())) return system;
        throw new IllegalStateException("Missing system " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
