package com.tndmadman.rts;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SnapshotValidator {
    private SnapshotValidator() { }

    static void validate(Snapshot snapshot) {
        if (snapshot == null) throw SnapshotReader.error("snapshot", 0, "value", "snapshot is null");
        if (snapshot.sequence() < 0) throw SnapshotReader.error("snapshot", 0, "sequence", "must not be negative");
        finite(snapshot.systemTime(), -1, SnapshotReader.MAX_SCALAR, "snapshot", 0, "system time");
        SnapshotReader.text(snapshot.systemId(), SnapshotReader.MAX_SYSTEM_FIELD_LENGTH, "snapshot", 0, "system");
        SnapshotReader.text(snapshot.celestialState(), SnapshotReader.MAX_SYSTEM_FIELD_LENGTH, "snapshot", 0, "celestial state");
        CelestialPacketCache.validateState(snapshot.celestialState());

        validatePlayers(required(snapshot.players(), "players"));
        validateUnits(required(snapshot.units(), "units"));
        validateResources(required(snapshot.resources(), "resources"));
        validateBases(required(snapshot.bases(), "bases"), snapshot.systemId());
        validateStocks(required(snapshot.stocks(), "stocks"));
        validateShots(required(snapshot.shots(), "shots"));
        validateItems(required(snapshot.items(), "items"));
        validateResearch(required(snapshot.research(), "research"));
        validateObjective(snapshot.objective());
    }

    private static void validatePlayers(List<PlayerInfo> states) {
        limit(states, SnapshotReader.MAX_PLAYERS, "players");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            PlayerInfo state = states.get(i);
            if (state == null) throw SnapshotReader.error("players", row, "value", "row is null");
            String id = SnapshotReader.requiredText(state.id(), 64, "players", row, "player ID");
            SnapshotReader.requiredText(state.name(), SnapshotReader.MAX_NAME_LENGTH, "players", row, "name");
            if (!ids.add(id)) throw SnapshotReader.error("players", row, "player ID", "duplicate value " + SnapshotReader.printable(id));
        }
    }

    private static void validateUnits(List<UnitState> states) {
        limit(states, SnapshotReader.MAX_UNITS, "units");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            UnitState state = states.get(i);
            if (state == null) throw SnapshotReader.error("units", row, "value", "row is null");
            String playerId = SnapshotReader.requiredText(state.playerId(), 64, "units", row, "player ID");
            if (state.unitId() < 0) throw SnapshotReader.error("units", row, "unit ID", "must not be negative");
            String key = playerId + ':' + state.unitId();
            if (!ids.add(key)) throw SnapshotReader.error("units", row, "unit ID", "duplicate unit " + SnapshotReader.printable(key));
            String shipType = SnapshotReader.requiredText(state.shipTypeId(), 128, "units", row, "ship type ID");
            if (Rules.findShip(shipType) == null) throw SnapshotReader.error("units", row, "ship type ID", "unknown value " + SnapshotReader.printable(shipType));
            String loadoutId = SnapshotReader.requiredText(state.loadoutId(), 128, "units", row, "loadout ID");
            ShipLoadoutDefinition loadout = WeaponRules.findLoadout(loadoutId);
            if (loadout == null || !shipType.equals(loadout.hullId())) {
                throw SnapshotReader.error("units", row, "loadout ID", "unknown or mismatched value "
                        + SnapshotReader.printable(loadoutId) + " for hull " + SnapshotReader.printable(shipType));
            }
            coordinate(state.x(), "units", row, "x");
            coordinate(state.y(), "units", row, "y");
            coordinate(state.targetX(), "units", row, "target x");
            coordinate(state.targetY(), "units", row, "target y");
            finite(state.heading(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "units", row, "heading");
            SnapshotReader.enumName(state.task(), UnitTask.class, "units", row, "task");
            if (state.resourceId() < -1) throw SnapshotReader.error("units", row, "resource ID", "must be -1 or greater");
            String packageType = SnapshotReader.text(state.packageType(), 128, "units", row, "station package type");
            if (!packageType.isBlank() && Rules.findBase(packageType) == null) {
                throw SnapshotReader.error("units", row, "station package type ID", "unknown value " + SnapshotReader.printable(packageType));
            }
            SnapshotReader.validateCargo(state.cargo(), "units", row, "cargo");
            finite(state.hp(), 0, SnapshotReader.MAX_SCALAR, "units", row, "hp");
            finite(state.shield(), 0, SnapshotReader.MAX_SCALAR, "units", row, "shield");
            SnapshotReader.text(state.attackTarget(), SnapshotReader.MAX_TEXT_LENGTH, "units", row, "attack target");
            finite(state.weaponFlashTimer(), 0, SnapshotReader.MAX_SCALAR, "units", row, "weapon flash");
            SnapshotReader.enumName(state.orderType(), UnitOrderType.class, "units", row, "order type");
            coordinate(state.orderX1(), "units", row, "order x1");
            coordinate(state.orderY1(), "units", row, "order y1");
            coordinate(state.orderX2(), "units", row, "order x2");
            coordinate(state.orderY2(), "units", row, "order y2");
            finite(state.orderRadius(), 0, SnapshotReader.MAX_ABS_COORDINATE, "units", row, "order radius");
            SnapshotReader.text(state.orderTarget(), SnapshotReader.MAX_TEXT_LENGTH, "units", row, "order target");
            if (state.orderPhase() < 0 || state.orderPhase() > 1_000_000) {
                throw SnapshotReader.error("units", row, "order phase", "is outside the allowed range");
            }
        }
    }

    private static void validateResources(List<ResourceState> states) {
        limit(states, SnapshotReader.MAX_RESOURCES, "resources");
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            ResourceState state = states.get(i);
            if (state == null) throw SnapshotReader.error("resources", row, "value", "row is null");
            if (state.id() < 0 || !ids.add(state.id())) throw SnapshotReader.error("resources", row, "resource ID", state.id() < 0 ? "must not be negative" : "duplicate value " + state.id());
            SnapshotReader.requiredText(state.name(), SnapshotReader.MAX_NAME_LENGTH, "resources", row, "name");
            SnapshotReader.enumName(state.kind(), NodeKind.class, "resources", row, "kind");
            SnapshotReader.enumName(state.material(), Material.class, "resources", row, "material");
            coordinate(state.x(), "resources", row, "x");
            coordinate(state.y(), "resources", row, "y");
            finite(state.maxAmount(), 0, SnapshotReader.MAX_SCALAR, "resources", row, "maximum amount");
            finite(state.harvestRate(), 0, SnapshotReader.MAX_SCALAR, "resources", row, "harvest rate");
            finite(state.radius(), 0, SnapshotReader.MAX_ABS_COORDINATE, "resources", row, "radius");
            finite(state.amount(), 0, state.maxAmount(), "resources", row, "amount");
            finite(state.respawnTimer(), 0, SnapshotReader.MAX_SCALAR, "resources", row, "respawn timer");
            coordinate(state.orbitCenterX(), "resources", row, "orbit center x");
            coordinate(state.orbitCenterY(), "resources", row, "orbit center y");
            finite(state.orbitRadius(), 0, SnapshotReader.MAX_ABS_COORDINATE, "resources", row, "orbit radius");
            finite(state.orbitAngle(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "resources", row, "orbit angle");
            finite(state.orbitSpeed(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "resources", row, "orbit speed");
        }
    }

    private static void validateBases(List<BaseState> states, String systemId) {
        limit(states, SnapshotReader.MAX_BASES, "bases");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            BaseState state = states.get(i);
            if (state == null) throw SnapshotReader.error("bases", row, "value", "row is null");
            String id = SnapshotReader.requiredText(state.id(), 128, "bases", row, "base ID");
            if (!ids.add(id)) throw SnapshotReader.error("bases", row, "base ID", "duplicate value " + SnapshotReader.printable(id));
            SnapshotReader.requiredText(state.playerId(), 64, "bases", row, "player ID");
            String typeId = SnapshotReader.requiredText(state.typeId(), 128, "bases", row, "station type ID");
            if (Rules.findBase(typeId) == null) throw SnapshotReader.error("bases", row, "station type ID", "unknown value " + SnapshotReader.printable(typeId));
            coordinate(state.x(), "bases", row, "x");
            coordinate(state.y(), "bases", row, "y");
            finite(state.hp(), 0, SnapshotReader.MAX_SCALAR, "bases", row, "hp");
            finite(state.shield(), 0, SnapshotReader.MAX_SCALAR, "bases", row, "shield");
            SnapshotReader.validateCargo(state.cargo(), "bases", row, "cargo");
            StrictProductionQueueCodec.decode(state.productionQueue(), CelestialPacketCache.systemId(systemId), id);
            SnapshotReader.text(state.logisticsStatus(), BaseStateParser.MAX_LOGISTICS_STATUS_CHARS,
                    "bases", row, "logistics status");
        }
    }

    private static void validateStocks(List<StockState> states) {
        limit(states, SnapshotReader.MAX_STOCKS, "stocks");
        Set<String> players = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            StockState state = states.get(i);
            if (state == null) throw SnapshotReader.error("stocks", row, "value", "row is null");
            String playerId = SnapshotReader.requiredText(state.playerId(), 64, "stocks", row, "player ID");
            if (!players.add(playerId)) throw SnapshotReader.error("stocks", row, "player ID", "duplicate value " + SnapshotReader.printable(playerId));
            SnapshotReader.validateCargo(state.cargo(), "stocks", row, "cargo");
        }
    }

    private static void validateShots(List<ShotState> states) {
        limit(states, SnapshotReader.MAX_SHOTS, "shots");
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            ShotState state = states.get(i);
            if (state == null) throw SnapshotReader.error("shots", row, "value", "row is null");
            if (state.id() < 0 || !ids.add(state.id())) throw SnapshotReader.error("shots", row, "shot ID", state.id() < 0 ? "must not be negative" : "duplicate value " + state.id());
            SnapshotReader.requiredText(state.ownerId(), 128, "shots", row, "owner ID");
            String weaponId = SnapshotReader.requiredText(state.weaponId(), 128, "shots", row, "weapon ID");
            if (!WeaponRules.WEAPONS.containsKey(weaponId)) throw SnapshotReader.error("shots", row, "weapon ID", "unknown value " + SnapshotReader.printable(weaponId));
            SnapshotReader.text(state.targetKey(), SnapshotReader.MAX_TEXT_LENGTH, "shots", row, "target");
            coordinate(state.x(), "shots", row, "x");
            coordinate(state.y(), "shots", row, "y");
            coordinate(state.lastX(), "shots", row, "last x");
            coordinate(state.lastY(), "shots", row, "last y");
        }
    }

    private static void validateItems(List<ItemState> states) {
        limit(states, SnapshotReader.MAX_ITEMS, "items");
        Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            ItemState state = states.get(i);
            if (state == null) throw SnapshotReader.error("items", row, "value", "row is null");
            if (state.id() < 0 || !ids.add(state.id())) throw SnapshotReader.error("items", row, "item ID", state.id() < 0 ? "must not be negative" : "duplicate value " + state.id());
            SnapshotReader.enumName(state.material(), Material.class, "items", row, "material");
            finite(state.amount(), 0, SnapshotReader.MAX_SCALAR, "items", row, "amount");
            coordinate(state.x(), "items", row, "x");
            coordinate(state.y(), "items", row, "y");
            finite(state.vx(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", row, "velocity x");
            finite(state.vy(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", row, "velocity y");
            finite(state.angle(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", row, "angle");
            finite(state.spin(), -SnapshotReader.MAX_SCALAR, SnapshotReader.MAX_SCALAR, "items", row, "spin");
        }
    }

    private static void validateResearch(List<ResearchState> states) {
        limit(states, SnapshotReader.MAX_RESEARCH_STATES, "research");
        Set<String> players = new HashSet<>();
        for (int i = 0; i < states.size(); i++) {
            int row = i + 1;
            ResearchState state = states.get(i);
            if (state == null) throw SnapshotReader.error("research", row, "value", "row is null");
            String playerId = SnapshotReader.requiredText(state.playerId(), 64, "research", row, "player ID");
            if (!players.add(playerId)) {
                throw SnapshotReader.error("research", row, "player ID", "duplicate value " + SnapshotReader.printable(playerId));
            }
            Set<String> topics = new HashSet<>();
            for (String topicId : required(state.topicIds(), "research topics")) {
                String checked = SnapshotReader.requiredText(topicId, 128, "research", row, "topic ID");
                if (ResearchRules.topic(checked) == null) {
                    throw SnapshotReader.error("research", row, "topic ID", "unknown value " + SnapshotReader.printable(checked));
                }
                if (!topics.add(checked)) {
                    throw SnapshotReader.error("research", row, "topic ID", "duplicate value " + SnapshotReader.printable(checked));
                }
            }
        }
    }

    private static void validateObjective(ObjectiveState state) {
        if (state == null) throw SnapshotReader.error("objective", 1, "value", "state is null");
        finite(state.elapsedSeconds(), 0, SnapshotReader.MAX_SCALAR, "objective", 1, "elapsed seconds");
        if (state.status() == ObjectiveStatus.DISABLED) {
            if (!state.conditionId().isBlank() || state.current() != 0 || state.target() != 0
                    || !state.leaderId().isBlank() || !state.completedById().isBlank()) {
                throw SnapshotReader.error("objective", 1, "disabled state", "must not contain active progress");
            }
            return;
        }
        String conditionId = SnapshotReader.requiredText(state.conditionId(), 64,
                "objective", 1, "condition ID");
        VictoryConditionDefinition definition = VictoryConditionRules.definition(conditionId);
        if (definition == null) {
            throw SnapshotReader.error("objective", 1, "condition ID",
                    "unknown value " + SnapshotReader.printable(conditionId));
        }
        if (state.target() != definition.target()) {
            throw SnapshotReader.error("objective", 1, "target", "does not match loaded configuration");
        }
        if (state.current() < 0) {
            throw SnapshotReader.error("objective", 1, "current progress", "must not be negative");
        }
        SnapshotReader.text(state.leaderId(), 64, "objective", 1, "leader ID");
        SnapshotReader.text(state.completedById(), 64, "objective", 1, "completed-by ID");
        if (state.status() == ObjectiveStatus.COMPLETED) {
            if (state.completedById().isBlank()) {
                throw SnapshotReader.error("objective", 1, "completed-by ID", "is required for completed state");
            }
            if (state.current() < state.target()) {
                throw SnapshotReader.error("objective", 1, "current progress", "must reach the target when completed");
            }
        } else if (!state.completedById().isBlank()) {
            throw SnapshotReader.error("objective", 1, "completed-by ID", "must be blank while active");
        }
    }

    private static <T> List<T> required(List<T> values, String section) {
        if (values == null) throw SnapshotReader.error(section, 0, "section", "list is null");
        return values;
    }

    private static void limit(List<?> values, int max, String section) {
        if (values.size() > max) throw SnapshotReader.error(section, 0, "count", "contains more than " + max + " rows");
    }

    private static void coordinate(double value, String section, int row, String field) {
        finite(value, -SnapshotReader.MAX_ABS_COORDINATE, SnapshotReader.MAX_ABS_COORDINATE, section, row, field);
    }

    private static void finite(double value, double min, double max, String section, int row, String field) {
        if (!Double.isFinite(value)) throw SnapshotReader.error(section, row, field, "must be finite");
        if (value < min || value > max) throw SnapshotReader.error(section, row, field, "is outside the allowed range");
    }
}
