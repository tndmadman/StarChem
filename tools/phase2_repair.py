from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, found {count}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: str, old: str, new: str, expected: int) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} matches, found {count}: {old!r}")
    file.write_text(text.replace(old, new), encoding="utf-8")


BUDGET = "src/main/java/com/tndmadman/rts/NpcResourceBudget.java"
EXPEDITION = "src/main/java/com/tndmadman/rts/NpcExpeditionSystem.java"
CONSTRUCTION = "src/main/java/com/tndmadman/rts/NpcStationConstructionSystem.java"
RECOVERY = "src/main/java/com/tndmadman/rts/NpcRecoverySystem.java"
BUDGET_VALIDATOR = "src/main/java/com/tndmadman/rts/NpcResourceBudgetValidator.java"
EXPEDITION_VALIDATOR = "src/main/java/com/tndmadman/rts/NpcExpeditionValidator.java"
RECOVERY_VALIDATOR = "src/main/java/com/tndmadman/rts/NpcRecoveryValidator.java"

# Use the same deterministic richest supply base for both expansion eligibility and reservation.
replace_once(
    BUDGET,
    "        Base source = firstLivingBase(world, faction.id());\n",
    "        Base source = expansionSupplyBase(world, faction);\n",
)
replace_once(
    BUDGET,
    """    private static Base firstLivingBase(World world, String factionId) {
        for (Base base : world.bases.values()) {
            if (factionId.equals(base.playerId) && base.hp > 0) return base;
        }
        return null;
    }
""",
    """    static Base expansionSupplyBase(World world, NpcFaction faction) {
        if (world == null || faction == null) return null;
        Base best = null;
        double bestTotal = -1.0;
        for (Base base : world.bases.values()) {
            if (!faction.id().equals(base.playerId) || base.hp <= 0) continue;
            double total = 0.0;
            for (Material material : Material.values()) {
                if (material.raw || material == Material.FUEL) {
                    total += base.inventory.getOrDefault(material, 0.0);
                }
            }
            if (total > bestTotal + EPSILON
                    || (Math.abs(total - bestTotal) <= EPSILON
                    && (best == null || base.id.compareTo(best.id) < 0))) {
                best = base;
                bestTotal = total;
            }
        }
        return best;
    }
""",
)
replace_all(
    EXPEDITION,
    "bestSupplyBase(world, faction.id())",
    "NpcResourceBudget.expansionSupplyBase(world, faction)",
    2,
)
replace_once(
    EXPEDITION,
    """    private static Base bestSupplyBase(World world, String factionId) {
        Base best = null;
        double bestTotal = -1;
        for (Base base : world.bases.values()) {
            if (!factionId.equals(base.playerId) || base.hp <= 0) continue;
            double total = 0;
            for (Material material : Material.values()) {
                if (material.raw || material == Material.FUEL) {
                    total += base.inventory.getOrDefault(material, 0.0);
                }
            }
            if (total > bestTotal) {
                best = base;
                bestTotal = total;
            }
        }
        return best;
    }

""",
    "",
)

# A loaded expedition package is already represented by committedExpeditionFootholds.
# At equality, construction converts that commitment rather than adding a seventh station.
replace_once(
    CONSTRUCTION,
    """        if (NpcFactionCapacitySystem.snapshot(world, faction).stationCommitments()
                >= faction.maxStations()) return false;
        Base anchor = anchorBase(faction, builder.x, builder.y);
""",
    """        if (NpcFactionCapacitySystem.snapshot(world, faction).stationCommitments()
                > faction.maxStations()) return false;
        Base anchor = anchorBase(faction, builder.x, builder.y);
""",
)

# Preserve all surplus cargo, including cargo aboard the consumed emergency builder.
replace_once(
    RECOVERY,
    """        Base base = new Base(baseId, faction.id(), typeId,
                Calc.clamp(builder.x, 0, world.width),
                Calc.clamp(builder.y, 0, world.height));
        world.units.remove(builder.key());
        transferSurplusToBase(units, builder, base);
        world.bases.put(base.id, base);
""",
    """        Base base = new Base(baseId, faction.id(), typeId,
                Calc.clamp(builder.x, 0, world.width),
                Calc.clamp(builder.y, 0, world.height));
        transferSurplusToBase(units, base);
        world.units.remove(builder.key());
        world.bases.put(base.id, base);
""",
)
replace_once(
    RECOVERY,
    """    private static void transferSurplusToBase(List<Unit> units, Unit consumedBuilder, Base base) {
        for (Unit unit : units) {
            if (unit == consumedBuilder) continue;
            for (Material material : new ArrayList<>(unit.inventory.keySet())) {
""",
    """    private static void transferSurplusToBase(List<Unit> units, Base base) {
        for (Unit unit : units) {
            for (Material material : new ArrayList<>(unit.inventory.keySet())) {
""",
)

# Regression: expansion must use a funded later station rather than an empty first station.
replace_once(
    BUDGET_VALIDATOR,
    """        validateRecursiveFleetInputs();
        validateExpansionWaitsForRecovery();
""",
    """        validateRecursiveFleetInputs();
        validateExpansionWaitsForRecovery();
        validateExpansionUsesRichestSupplyBase();
""",
)
replace_once(
    BUDGET_VALIDATOR,
    """    private static void fundDesired(Base base, NpcBudgetPlan plan, NpcBudgetCategory through) {
""",
    """    private static void validateExpansionUsesRichestSupplyBase() {
        Fixture fixture = fixture("Expansion Richest Supply Base");
        ensureWorkers(fixture);
        ensureAllStations(fixture);
        ensureCombat(fixture);
        ensureSupportAndIndustry(fixture);
        ensureBuilder(fixture);
        completeResearch(fixture);
        clearMaterials(fixture);

        Base rich = null;
        for (Base base : fixture.world.bases.values()) {
            if (fixture.faction.id().equals(base.playerId) && base != fixture.home) {
                rich = base;
                break;
            }
        }
        require(rich != null, "expansion fixture had no secondary supply station");
        for (Material material : Material.values()) {
            if (material.raw || material == Material.FUEL) rich.inventory.put(material, 1000.0);
        }

        NpcBudgetPlan plan = NpcResourceBudget.plan(
                fixture.world, fixture.faction, NpcStrategicState.EXPAND);
        require(NpcResourceBudget.expansionSupplyBase(fixture.world, fixture.faction) == rich,
                "expansion did not select the richest deterministic supply station");
        require(NpcResourceBudget.canLaunchExpansion(fixture.world, fixture.faction, plan),
                "an empty first station blocked a fully funded later supply station");
    }

    private static void fundDesired(Base base, NpcBudgetPlan plan, NpcBudgetCategory through) {
""",
)

# Regression: the sixth and final allowed Corsair station must convert its expedition
# commitment into a construction plan and finish successfully.
replace_once(
    EXPEDITION_VALIDATOR,
    """        validateSuccessfulPersistentEstablishment();
        validatePrelaunchAbortRefund();
""",
    """        validateSuccessfulPersistentEstablishment();
        validateFinalCapacityExpeditionEstablishment();
        validatePrelaunchAbortRefund();
""",
)
replace_once(
    EXPEDITION_VALIDATOR,
    """    private static void validatePrelaunchAbortRefund() {
""",
    """    private static void validateFinalCapacityExpeditionEstablishment() {
        Fixture fixture = fixture("NPC Expedition Final Capacity");
        fixture.world().activateSystem(StarSystems.CORSAIR_SYSTEM_ID);
        Base fifth = addBase(fixture.world(), fixture.faction(), 5, "shipyard",
                fixture.world().width * 0.5 - 1400,
                fixture.world().height * 0.5 + 1400);
        fifth.inventory.put(Material.FUEL, 100.0);
        fixture.world().saveActiveSystem();

        require(globalStationCount(fixture.world(), fixture.faction().id())
                        == fixture.faction().maxStations() - 1,
                "final-capacity fixture did not begin one station below the limit");
        NpcExpeditionSnapshot reserved = startReservedPlan(fixture);
        assembleAtIssuedPositions(fixture, reserved);
        NpcExpeditionSnapshot launching = updateAtHome(fixture, 1.0);
        require(launching.state() == NpcExpeditionState.LAUNCHING,
                "final-capacity expedition did not launch");
        moveRosterAlongRoute(fixture, launching);

        NpcExpeditionSnapshot establishing = NpcExpeditionSystem.snapshot(
                fixture.world(), fixture.faction());
        require(establishing.state() == NpcExpeditionState.ESTABLISHING,
                "final-capacity expedition did not reach establishment");
        updateAtHome(fixture, 1.0);
        fixture.world().activateSystem(establishing.targetSystemId());
        require(NpcStationConstructionSystem.snapshot(fixture.world(), fixture.faction()).active(),
                "the expedition's own commitment blocked its final allowed construction plan");

        int guard = 0;
        while (NpcStationConstructionSystem.snapshot(fixture.world(), fixture.faction()).active()
                && guard++ < 240) {
            fixture.world().updateCurrentSystem(1.0);
        }
        require(guard < 240 && targetHasCorsairStation(
                        fixture.world(), establishing.targetSystemId()),
                "the final allowed expedition foothold did not complete");
        require(globalStationCount(fixture.world(), fixture.faction().id())
                        == fixture.faction().maxStations(),
                "final-capacity establishment exceeded or failed to reach the station cap");
    }

    private static void validatePrelaunchAbortRefund() {
""",
)
replace_once(
    EXPEDITION_VALIDATOR,
    """    private static Set<String> globalLiveKeys(World world, String factionId) {
""",
    """    private static int globalStationCount(World world, String factionId) {
        String previous = world.activeSystemId();
        String status = world.status;
        int count = 0;
        try {
            for (GalaxyMapSystem system : world.authoritativeGalaxyMapSnapshot().systems()) {
                if (system == null) continue;
                world.activateSystem(system.id());
                for (Base base : world.bases.values()) {
                    if (factionId.equals(base.playerId) && base.hp > 0) count++;
                }
            }
        } finally {
            if (previous != null && !previous.isBlank()) world.activateSystem(previous);
            world.status = status;
        }
        return count;
    }

    private static Set<String> globalLiveKeys(World world, String factionId) {
""",
)

# Regression: cargo remaining aboard the consumed emergency deployer must reach the new base.
replace_once(
    RECOVERY_VALIDATOR,
    """        validatePhysicalPaidRepairAndEscortOwnership();
        validateEmergencyRebuildFromCargo();
        validateLoadedPackageRecoveryRules();
""",
    """        validatePhysicalPaidRepairAndEscortOwnership();
        validateEmergencyRebuildFromCargo();
        validateEmergencyRebuildPreservesBuilderCargo();
        validateLoadedPackageRecoveryRules();
""",
)
replace_once(
    RECOVERY_VALIDATOR,
    """    private static void validateLoadedPackageRecoveryRules() {
""",
    """    private static void validateEmergencyRebuildPreservesBuilderCargo() {
        Fixture fixture = fixture("NPC Emergency Builder Cargo");
        fixture.world.wormholes.clear();
        Unit builder = addUnit(fixture, 71_101, "station_builder", 4000, 4000);
        BaseType recoveryBase = Rules.base(fixture.faction.baseType());
        for (Cost cost : recoveryBase.buildCost) {
            builder.inventory.merge(cost.material(), cost.amount(), Double::sum);
        }
        builder.inventory.merge(Material.XENON, 7.0, Double::sum);
        fixture.world.saveActiveSystem();

        stepCurrent(fixture.world, 1);
        Base emergency = firstFactionBase(fixture.world, fixture.faction.id());
        require(emergency != null, "builder-funded emergency rebuild did not create a station");
        require(!fixture.world.units.containsKey(builder.key()),
                "builder-funded emergency rebuild did not consume its deployer");
        require(emergency.inventory.getOrDefault(Material.XENON, 0.0) >= 6.999,
                "emergency rebuild discarded surplus cargo aboard the consumed deployer");
    }

    private static void validateLoadedPackageRecoveryRules() {
""",
)

print("Phase 2 source and validator repairs applied.")
