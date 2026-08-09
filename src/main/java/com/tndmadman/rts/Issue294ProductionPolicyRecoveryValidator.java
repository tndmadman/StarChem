package com.tndmadman.rts;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused station-loss/recovery coverage for issue #294. */
public final class Issue294ProductionPolicyRecoveryValidator {
    private Issue294ProductionPolicyRecoveryValidator() { }

    public static void main(String[] args) {
        validateOrThrow();
        System.out.println("StarChem issue #294 production policy recovery validation passed.");
    }

    static void validateOrThrow() {
        PlayerRegistry.reset("SOLO", "Issue 294 Recovery Validator", 0x50BEFF);
        World world = new World("Issue 294 Recovery Validator", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        String playerId = "POLICY_RECOVERY_TEST";
        PlayerRegistry.register(playerId, "Policy Recovery Tester", 0x50BEFF, true);

        Base lost = base(world, playerId + ":M1", playerId, "manufacturing", 300, 300);
        Base replacement = base(world, playerId + ":M2", playerId, "manufacturing", 700, 300);
        fill(lost);
        fill(replacement);
        lost.inventory.remove(Material.FUEL);
        replacement.inventory.remove(Material.FUEL);

        String spec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 100, 2, 75, 2, 0,
                Map.of(), Map.of());
        require(ProductionCommands.apply(world, playerId, "POLICY", lost.id,
                ProductionPolicySystem.COMMAND_CREATE, spec), "policy creation failed");
        List<ProductionPolicySystem.PolicyView> created = ProductionPolicySystem.viewsForBase(world, lost);
        require(created.size() == 1, "created policy missing");
        String policyId = created.get(0).id();

        world.bases.remove(lost.id);
        ProductionPolicySystem.update(world, 0.6);
        ProductionPolicyRecoveryBridge.refreshStatus(world);
        List<ProductionPolicyRecoveryBridge.OrphanView> orphans =
                ProductionPolicyRecoveryBridge.orphanViews(world, playerId);
        require(orphans.size() == 1 && policyId.equals(orphans.get(0).id()),
                "destroyed station policy was not exposed as orphaned");

        Base networkCopy = NetBaseSync.fromState(NetBaseSync.toState(replacement));
        require(ProductionPolicyRecoveryBridge.orphanViews(nullSafeWorld(networkCopy), playerId).isEmpty(),
                "client-only helper unexpectedly accepted a synthetic world");

        require(ProductionPolicyRecoveryBridge.reassign(world, playerId, policyId, replacement),
                "orphaned policy could not be reassigned to a compatible station");
        List<ProductionPolicySystem.PolicyView> reassigned = ProductionPolicySystem.viewsForBase(world, replacement);
        require(reassigned.size() == 1 && policyId.equals(reassigned.get(0).id()) && reassigned.get(0).enabled(),
                "reassigned policy did not preserve identity and enabled state");
        require(ProductionPolicyRecoveryBridge.orphanViews(world, playerId).isEmpty(),
                "reassigned policy remained orphaned");

        ProductionPolicySystem.update(world, 0.6);
        require(!replacement.productionQueue.isEmpty(),
                "reassigned maintain-stock policy did not resume production");

        Base doomed = base(world, playerId + ":M3", playerId, "manufacturing", 900, 300);
        fill(doomed);
        doomed.inventory.remove(Material.FUEL);
        String secondSpec = ProductionPolicyWire.encodeSpec("",
                ProductionPolicySystem.PolicyType.MAINTAIN_STOCK,
                ProductionJobKind.CRAFTABLE, "fuel", "", 50, 1, 60, 1, 0,
                Map.of(), Map.of());
        require(ProductionCommands.apply(world, playerId, "POLICY", doomed.id,
                ProductionPolicySystem.COMMAND_CREATE, secondSpec), "second policy creation failed");
        String secondId = ProductionPolicySystem.viewsForBase(world, doomed).get(0).id();
        world.bases.remove(doomed.id);
        ProductionPolicySystem.update(world, 0.6);
        require(ProductionPolicyRecoveryBridge.delete(world, playerId, secondId),
                "orphaned policy delete failed");
        for (ProductionPolicyRecoveryBridge.OrphanView orphan :
                ProductionPolicyRecoveryBridge.orphanViews(world, playerId)) {
            require(!secondId.equals(orphan.id()), "deleted orphan remained in recovery state");
        }
    }

    private static World nullSafeWorld(Base base) {
        // The compact recovery view is intentionally read from a normal synced client world,
        // not from an isolated Base object. Return null here to assert the helper fails closed.
        return null;
    }

    private static Base base(World world, String id, String playerId, String typeId, double x, double y) {
        Base base = new Base(id, playerId, typeId, x, y);
        world.bases.put(id, base);
        return base;
    }

    private static void fill(Base base) {
        for (Material material : Material.values()) base.inventory.put(material, 100_000.0);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Issue #294 recovery validation failed: " + message);
    }
}