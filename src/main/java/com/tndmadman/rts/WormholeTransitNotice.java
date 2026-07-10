package com.tndmadman.rts;

final class WormholeTransitNotice {
    private static final long EXIT_DELAY_MS = 260;
    private static final long ALARM_SECOND_TONE_DELAY_MS = 150;
    private static final long INCOMING_ALARM_COOLDOWN_NANOS = 900_000_000L;
    private static String lastIncomingSystemId = "";
    private static long lastIncomingAlarmNanos;

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

    static void incoming(String destinationSystemId) {
        if (!SystemAudio.audible(destinationSystemId)) return;
        long now = System.nanoTime();
        synchronized (WormholeTransitNotice.class) {
            if (destinationSystemId.equals(lastIncomingSystemId)
                    && now - lastIncomingAlarmNanos < INCOMING_ALARM_COOLDOWN_NANOS) return;
            lastIncomingSystemId = destinationSystemId;
            lastIncomingAlarmNanos = now;
        }
        SystemAudio.play(destinationSystemId, SoundCue.ATTACK_ORDER);
        Thread secondTone = new Thread(() -> {
            try {
                Thread.sleep(ALARM_SECOND_TONE_DELAY_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            SystemAudio.play(destinationSystemId, SoundCue.ERROR);
        }, "StarChem Incoming Wormhole Alarm");
        secondTone.setDaemon(true);
        secondTone.start();
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
