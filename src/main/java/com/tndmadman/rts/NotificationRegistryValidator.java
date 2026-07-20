package com.tndmadman.rts;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class NotificationRegistryValidator {
    private NotificationRegistryValidator() { }

    public static void main(String[] args) throws Exception {
        validateOrThrow();
        System.out.println("StarChem notification registry validation passed.");
    }

    static void validateOrThrow() throws Exception {
        validateWeakRegistryConfiguration();
        validateExplicitCleanup();
        validateImmutableAlertSnapshots();
        validateConcurrentRegistryAccess();
        LeaderboardAggregationValidator.validateOrThrow();
    }

    private static void validateWeakRegistryConfiguration() {
        require(GameNoticeCenter.usesWeakKeysForTest(), "game notice registry does not use weak world keys");
        require(AlertCenter.usesWeakKeysForTest(), "alert registry does not use weak world keys");
    }

    private static void validateExplicitCleanup() {
        PlayerRegistry.reset("WAIT", "Notification Registry Validator", 0x50BEFF);
        World world = new World("Notification Registry", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        GameNoticeCenter.publish(world, "P-VALIDATOR", NoticeCategory.SYSTEM, "queued notice", false);
        AlertCenter.push(world, "visible alert");
        require(GameNoticeCenter.containsWorldForTest(world), "notice registry did not retain active world state");
        require(AlertCenter.containsWorldForTest(world), "alert registry did not retain active world state");

        WorldRuntimeCleanup.discard(world);

        require(!GameNoticeCenter.containsWorldForTest(world), "notice registry retained discarded world");
        require(!AlertCenter.containsWorldForTest(world), "alert registry retained discarded world");
    }

    private static void validateImmutableAlertSnapshots() {
        World world = new World("Notification Snapshot", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        AlertCenter.push(world, "snapshot alert");
        List<GameNotification> snapshot = AlertCenter.list(world);
        require(snapshot.size() == 1, "alert snapshot did not include active alert");
        boolean immutable = false;
        try {
            snapshot.clear();
        } catch (UnsupportedOperationException expected) {
            immutable = true;
        }
        require(immutable, "alert snapshot exposed mutable registry state");
        require(AlertCenter.list(world).size() == 1, "external snapshot mutation changed registry state");
        WorldRuntimeCleanup.discard(world);
    }

    private static void validateConcurrentRegistryAccess() throws Exception {
        World world = new World("Notification Concurrency", Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread publisher = thread("notification-publisher", start, failure, () -> {
            for (int i = 0; i < 500; i++) {
                GameNoticeCenter.publish(world, "P" + (i % 8), NoticeCategory.SYSTEM, "notice " + i, false);
                AlertCenter.push(world, "alert " + i);
            }
        });
        Thread consumer = thread("notification-consumer", start, failure, () -> {
            for (int i = 0; i < 500; i++) {
                GameNoticeCenter.drain(world, "P" + (i % 8));
                AlertCenter.list(world);
                AlertCenter.update(world, 0.016);
            }
        });
        Thread cleaner = thread("notification-cleaner", start, failure, () -> {
            for (int i = 0; i < 100; i++) {
                GameNoticeCenter.clear(world);
                AlertCenter.clear(world);
            }
        });

        publisher.start();
        consumer.start();
        cleaner.start();
        start.countDown();
        publisher.join(TimeUnit.SECONDS.toMillis(10));
        consumer.join(TimeUnit.SECONDS.toMillis(10));
        cleaner.join(TimeUnit.SECONDS.toMillis(10));
        require(!publisher.isAlive() && !consumer.isAlive() && !cleaner.isAlive(),
                "notification registry concurrency validation timed out");
        if (failure.get() != null) throw new IllegalStateException("notification registry concurrency failure", failure.get());
        WorldRuntimeCleanup.discard(world);
    }

    private static Thread thread(String name, CountDownLatch start, AtomicReference<Throwable> failure,
                                 CheckedRunnable action) {
        return new Thread(() -> {
            try {
                start.await();
                action.run();
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        }, name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
