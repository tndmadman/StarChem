package com.tndmadman.rts;

final class WormholeTransitNotice {
    private static final long EXIT_DELAY_MS = 260;

    private WormholeTransitNotice() { }

    static void alert(World world, String targetSystemId) {
        if (world != null && targetSystemId != null && !targetSystemId.isBlank()) {
            world.status = "Wormhole transit: entering " + StarSystems.get(targetSystemId).name() + ".";
        }
        play();
    }

    static void play() {
        ProceduralAudio.play(SoundCue.TRACTOR_BEAM);
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(EXIT_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            ProceduralAudio.play(SoundCue.ITEM_PICKUP);
        }, "StarChem Wormhole Exit Audio");
        exit.setDaemon(true);
        exit.start();
    }
}
