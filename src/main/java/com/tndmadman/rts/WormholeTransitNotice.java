package com.tndmadman.rts;

final class WormholeTransitNotice {
    private static final long EXIT_DELAY_MS = 260;

    private WormholeTransitNotice() { }

    static void alert(World world, String targetSystemId) {
        String sourceSystemId = world == null ? "" : world.activeSystemId();
        if (world != null && targetSystemId != null && !targetSystemId.isBlank()) {
            world.status = "Wormhole transit: entering " + StarSystems.get(targetSystemId).name() + ".";
        }
        play(world, sourceSystemId, targetSystemId);
    }

    static void play(World world, String systemId) {
        play(world, systemId, systemId);
    }

    private static void play(World world, String entrySystemId, String exitSystemId) {
        if (!SystemAudio.audible(world, entrySystemId)) return;
        ProceduralAudio.play(SoundCue.TRACTOR_BEAM);
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(EXIT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            SystemAudio.play(world, exitSystemId, SoundCue.ITEM_PICKUP);
        }, "StarChem Wormhole Exit Audio");
        exit.setDaemon(true);
        exit.start();
    }
}
