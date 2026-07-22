package com.tndmadman.rts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Commits system pruning to a verified save before publishing the mutation to clients. */
final class ServerPruneTransaction {
    private ServerPruneTransaction() { }

    interface Operations {
        boolean save(String reason);
        ServerBackupAdmin.BackupCreation createBackup(String label);
        Set<String> prune();
        ServerBackupAdmin.Verification verifyCurrent();
        String restoreCurrent(Path backup);
        void enterRecovery(String reason);
        void publish(Set<String> deleted);
    }

    record Result(List<String> lines, boolean committed, boolean recoveryRequired) {
        Result {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    static Result run(Operations operations) {
        if (operations == null) return new Result(List.of("Prune transaction is unavailable."), false, false);
        if (!operations.save("pre-prune")) {
            return new Result(List.of("Pre-prune save failed; nothing was deleted."), false, false);
        }

        ServerBackupAdmin.BackupCreation backup;
        try {
            backup = operations.createBackup("pre-prune");
        } catch (RuntimeException ex) {
            return new Result(List.of("Could not create pre-prune backup: " + detail(ex), "Nothing was deleted."), false, false);
        }
        if (backup == null || !backup.success() || backup.path() == null) {
            String message = backup == null ? "Could not create pre-prune backup." : backup.message();
            return new Result(List.of(message, "Nothing was deleted."), false, false);
        }

        LinkedHashSet<String> deleted = new LinkedHashSet<>();
        try {
            Set<String> removed = operations.prune();
            if (removed != null) deleted.addAll(removed);
        } catch (RuntimeException ex) {
            return recover(operations, backup, "Prune failed before it could be committed: " + detail(ex));
        }

        if (deleted.isEmpty()) {
            return new Result(List.of(backup.message(), "No systems were deleted; the current save remains unchanged."), false, false);
        }

        ArrayList<String> ordered = new ArrayList<>(deleted);
        ordered.sort(String::compareTo);
        deleted.clear();
        deleted.addAll(ordered);

        if (!operations.save("post-prune")) {
            return recover(operations, backup, "Post-prune save failed; the deletion was not committed.");
        }

        ServerBackupAdmin.Verification verification;
        try {
            verification = operations.verifyCurrent();
        } catch (RuntimeException ex) {
            return recover(operations, backup, "Post-prune save verification failed: " + detail(ex));
        }
        if (verification == null || !verification.valid()) {
            String verificationDetail = verification == null ? "verification returned no result" : verification.detail();
            return recover(operations, backup, "Post-prune save verification failed: " + verificationDetail);
        }

        try {
            operations.publish(Set.copyOf(deleted));
        } catch (RuntimeException ex) {
            return new Result(List.of(
                    backup.message(), successMessage(deleted.size()), "Deleted: " + String.join(", ", deleted),
                    "The prune is durable, but client notification failed: " + detail(ex) + ". Run 'resync all'."), true, false);
        }

        return new Result(List.of(backup.message(), successMessage(deleted.size()),
                "Deleted: " + String.join(", ", deleted)), true, false);
    }

    private static Result recover(Operations operations, ServerBackupAdmin.BackupCreation backup, String failure) {
        String restoration;
        try {
            restoration = operations.restoreCurrent(backup.path());
        } catch (RuntimeException ex) {
            restoration = "Could not restore current save: " + detail(ex);
        }
        String reason = failure + " " + restoration + " Restart the server before continuing.";
        try {
            operations.enterRecovery(reason);
        } catch (RuntimeException ex) {
            reason += " Recovery-state activation also failed: " + detail(ex) + ".";
        }
        return new Result(List.of(backup.message(), failure, restoration,
                "Server entered recovery-required mode; restart before continuing."), false, true);
    }

    private static String successMessage(int count) {
        return "Pruned " + count + " abandoned dynamic system" + (count == 1 ? "" : "s")
                + " and verified the committed save.";
    }

    private static String detail(RuntimeException ex) {
        if (ex == null) return "unknown error";
        String message = ex.getMessage();
        return ex.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
