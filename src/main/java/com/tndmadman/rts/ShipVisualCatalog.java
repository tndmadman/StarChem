package com.tndmadman.rts;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.tndmadman.rts.ShipVisualDefinition.Feature.*;
import static com.tndmadman.rts.ShipVisualDefinition.MountKind.*;

/** Local cosmetic catalog. Gameplay/network state continues to carry only ship type ids. */
final class ShipVisualCatalog {
    private static final Map<String, ShipVisualDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final ShipVisualDefinition FALLBACK = d("__fallback", 1,
            o(-28,0, -14,-12, 10,-14, 28,-7, 23,0, 28,7, 10,14, -14,12),
            f(), m(ENGINE, 22,0, 11,6));

    static {
        // Early utility hulls.
        add(d("prospector", 2,
                o(-29,-5, -23,-14, -11,-16, -3,-9, 11,-13, 27,-8, 23,0,
                        27,8, 11,13, -3,9, -11,16, -23,14, -29,5, -20,0),
                f(MINING_GEAR),
                m(MINING_HEAD,-25,-10,8,7), m(MINING_HEAD,-25,10,8,7),
                m(CARGO_POD,2,-8,11,6), m(CARGO_POD,2,8,11,6), m(ENGINE,22,0,12,6)));
        add(d("station_builder", 3,
                o(-28,-12, -17,-21, 5,-18, 11,-11, 28,-11, 23,0, 28,11, 11,11,
                        5,18, -17,21, -28,12, -18,5, -7,0, -18,-5),
                f(CONSTRUCTION_GEAR),
                m(CONSTRUCTION_ARM,-12,-12,-11,-10), m(CONSTRUCTION_ARM,-12,12,-11,10),
                m(CARGO_POD,8,-10,12,7), m(CARGO_POD,8,10,12,7), m(BRIDGE,-7,0,10,5), m(ENGINE,22,0,14,7)));

        // Anonymous contacts deliberately do not reveal the underlying authored hull.
        add(d("sensor_contact_small", 0, o(-18,0, 0,-8, 18,0, 0,8), f(ANONYMOUS_CONTACT)));
        add(d("sensor_contact_medium", 0, o(-21,0, -7,-9, 10,-9, 21,0, 10,9, -7,9), f(ANONYMOUS_CONTACT)));
        add(d("sensor_contact_large", 0, o(-23,-8, -13,-14, 14,-14, 23,-8, 23,8, 14,14, -13,14, -23,8), f(ANONYMOUS_CONTACT)));

        // Combat line: each class has a different normalized macro silhouette.
        add(d("frigate", 2,
                o(-30,0, -17,-7, -4,-13, 8,-9, 19,-12, 28,-5, 23,0, 28,5,
                        19,12, 8,9, -4,13, -17,7), f(),
                m(HARDPOINT,-11,0,6,9), m(ENGINE,22,0,11,5), m(BRIDGE,-4,0,8,4)));
        add(d("destroyer", 3,
                o(-30,-8, -20,-4, -10,-12, 0,-18, 13,-14, 28,-7, 24,0, 28,7,
                        13,14, 0,18, -10,12, -20,4, -30,8, -23,0), f(),
                m(HARDPOINT,-18,-5,6,10), m(HARDPOINT,-18,5,6,10), m(HARDPOINT,-5,0,6,10),
                m(ENGINE,22,-6,9,5), m(ENGINE,22,6,9,5), m(BRIDGE,3,0,9,5)));
        add(d("cruiser", 4,
                o(-27,-6, -18,-13, -4,-15, 1,-22, 12,-17, 28,-10, 23,0, 28,10,
                        12,17, 1,22, -4,15, -18,13, -27,6, -20,0), f(),
                m(HARDPOINT,-14,-7,7,11), m(HARDPOINT,-14,7,7,11), m(HARDPOINT,2,-11,7,11), m(HARDPOINT,2,11,7,11),
                m(ENGINE,22,-7,11,5), m(ENGINE,22,7,11,5), m(BRIDGE,-3,0,11,6)));
        add(d("battle_cruiser", 5,
                o(-28,-16, -20,-19, -12,-12, 8,-10, 18,-17, 29,-9, 24,0, 29,9,
                        18,17, 8,10, -12,12, -20,19, -28,16, -23,5, -14,0, -23,-5), f(),
                m(HARDPOINT,-21,-11,8,13), m(HARDPOINT,-21,11,8,13), m(HARDPOINT,-7,0,9,15),
                m(ENGINE,22,-7,12,6), m(ENGINE,22,7,12,6), m(BRIDGE,4,0,12,6)));
        add(d("battleship", 6,
                o(-29,-10, -24,-17, -10,-20, -3,-17, 6,-22, 19,-19, 30,-10, 27,-3,
                        23,0, 27,3, 30,10, 19,19, 6,22, -3,17, -10,20, -24,17, -29,10, -24,0), f(),
                m(HARDPOINT,-20,-11,8,14), m(HARDPOINT,-20,11,8,14), m(HARDPOINT,-8,-14,8,14), m(HARDPOINT,-8,14,8,14),
                m(HARDPOINT,5,-12,8,14), m(HARDPOINT,5,12,8,14),
                m(ENGINE,23,-9,12,6), m(ENGINE,23,0,12,6), m(ENGINE,23,9,12,6), m(BRIDGE,5,0,13,7)));

        // Industry: function is readable directly from exposed equipment.
        add(d("hauler", 3,
                o(-28,-6, -19,-11, 16,-11, 29,-6, 24,0, 29,6, 16,11, -19,11), f(CARGO_MODULES),
                m(CARGO_POD,-10,-12,12,8), m(CARGO_POD,4,-12,12,8), m(CARGO_POD,-10,12,12,8), m(CARGO_POD,4,12,12,8),
                m(HARDPOINT,-20,0,5,8), m(ENGINE,23,0,13,6)));
        add(d("deep_miner", 4,
                o(-30,-13, -20,-18, -8,-12, 5,-15, 22,-11, 29,-5, 24,0, 29,5, 22,11,
                        5,15, -8,12, -20,18, -30,13, -21,5, -13,0, -21,-5), f(MINING_GEAR),
                m(MINING_HEAD,-27,-12,10,9), m(MINING_HEAD,-27,12,10,9), m(MINING_HEAD,-17,0,9,8),
                m(CARGO_POD,4,-9,12,7), m(CARGO_POD,4,9,12,7), m(ENGINE,23,0,13,6)));
        add(d("gas_harvester", 4,
                o(-29,0, -22,-14, -8,-20, 8,-19, 23,-12, 30,-4, 24,0, 30,4, 23,12,
                        8,19, -8,20, -22,14), f(GAS_GEAR),
                m(GAS_TANK,-9,-13,11,8), m(GAS_TANK,-9,13,11,8), m(GAS_TANK,7,-13,12,8), m(GAS_TANK,7,13,12,8),
                m(SENSOR_ARRAY,-18,0,9,9), m(ENGINE,23,0,13,6)));
        add(d("freighter", 5,
                o(-30,-8, -22,-14, 18,-14, 30,-8, 26,0, 30,8, 18,14, -22,14), f(CARGO_MODULES),
                m(CARGO_POD,-18,-14,13,9), m(CARGO_POD,-4,-14,13,9), m(CARGO_POD,10,-14,13,9),
                m(CARGO_POD,-18,14,13,9), m(CARGO_POD,-4,14,13,9), m(CARGO_POD,10,14,13,9),
                m(HARDPOINT,-24,-5,6,9), m(HARDPOINT,-24,5,6,9), m(ENGINE,24,-7,13,6), m(ENGINE,24,7,13,6)));
        add(d("salvager", 4,
                o(-29,-8, -18,-15, -4,-13, 5,-18, 18,-13, 29,-6, 24,0, 29,6, 18,13,
                        5,18, -4,13, -18,15, -29,8, -19,0), f(SALVAGE_GEAR, CARGO_MODULES),
                m(SALVAGE_BOOM,-15,-10,-13,-10), m(SALVAGE_BOOM,-15,10,-13,10), m(CARGO_POD,3,-10,11,7), m(CARGO_POD,3,10,11,7),
                m(SENSOR_ARRAY,-8,0,8,8), m(ENGINE,23,0,12,6)));

        // Capitals: progressively broader/longer silhouettes and more authored systems.
        add(d("carrier", 6,
                o(-30,-9, -22,-20, 17,-20, 30,-11, 24,-3, 18,0, 24,3, 30,11, 17,20, -22,20, -30,9, -23,0),
                f(HANGAR, CAPITAL),
                m(HANGAR,-8,-13,36,7), m(HANGAR,-8,13,36,7),
                m(HARDPOINT,-21,0,7,10), m(HARDPOINT,4,-18,7,10), m(HARDPOINT,4,18,7,10),
                m(ENGINE,23,-12,12,6), m(ENGINE,23,0,13,6), m(ENGINE,23,12,12,6), m(BRIDGE,9,0,12,7)));
        add(d("dreadnought", 7,
                o(-31,0, -24,-10, -12,-14, -2,-19, 11,-16, 29,-9, 25,-3, 30,0, 25,3, 29,9,
                        11,16, -2,19, -12,14, -24,10), f(SIEGE_WEAPON, CAPITAL),
                m(LANCE,-29,0,24,3), m(HARDPOINT,-15,-9,8,13), m(HARDPOINT,-15,9,8,13), m(HARDPOINT,2,-13,8,13), m(HARDPOINT,2,13,8,13),
                m(ENGINE,23,-8,13,6), m(ENGINE,23,8,13,6), m(BRIDGE,6,0,11,6)));
        add(d("supercarrier", 8,
                o(-31,-11, -24,-22, -3,-22, 3,-18, 17,-22, 31,-12, 27,-4, 19,0, 27,4,
                        31,12, 17,22, 3,18, -3,22, -24,22, -31,11, -24,0), f(HANGAR, CAPITAL),
                m(HANGAR,-14,-15,28,7), m(HANGAR,4,-15,23,7), m(HANGAR,-14,15,28,7), m(HANGAR,4,15,23,7),
                m(HARDPOINT,-23,-6,8,12), m(HARDPOINT,-23,6,8,12), m(HARDPOINT,8,-19,8,12), m(HARDPOINT,8,19,8,12),
                m(ENGINE,24,-13,12,6), m(ENGINE,24,0,14,7), m(ENGINE,24,13,12,6), m(BRIDGE,11,0,13,7)));
        add(d("titan", 9,
                o(-32,-4, -27,-11, -16,-13, -10,-19, 2,-17, 8,-22, 20,-18, 31,-9, 27,-2, 31,0,
                        27,2, 31,9, 20,18, 8,22, 2,17, -10,19, -16,13, -27,11, -32,4), f(SIEGE_WEAPON, CAPITAL),
                m(LANCE,-30,0,30,4), m(HARDPOINT,-20,-8,8,14), m(HARDPOINT,-20,8,8,14), m(HARDPOINT,-6,-13,8,14),
                m(HARDPOINT,-6,13,8,14), m(HARDPOINT,9,0,9,15),
                m(ENGINE,24,-12,13,6), m(ENGINE,24,0,14,7), m(ENGINE,24,12,13,6), m(BRIDGE,12,0,14,8)));
        add(d("monolith", 10,
                o(-31,-19, -23,-23, -10,-21, -2,-24, 10,-21, 21,-23, 31,-17, 28,-8, 31,0, 28,8,
                        31,17, 21,23, 10,21, -2,24, -10,21, -23,23, -31,19, -28,8, -31,0, -28,-8),
                f(HANGAR, CAPITAL, MEGASTRUCTURE),
                m(HANGAR,-17,-13,25,7), m(HANGAR,7,-13,22,7), m(HANGAR,-17,13,25,7), m(HANGAR,7,13,22,7),
                m(HARDPOINT,-24,-12,8,13), m(HARDPOINT,-24,12,8,13), m(HARDPOINT,-7,-19,8,13), m(HARDPOINT,-7,19,8,13),
                m(HARDPOINT,10,-18,8,13), m(HARDPOINT,10,18,8,13),
                m(CARGO_POD,-4,-8,12,7), m(CARGO_POD,-4,8,12,7), m(BRIDGE,12,0,15,9),
                m(ENGINE,24,-14,13,6), m(ENGINE,24,0,15,8), m(ENGINE,24,14,13,6)));

        // Automated couriers are authored too, but intentionally simpler than player hulls.
        add(d("fuel_hauler_shuttle", 1,
                o(-25,0, -13,-9, 4,-8, 12,-13, 27,-5, 22,0, 27,5, 12,13, 4,8, -13,9), f(CARGO_MODULES),
                m(CARGO_POD,2,-9,9,6), m(CARGO_POD,2,9,9,6), m(ENGINE,21,0,10,5)));
        add(d("logistics_shuttle", 1,
                o(-24,-4, -15,-10, 0,-12, 9,-7, 25,-7, 20,0, 25,7, 9,7, 0,12, -15,10, -24,4, -18,0), f(CARGO_MODULES),
                m(CARGO_POD,2,0,12,7), m(ENGINE,20,-5,9,4), m(ENGINE,20,5,9,4)));
    }

    private ShipVisualCatalog() { }

    static ShipVisualDefinition forType(ShipType type) {
        return type == null ? FALLBACK : DEFINITIONS.getOrDefault(type.id, FALLBACK);
    }

    static boolean hasExplicit(String shipTypeId) { return DEFINITIONS.containsKey(shipTypeId); }
    static Map<String, ShipVisualDefinition> definitions() { return Map.copyOf(DEFINITIONS); }

    private static void add(ShipVisualDefinition definition) {
        if (DEFINITIONS.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalStateException("Duplicate ship visual definition: " + definition.id());
        }
    }

    private static ShipVisualDefinition d(String id, int complexity, double[][] outline,
                                          EnumSet<ShipVisualDefinition.Feature> features,
                                          ShipVisualDefinition.Mount... mounts) {
        return new ShipVisualDefinition(id, outline, List.of(mounts), features, complexity);
    }

    private static EnumSet<ShipVisualDefinition.Feature> f(ShipVisualDefinition.Feature... features) {
        EnumSet<ShipVisualDefinition.Feature> set = EnumSet.noneOf(ShipVisualDefinition.Feature.class);
        for (ShipVisualDefinition.Feature feature : features) set.add(feature);
        return set;
    }

    private static ShipVisualDefinition.Mount m(ShipVisualDefinition.MountKind kind,
                                                 double x, double y, double width, double height) {
        return new ShipVisualDefinition.Mount(kind, x, y, width, height);
    }

    private static double[][] o(double... coordinates) {
        if (coordinates.length < 6 || coordinates.length % 2 != 0) {
            throw new IllegalArgumentException("Hull outline requires x/y pairs.");
        }
        double[][] result = new double[coordinates.length / 2][2];
        for (int i = 0; i < result.length; i++) {
            result[i][0] = coordinates[i * 2];
            result[i][1] = coordinates[i * 2 + 1];
        }
        return result;
    }
}
