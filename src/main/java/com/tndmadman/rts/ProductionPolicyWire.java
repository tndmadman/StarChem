package com.tndmadman.rts;

import java.util.Map;

/** Wire helpers for bounded production-policy command payloads. */
final class ProductionPolicyWire {
    private ProductionPolicyWire() { }

    static String encodeSpec(String policyId, ProductionPolicySystem.PolicyType type,
                             ProductionJobKind kind, String itemId, String loadoutId,
                             double targetAmount, int batchSize, int priority,
                             int maxOutstandingJobs, int repeatLimit,
                             Map<Material,Double> stationReserve,
                             Map<Material,Double> networkReserve) {
        String encoded = ProductionPolicySystem.encodeSpec(policyId, type, kind, itemId, loadoutId,
                targetAmount, batchSize, priority, maxOutstandingJobs, repeatLimit,
                stationReserve, networkReserve);
        // The system codec uses '-' as its display-safe blank token. CREATE needs
        // a truly empty stable-ID field so the authoritative parser can distinguish
        // a new policy from an update request.
        return encoded.startsWith("v1~-~") ? "v1~~" + encoded.substring(5) : encoded;
    }
}