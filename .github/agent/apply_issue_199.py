from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match in {path}, found {count}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


extensions_old = '''    private static List<String> prune(HeadlessGameServer host, ServerCommandDispatcher.Target target, List<String> args) {
        List<GalaxyMapSystem> candidates = pruneCandidates(host.world);
        if (args == null || args.isEmpty() || "preview".equalsIgnoreCase(args.get(0))) {
            ArrayList<String> lines = new ArrayList<>();
            lines.add("Eligible abandoned dynamic systems: " + candidates.size());
            for (GalaxyMapSystem system : candidates) lines.add(system.id() + " | " + system.name() + " | resources " + system.resources());
            if (candidates.isEmpty()) lines.add("No systems are currently eligible.");
            else lines.add("Run 'prune-systems run confirm' to create a backup and prune.");
            return List.copyOf(lines);
        }
        if (args.size() != 2 || !"run".equalsIgnoreCase(args.get(0)) || !"confirm".equalsIgnoreCase(args.get(1))) {
            return List.of("Usage: prune-systems <preview|run confirm>");
        }
        if (candidates.isEmpty()) return List.of("No abandoned dynamic systems are eligible.");
        if (!target.save()) return List.of("Pre-prune save failed; nothing was deleted.");
        Config config = host.network.serverConfig();
        String backup = new ServerBackupAdmin(config.saveDir, config.saveName, config.backupCount).create("pre-prune");
        if (!backup.startsWith("Created backup")) return List.of(backup, "Nothing was deleted.");
        Set<String> deleted = host.world.pruneEmptyDynamicSystems();
        host.network.notifyDeletedSystems(deleted);
        host.network.resyncAllServerPlayers();
        host.network.serverJournal().add("PRUNE", "systems", "deleted " + deleted.size());
        return List.of(backup, "Pruned " + deleted.size() + " abandoned dynamic system" + (deleted.size() == 1 ? "" : "s") + ".",
                deleted.isEmpty() ? "Deleted: none" : "Deleted: " + String.join(", ", deleted));
    }
'''

extensions_new = '''    private static List<String> prune(HeadlessGameServer host, ServerCommandDispatcher.Target target, List<String> args) {
        List<GalaxyMapSystem> candidates = pruneCandidates(host.world);
        if (args == null || args.isEmpty() || "preview".equalsIgnoreCase(args.get(0))) {
            ArrayList<String> lines = new ArrayList<>();
            lines.add("Eligible abandoned dynamic systems: " + candidates.size());
            for (GalaxyMapSystem system : candidates) lines.add(system.id() + " | " + system.name() + " | resources " + system.resources());
            if (candidates.isEmpty()) lines.add("No systems are currently eligible.");
            else lines.add("Run 'prune-systems run confirm' to create a backup and prune.");
            return List.copyOf(lines);
        }
        if (args.size() != 2 || !"run".equalsIgnoreCase(args.get(0)) || !"confirm".equalsIgnoreCase(args.get(1))) {
            return List.of("Usage: prune-systems <preview|run confirm>");
        }
        if (candidates.isEmpty()) return List.of("No abandoned dynamic systems are eligible.");

        Config config = host.network.serverConfig();
        ServerBackupAdmin backupAdmin = new ServerBackupAdmin(config.saveDir, config.saveName, config.backupCount);
        ServerPruneTransaction.Result result = ServerPruneTransaction.run(new ServerPruneTransaction.Operations() {
            @Override public boolean save(String reason) {
                return host.saveForAdmin(reason);
            }

            @Override public ServerBackupAdmin.BackupCreation createBackup(String label) {
                return backupAdmin.createVerified(label);
            }

            @Override public Set<String> prune() {
                return host.world.pruneEmptyDynamicSystems();
            }

            @Override public ServerBackupAdmin.Verification verifyCurrent() {
                return backupAdmin.verifyCurrent();
            }

            @Override public String restoreCurrent(Path backup) {
                return backupAdmin.restoreCurrent(backup);
            }

            @Override public void enterRecovery(String reason) {
                host.enterRecoveryRequired(reason);
            }

            @Override public void publish(Set<String> deleted) {
                host.network.notifyDeletedSystems(deleted);
                host.network.resyncAllServerPlayers();
                host.network.serverJournal().add("PRUNE", "systems", "deleted " + deleted.size());
            }
        });
        return result.lines();
    }
'''
replace_once("src/main/java/com/tndmadman/rts/ServerCommandExtensions.java", extensions_old, extensions_new)

admin_create_old = '''    String create(String label) {
        Path current = currentPath();
        if (!Files.isRegularFile(current)) return "Current save does not exist.";
        String suffix = cleanLabel(label);
        String base = saveName + "-" + STAMP.format(Instant.now()) + (suffix.isBlank() ? "" : "-" + suffix);
        try {
            Files.createDirectories(saveDir);
            Path target = uniquePath(base);
            Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES);
            Verification verification = verify(target);
            if (!verification.valid()) {
                Files.deleteIfExists(target);
                return "Backup verification failed: " + verification.detail();
            }
            return "Created backup " + target.getFileName() + ".";
        } catch (IOException ex) {
            return "Could not create backup: " + ex.getMessage();
        }
    }
'''

admin_create_new = '''    String create(String label) {
        return createVerified(label).message();
    }

    BackupCreation createVerified(String label) {
        Path current = currentPath();
        if (!Files.isRegularFile(current)) {
            return new BackupCreation(false, null, "Current save does not exist.");
        }
        String suffix = cleanLabel(label);
        String base = saveName + "-" + STAMP.format(Instant.now()) + (suffix.isBlank() ? "" : "-" + suffix);
        try {
            Files.createDirectories(saveDir);
            Path target = uniquePath(base);
            Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES);
            Verification verification = verify(target);
            if (!verification.valid()) {
                Files.deleteIfExists(target);
                return new BackupCreation(false, null, "Backup verification failed: " + verification.detail());
            }
            return new BackupCreation(true, target, "Created backup " + target.getFileName() + ".");
        } catch (IOException ex) {
            return new BackupCreation(false, null, "Could not create backup: " + ex.getMessage());
        }
    }
'''
replace_once("src/main/java/com/tndmadman/rts/ServerAdministration.java", admin_create_old, admin_create_new)

admin_verify_old = '''    String verifySelector(String selector) {
        Path target = resolveSelector(selector);
        if (target == null) return "Unknown backup selector: " + selector;
        Verification result = verify(target);
        return (result.valid() ? "Valid: " : "Invalid: ") + target.getFileName() + " | " + result.detail();
    }
'''

admin_verify_new = '''    String verifySelector(String selector) {
        Path target = resolveSelector(selector);
        if (target == null) return "Unknown backup selector: " + selector;
        Verification result = verify(target);
        return (result.valid() ? "Valid: " : "Invalid: ") + target.getFileName() + " | " + result.detail();
    }

    Verification verifyCurrent() {
        return verify(currentPath());
    }

    String restoreCurrent(Path backup) {
        Verification source = verify(backup);
        if (!source.valid()) return "Could not restore current save: recovery backup is invalid: " + source.detail();
        Path temp = null;
        try {
            Files.createDirectories(saveDir);
            temp = Files.createTempFile(saveDir, saveName + "-restore-", ".tmp");
            Files.copy(backup, temp, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Verification staged = verify(temp);
            if (!staged.valid()) return "Could not restore current save: staged backup is invalid: " + staged.detail();
            moveReplace(temp, currentPath());
            temp = null;
            Verification restored = verify(currentPath());
            if (!restored.valid()) return "Could not restore current save: restored archive is invalid: " + restored.detail();
            return "Restored current save from " + backup.getFileName() + ".";
        } catch (IOException ex) {
            return "Could not restore current save: " + ex.getMessage();
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
'''
replace_once("src/main/java/com/tndmadman/rts/ServerAdministration.java", admin_verify_old, admin_verify_new)
replace_once("src/main/java/com/tndmadman/rts/ServerAdministration.java", "    private Verification verify(Path path) {\n", "    Verification verify(Path path) {\n")

admin_paths_old = '''    private Path currentPath() { return saveDir.resolve(saveName + "-current" + EXTENSION); }
    private Path previousPath() { return saveDir.resolve(saveName + "-previous" + EXTENSION); }
'''
admin_paths_new = '''    private Path currentPath() { return saveDir.resolve(saveName + "-current" + EXTENSION); }
    private Path previousPath() { return saveDir.resolve(saveName + "-previous" + EXTENSION); }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
'''
replace_once("src/main/java/com/tndmadman/rts/ServerAdministration.java", admin_paths_old, admin_paths_new)
replace_once(
    "src/main/java/com/tndmadman/rts/ServerAdministration.java",
    "    private record Verification(boolean valid, String detail) { }\n",
    "    record BackupCreation(boolean success, Path path, String message) { }\n    record Verification(boolean valid, String detail) { }\n",
)

replace_once(
    "src/main/java/com/tndmadman/rts/HeadlessGameServer.java",
    '''    private volatile ServerShutdownResult lastShutdownResult;
    private volatile String lastSaveFailure = "";
''',
    '''    private volatile ServerShutdownResult lastShutdownResult;
    private volatile String lastSaveFailure = "";
    private volatile String recoveryRequiredReason = "";
''',
)
replace_once(
    "src/main/java/com/tndmadman/rts/HeadlessGameServer.java",
    '''        shutdownDeadlineNanos = NO_SHUTDOWN;
        boolean saved = saveNow("shutdown");
''',
    '''        shutdownDeadlineNanos = NO_SHUTDOWN;
        boolean recoveryStop = !recoveryRequiredReason.isBlank();
        boolean saved = recoveryStop || saveNow("shutdown");
        if (recoveryStop) {
            System.err.println("Skipping final save because recovery-required state must remain on disk; restarting will load the restored save.");
        }
''',
)
replace_once(
    "src/main/java/com/tndmadman/rts/HeadlessGameServer.java",
    '''        String maintenance = accessPolicy.maintenance() ? " | maintenance" : "";
        String slots = accessPolicy.maxSlots() <= 0 ? "" : " | slots " + sortedSessions().size() + "/" + accessPolicy.maxSlots();
        return statusLine() + " | save " + config.saveName + " | " + autosave + maintenance + slots + shutdown;
''',
    '''        String maintenance = accessPolicy.maintenance() ? " | maintenance" : "";
        String slots = accessPolicy.maxSlots() <= 0 ? "" : " | slots " + sortedSessions().size() + "/" + accessPolicy.maxSlots();
        String recovery = recoveryRequiredReason.isBlank() ? "" : " | RECOVERY REQUIRED";
        return statusLine() + " | save " + config.saveName + " | " + autosave + maintenance + slots + shutdown + recovery;
''',
)
headless_save_old = '''    boolean saveForAdmin(String reason) { return saveNow(reason); }

    private boolean saveNow(String reason) {
        try {
'''
headless_save_new = '''    boolean saveForAdmin(String reason) { return saveNow(reason); }

    void enterRecoveryRequired(String reason) {
        String clean = cleanNotice(reason);
        recoveryRequiredReason = clean.isBlank() ? "Prune recovery requires a server restart." : clean;
        runtimeAutosaveSeconds = 0;
        nextAutosaveNanos = Long.MAX_VALUE;
        network.setSimulationPaused(true, recoveryRequiredReason);
        network.broadcastServerNotice("Server entered recovery-required mode. Restart the server before continuing.");
        System.err.println("Server recovery required: " + recoveryRequiredReason);
    }

    String recoveryRequiredReason() { return recoveryRequiredReason; }

    private boolean saveNow(String reason) {
        if (!recoveryRequiredReason.isBlank()) {
            lastSaveFailure = "Recovery required; restart without saving: " + recoveryRequiredReason;
            if (!"autosave".equals(reason)) System.err.println("Server save blocked (" + reason + "): " + lastSaveFailure);
            return false;
        }
        try {
'''
replace_once("src/main/java/com/tndmadman/rts/HeadlessGameServer.java", headless_save_old, headless_save_new)

transaction_source = '''package com.tndmadman.rts;

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
'''
Path("src/main/java/com/tndmadman/rts/ServerPruneTransaction.java").write_text(transaction_source, encoding="utf-8")

validator_source = '''package com.tndmadman.rts;

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
'''
Path("src/main/java/com/tndmadman/rts/ServerPruneTransactionValidator.java").write_text(validator_source, encoding="utf-8")

build = Path("build.gradle")
build_text = build.read_text(encoding="utf-8")
if "validatePrunePersistence" in build_text:
    raise SystemExit("validatePrunePersistence already exists")
build_text += '''

tasks.register('validatePrunePersistence', JavaExec) {
    group = 'verification'
    description = 'Validate durable system-prune persistence, recovery, and publication ordering.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.ServerPruneTransactionValidator'
}

tasks.named('check') {
    dependsOn tasks.named('validatePrunePersistence')
}
'''
build.write_text(build_text, encoding="utf-8")
