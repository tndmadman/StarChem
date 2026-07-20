package com.tndmadman.rts;

final class WorldRuntimeCleanup {
    private WorldRuntimeCleanup() { }

    static void discard(World world) {
        if (world == null) return;
        world.aiDevSettings.resetToDefaults();
        DevTimerSettings.configure(world, false);
        SystemAudio.markNonRendered(world);
        AudioEventCenter.discard(world);
        GameNoticeCenter.clear(world);
        AlertCenter.clear(world);
    }
}
