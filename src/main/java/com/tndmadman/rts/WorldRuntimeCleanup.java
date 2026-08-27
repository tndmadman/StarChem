package com.tndmadman.rts;

final class WorldRuntimeCleanup {
    private WorldRuntimeCleanup() { }

    static void discard(World world) {
        if (world == null) return;
        world.aiDevSettings.resetToDefaults();
        DevTimerSettings.configure(world, false);
        DiplomacySystem.clear(world);
        DiplomacyClientState.clear(world);
        SystemAudio.markNonRendered(world);
        AudioEventCenter.discard(world);
        GameNoticeCenter.clear(world);
        AlertCenter.clear(world);
        OwnerFleetLocationRegistry.clear(world);
        EmpireOverviewOverlay.clear(world);
        StrategicSummaryService.clear(world);
    }
}
