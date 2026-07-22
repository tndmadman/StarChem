package com.tndmadman.rts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validates durable prune ordering, failure recovery, and restart-visible state. */
public final class ServerPruneTransactionValidator {
    private ServerPruneTransactionValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("StarChem prune persistence validation passed.");
    }

    static void validate() {
        validateSuccessAndRestart();
        validatePreSaveFailure();
        validateBackupFailure();
        validatePruneFailure();
        validatePostSaveFailure();
        validateVerificationFailure();
        validateEmptyDeletion();
        validatePublishAfterCommit();
    }

    private static void validateSuccessAndRestart() {
        FakeOperations operations = new FakeOperations();
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(result.committed(), "successful prune was not reported committed");
        require(!result.recoveryRequired(), "successful prune entered recovery");
        require(operations.events.equals(List.of("save:pre-prune", "backup", "prune", "save:post-prune", "verify", "publish")),
                "successful prune ordering was wrong: " + operations.events);
        require(operations.persistedPruned, "post-prune state was not persisted");
        require(new RestartedServer(operations.persistedPruned).pruned, "restart did not observe the committed pruned state");
    }

    private static void validatePreSaveFailure() {
        FakeOperations operations = new FakeOperations();
        operations.failPreSave = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(!result.committed() && !result.recoveryRequired(), "pre-save failure had the wrong result");
        require(operations.events.equals(List.of("save:pre-prune")), "pre-save failure continued the transaction");
    }

    private static void validateBackupFailure() {
        FakeOperations operations = new FakeOperations();
        operations.failBackup = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(!result.committed() && !result.recoveryRequired(), "backup failure had the wrong result");
        require(operations.events.equals(List.of("save:pre-prune", "backup")), "backup failure mutated the world");
    }

    private static void validatePruneFailure() {
        FakeOperations operations = new FakeOperations();
        operations.throwDuringPrune = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(result.recoveryRequired(), "prune failure did not require recovery");
        require(operations.events.equals(List.of("save:pre-prune", "backup", "prune", "restore", "recovery")),
                "prune failure recovery ordering was wrong: " + operations.events);
        require(!operations.published, "prune failure was published");
    }

    private static void validatePostSaveFailure() {
        FakeOperations operations = new FakeOperations();
        operations.failPostSave = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(result.recoveryRequired(), "post-save failure did not require recovery");
        require(operations.events.equals(List.of("save:pre-prune", "backup", "prune", "save:post-prune", "restore", "recovery")),
                "post-save failure recovery ordering was wrong: " + operations.events);
        require(!operations.persistedPruned && !operations.published, "post-save failure retained or published the prune");
    }

    private static void validateVerificationFailure() {
        FakeOperations operations = new FakeOperations();
        operations.failVerification = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(result.recoveryRequired(), "verification failure did not require recovery");
        require(operations.events.equals(List.of("save:pre-prune", "backup", "prune", "save:post-prune", "verify", "restore", "recovery")),
                "verification failure recovery ordering was wrong: " + operations.events);
        require(!operations.persistedPruned && !operations.published, "verification failure retained or published the prune");
    }

    private static void validateEmptyDeletion() {
        FakeOperations operations = new FakeOperations();
        operations.emptyDeletion = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(!result.committed() && !result.recoveryRequired(), "empty deletion had the wrong result");
        require(operations.events.equals(List.of("save:pre-prune", "backup", "prune")), "empty deletion performed a post-save or publication");
    }

    private static void validatePublishAfterCommit() {
        FakeOperations operations = new FakeOperations();
        operations.failPublish = true;
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(operations);
        require(result.committed() && !result.recoveryRequired(), "publish failure lost the committed result");
        require(operations.persistedPruned, "publish failure rolled back durable state");
        require(operations.events.indexOf("publish") > operations.events.indexOf("verify"), "publication happened before verification");
    }

    private static final class FakeOperations implements ServerPruneTransaction.Operations {
        final List<String> events = new ArrayList<>();
        boolean failPreSave;
        boolean failBackup;
        boolean throwDuringPrune;
        boolean failPostSave;
        boolean failVerification;
        boolean emptyDeletion;
        boolean failPublish;
        boolean pruned;
        boolean persistedPruned;
        boolean published;

        @Override public boolean save(String reason) {
            events.add("save:" + reason);
            if ("pre-prune".equals(reason)) return !failPreSave;
            if (failPostSave) return false;
            persistedPruned = pruned;
            return true;
        }

        @Override public ServerBackupAdmin.BackupCreation createBackup(String label) {
            events.add("backup");
            return failBackup
                    ? new ServerBackupAdmin.BackupCreation(false, null, "backup failed")
                    : new ServerBackupAdmin.BackupCreation(true, Path.of("pre-prune.starchem-save"), "Created backup pre-prune.starchem-save.");
        }

        @Override public Set<String> prune() {
            events.add("prune");
            if (throwDuringPrune) throw new IllegalStateException("simulated prune failure");
            if (emptyDeletion) return Set.of();
            pruned = true;
            return Set.of("DYN-2", "DYN-1");
        }

        @Override public ServerBackupAdmin.Verification verifyCurrent() {
            events.add("verify");
            return new ServerBackupAdmin.Verification(!failVerification && persistedPruned,
                    failVerification ? "simulated checksum failure" : "checksums passed");
        }

        @Override public String restoreCurrent(Path backup) {
            events.add("restore");
            persistedPruned = false;
            return "Restored current save from " + backup.getFileName() + ".";
        }

        @Override public void enterRecovery(String reason) {
            events.add("recovery");
        }

        @Override public void publish(Set<String> deleted) {
            events.add("publish");
            require(persistedPruned, "publication occurred before post-prune persistence");
            if (failPublish) throw new IllegalStateException("simulated notification failure");
            published = true;
        }
    }

    private record RestartedServer(boolean pruned) { }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
