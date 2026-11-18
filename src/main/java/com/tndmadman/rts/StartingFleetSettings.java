package com.tndmadman.rts;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Authoritative starter assets for newly-created commanders. The policy is
 * saved with the world and never recalculated from a server's changed CLI
 * arguments on restart. Pre-policy saves retain the historical single miner.
 */
record StartingFleetSettings(int prospectorCount, boolean tierOneRadar) {
    static final int DEFAULT_PROSPECTORS = 15;
    static final int MIN_PROSPECTORS = 1;
    static final int MAX_PROSPECTORS = 50;

    StartingFleetSettings {
        if (prospectorCount < MIN_PROSPECTORS || prospectorCount > MAX_PROSPECTORS) {
            throw new IllegalArgumentException("Starting Prospectors must be 1-50.");
        }
    }

    static StartingFleetSettings newCampaign(int count) {
        return new StartingFleetSettings(count, true);
    }

    static StartingFleetSettings legacy() {
        return new StartingFleetSettings(1, false);
    }

    static int parseCount(String value) {
        try {
            return new StartingFleetSettings(Integer.parseInt(value.trim()), true).prospectorCount();
        } catch (NumberFormatException | NullPointerException ex) {
            throw new IllegalArgumentException("Starting Prospectors must be a number from 1 to 50.");
        }
    }

    Map<String,Object> capture() {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("prospectorCount", prospectorCount);
        data.put("tierOneRadar", tierOneRadar);
        return data;
    }

    static StartingFleetSettings restore(Object saved) {
        Map<String,Object> data = ServerSaveStore.object(saved);
        if (!data.containsKey("prospectorCount")) return legacy();
        return new StartingFleetSettings(
                ServerSaveStore.intValue(data, "prospectorCount", 1),
                ServerSaveStore.boolValue(data, "tierOneRadar", false));
    }
}
