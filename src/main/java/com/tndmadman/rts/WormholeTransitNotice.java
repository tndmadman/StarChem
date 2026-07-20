package com.tndmadman.rts;

final class WormholeTransitNotice {
    private static final long EXIT_DELAY_MS = 260;
    private static final long ALARM_SECOND_TONE_DELAY_MS = 150;
    private static final long INCOMING_ALARM_COOLDOWN_NANOS = 900_000_000L;

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
        World world = PlayerRegistry.activeWorld();
        if (world == null || destinationSystemId == null || destinationSystemId.isBlank()) return;
        String alarmKey = "wormhole-incoming:" + destinationSystemId;
        if (!AudioEventCenter.claimCooldown(world, alarmKey, INCOMING_ALARM_COOLDOWN_NANOS)) return;
        AudioEventCenter.play(world, destinationSystemId, SoundCue.ATTACK_ORDER);
        AudioEventCenter.scheduleDelayed(world, destinationSystemId, SoundCue.ERROR,
                ALARM_SECOND_TONE_DELAY_MS, alarmKey + ":second-tone");
    }

    private static void play(World world, String entrySystemId, String exitSystemId) {
        if (world == null || entrySystemId == null || entrySystemId.isBlank()) return;
        AudioEventCenter.play(world, entrySystemId, SoundCue.TRACTOR_BEAM);
        AudioEventCenter.scheduleDelayed(world, exitSystemId, SoundCue.ITEM_PICKUP,
                EXIT_DELAY_MS, "");
    }
}
