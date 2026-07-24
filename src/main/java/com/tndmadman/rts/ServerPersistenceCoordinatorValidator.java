package com.tndmadman.rts;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Validates bounded single-writer scheduling, autosave coalescing, and failure propagation. */
public final class ServerPersistenceCoordinatorValidator {
    private ServerPersistenceCoordinatorValidator() { }

    public static void main(String[] args) throws Exception {
        validate();
        System.out.println("StarChem persistence coordinator validation passed.");
    }

    static void validate() throws Exception {
        validateSerialExecutionAndAutosaveCoalescing();
        validateQueueBoundAndFailurePropagation();
    }

    private static void validateSerialExecutionAndAutosaveCoalescing() throws Exception {
        ServerPersistenceCoordinator coordinator = new ServerPersistenceCoordinator("validator-serial", 4);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger autosaveRuns = new AtomicInteger();
        AtomicReference<String> workerName = new AtomicReference<>();
        try {
            ServerPersistenceCoordinator.Submission first = coordinator.submit("manual", false, () -> {
                workerName.set(Thread.currentThread().getName());
                int running = active.incrementAndGet();
                maxActive.accumulateAndGet(running, Math::max);
                firstStarted.countDown();
                require(releaseFirst.await(5, TimeUnit.SECONDS), "first persistence job was not released");
                active.decrementAndGet();
            });
            require(first.accepted() && !first.coalesced(), "first persistence job was not accepted");
            require(firstStarted.await(5, TimeUnit.SECONDS), "first persistence job did not start");

            ServerPersistenceCoordinator.Submission autosave = coordinator.submit("autosave", true, () -> {
                int running = active.incrementAndGet();
                maxActive.accumulateAndGet(running, Math::max);
                autosaveRuns.incrementAndGet();
                active.decrementAndGet();
            });
            ServerPersistenceCoordinator.Submission duplicate = coordinator.submit("autosave", true,
                    () -> autosaveRuns.addAndGet(100));
            require(autosave.accepted() && !autosave.coalesced(), "first autosave was not queued");
            require(duplicate.accepted() && duplicate.coalesced(), "duplicate autosave was not coalesced");
            require(duplicate.completion() == autosave.completion(), "coalesced autosave did not share completion");

            releaseFirst.countDown();
            coordinator.await(first);
            coordinator.await(autosave);
            require(autosaveRuns.get() == 1, "coalesced autosave executed more than once");
            require(maxActive.get() == 1, "persistence jobs overlapped");
            require(workerName.get() != null && workerName.get().startsWith("starchem-persistence-"),
                    "persistence work did not run on the dedicated worker");
            require(!Thread.currentThread().getName().equals(workerName.get()),
                    "persistence work ran on the caller thread");
        } finally {
            releaseFirst.countDown();
            coordinator.shutdownForValidation();
        }
    }

    private static void validateQueueBoundAndFailurePropagation() throws Exception {
        ServerPersistenceCoordinator coordinator = new ServerPersistenceCoordinator("validator-bounds", 1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            ServerPersistenceCoordinator.Submission first = coordinator.submit("first", false, () -> {
                firstStarted.countDown();
                require(releaseFirst.await(5, TimeUnit.SECONDS), "bounded queue fixture was not released");
            });
            require(firstStarted.await(5, TimeUnit.SECONDS), "bounded queue fixture did not start");
            ServerPersistenceCoordinator.Submission queued = coordinator.submit("queued", false, () -> { });
            ServerPersistenceCoordinator.Submission rejected = coordinator.submit("rejected", false, () -> { });
            require(queued.accepted(), "bounded queue did not accept its one waiting job");
            require(!rejected.accepted(), "bounded queue accepted work beyond capacity");

            releaseFirst.countDown();
            coordinator.await(first);
            coordinator.await(queued);

            ServerPersistenceCoordinator.Submission failed = coordinator.submit("failure", false,
                    () -> { throw new IOException("simulated slow-disk failure"); });
            try {
                coordinator.await(failed);
                throw new IllegalStateException("persistence failure was not propagated");
            } catch (IOException expected) {
                require(expected.getMessage().contains("simulated slow-disk failure"),
                        "persistence failure lost its diagnostic");
            }
            require(coordinator.lastFailure().contains("simulated slow-disk failure"),
                    "coordinator did not retain the latest failure");
        } finally {
            releaseFirst.countDown();
            coordinator.shutdownForValidation();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
