package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Shared verified current/previous storage for security-sensitive companion files. */
final class CompanionStateFiles {
    private CompanionStateFiles() { }

    static <T> CompanionLoad<T> load(Path current, Path previous, String label,
                                     CompanionParser<T> parser, Function<String,T> restrictedFallback) {
        ArrayList<String> failures = new ArrayList<>();
        if (!Files.exists(current) && !Files.exists(previous)
                && CompanionRecoveryRegistry.initializeMissingDefaults()) {
            try {
                String initial = initialState(label);
                if (initial.isBlank()) throw new IOException("no initial companion state is defined for " + label);
                T value = seedInitial(current, initial, parser);
                return new CompanionLoad<>(value, CompanionLoadStatus.current("initialized verified defaults"));
            } catch (Exception ex) {
                failures.add("initialization: " + detail(ex));
            }
        }

        if (Files.isRegularFile(current)) {
            try {
                T value = parser.parse(Files.readString(current, StandardCharsets.UTF_8));
                return new CompanionLoad<>(value, CompanionLoadStatus.current("current file loaded"));
            } catch (Exception ex) {
                failures.add("current: " + detail(ex));
            }
        } else failures.add("current: file is missing");

        if (Files.isRegularFile(previous)) {
            try {
                T value = parser.parse(Files.readString(previous, StandardCharsets.UTF_8));
                String recovery = "recovered from previous file after " + String.join("; ", failures);
                return new CompanionLoad<>(value, CompanionLoadStatus.previous(recovery));
            } catch (Exception ex) {
                failures.add("previous: " + detail(ex));
            }
        } else failures.add("previous: file is missing");

        String failure = label + " current and previous files were unavailable (" + String.join("; ", failures) + ")";
        return new CompanionLoad<>(restrictedFallback.apply(failure), CompanionLoadStatus.restricted(failure));
    }

    static <T> void save(Path current, Path previous, T value,
                         CompanionParser<T> parser, CompanionSerializer<T> serializer) throws IOException {
        save(current, previous, value, parser, serializer,
                (source, target, options) -> Files.move(source, target, options));
    }

    static <T> void save(Path current, Path previous, T value,
                         CompanionParser<T> parser, CompanionSerializer<T> serializer,
                         CompanionMove move) throws IOException {
        Path parent = current.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path currentTemp = current.resolveSibling(current.getFileName() + ".tmp");
        Path previousTemp = previous.resolveSibling(previous.getFileName() + ".tmp");
        try {
            writeVerified(currentTemp, value, parser, serializer);
            String verifiedCurrent = verifiedText(current, parser);
            if (verifiedCurrent != null) {
                Files.writeString(previousTemp, verifiedCurrent, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                parser.parse(Files.readString(previousTemp, StandardCharsets.UTF_8));
                moveReplacing(previousTemp, previous, move);
                parser.parse(Files.readString(previous, StandardCharsets.UTF_8));
            }
            moveReplacing(currentTemp, current, move);
            parser.parse(Files.readString(current, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("companion file verification failed: " + detail(ex), ex);
        } finally {
            Files.deleteIfExists(currentTemp);
            Files.deleteIfExists(previousTemp);
        }
    }

    static <T> void repairCurrent(Path current, T value,
                                  CompanionParser<T> parser, CompanionSerializer<T> serializer) throws IOException {
        Path parent = current.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = current.resolveSibling(current.getFileName() + ".recovery.tmp");
        try {
            writeVerified(temp, value, parser, serializer);
            moveReplacing(temp, current, (source, target, options) -> Files.move(source, target, options));
            parser.parse(Files.readString(current, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("recovered companion file verification failed: " + detail(ex), ex);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void moveReplacing(Path source, Path target, CompanionMove move) throws IOException {
        try {
            move.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            move.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            try {
                move.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicFailure);
                throw fallbackFailure;
            }
        }
    }

    private static <T> T seedInitial(Path current, String initial, CompanionParser<T> parser) throws Exception {
        Path parent = current.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = current.resolveSibling(current.getFileName() + ".initial.tmp");
        try {
            Files.writeString(temp, initial.endsWith("\n") ? initial : initial + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            parser.parse(Files.readString(temp, StandardCharsets.UTF_8));
            moveReplacing(temp, current, (source, target, options) -> Files.move(source, target, options));
            return parser.parse(Files.readString(current, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String initialState(String label) {
        if ("Administration".equals(label)) {
            return "{\"version\":1,\"maintenance\":false,\"maintenanceReason\":\"\",\"maxSlots\":0,\"motd\":\"\"}";
        }
        if ("Moderation".equals(label)) {
            return "{\"version\":1,\"whitelistEnabled\":false,\"whitelist\":[],\"entries\":[]}";
        }
        return "";
    }

    private static <T> String verifiedText(Path path, CompanionParser<T> parser) {
        if (!Files.isRegularFile(path)) return null;
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            parser.parse(text);
            return text;
        } catch (Exception invalidCurrent) {
            return null;
        }
    }

    private static <T> void writeVerified(Path path, T value,
                                          CompanionParser<T> parser, CompanionSerializer<T> serializer) throws Exception {
        String text = serializer.serialize(value);
        Files.writeString(path, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        parser.parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    private static String detail(Exception ex) {
        String message = ex == null ? "unknown failure" : ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

enum CompanionLoadSource { CURRENT, PREVIOUS, RESTRICTED }

record CompanionLoadStatus(CompanionLoadSource source, String detail) {
    CompanionLoadStatus {
        source = source == null ? CompanionLoadSource.RESTRICTED : source;
        detail = detail == null ? "" : detail.trim();
    }

    static CompanionLoadStatus current(String detail) {
        return new CompanionLoadStatus(CompanionLoadSource.CURRENT, detail);
    }

    static CompanionLoadStatus previous(String detail) {
        return new CompanionLoadStatus(CompanionLoadSource.PREVIOUS, detail);
    }

    static CompanionLoadStatus restricted(String detail) {
        return new CompanionLoadStatus(CompanionLoadSource.RESTRICTED, detail);
    }

    boolean recoveredPrevious() { return source == CompanionLoadSource.PREVIOUS; }
    boolean restricted() { return source == CompanionLoadSource.RESTRICTED; }

    String summary(String label) {
        String safe = label == null || label.isBlank() ? "Companion state" : label;
        return switch (source) {
            case CURRENT -> safe + ": current file loaded.";
            case PREVIOUS -> safe + ": recovered from previous file. " + detail;
            case RESTRICTED -> safe + ": recovery failed; restricted admission is active. " + detail;
        };
    }
}

record CompanionLoad<T>(T value, CompanionLoadStatus status) { }

@FunctionalInterface
interface CompanionParser<T> {
    T parse(String text) throws Exception;
}

@FunctionalInterface
interface CompanionSerializer<T> {
    String serialize(T value) throws Exception;
}

@FunctionalInterface
interface CompanionMove {
    void move(Path source, Path target, CopyOption... options) throws IOException;
}

/** Process-wide recovery state for a dedicated server's save identity. */
final class CompanionRecoveryRegistry {
    private static Path markerPath;
    private static String markerReason = "";
    private static CompanionLoadStatus administration = CompanionLoadStatus.current("not loaded");
    private static CompanionLoadStatus moderation = CompanionLoadStatus.current("not loaded");
    private static boolean administrationReady;
    private static boolean moderationReady;
    private static boolean operatorResetPending;
    private static boolean restrictedAdmissionEnabled;
    private static boolean initializeMissingDefaults;

    private CompanionRecoveryRegistry() { }

    static synchronized void configure(Path saveDir, String saveName) {
        Path dir = saveDir == null ? Path.of("saves") : saveDir;
        String cleanName = Config.cleanSaveName(saveName);
        Path next = dir.resolve(cleanName + "-companion-recovery.lock").toAbsolutePath().normalize();
        if (next.equals(markerPath)) return;
        markerPath = next;
        administration = CompanionLoadStatus.current("not loaded");
        moderation = CompanionLoadStatus.current("not loaded");
        administrationReady = false;
        moderationReady = false;
        operatorResetPending = false;
        restrictedAdmissionEnabled = false;
        markerReason = "";
        initializeMissingDefaults = freshServer(dir, cleanName, markerPath);
        if (Files.isRegularFile(markerPath)) {
            try { markerReason = Files.readString(markerPath, StandardCharsets.UTF_8).trim(); }
            catch (IOException ex) { markerReason = "A prior recovery lock exists but could not be read."; }
        }
    }

    static synchronized boolean initializeMissingDefaults() {
        return initializeMissingDefaults;
    }

    static synchronized void enableRestrictedAdmission() {
        restrictedAdmissionEnabled = true;
        if (administration.restricted()) restrict("Administration state could not be recovered.");
        if (moderation.restricted()) restrict("Moderation state could not be recovered.");
    }

    static synchronized void recordAdministration(CompanionLoadStatus status, boolean ready) {
        administration = status;
        administrationReady = ready;
        if (restrictedAdmissionEnabled && status != null && status.restricted()) {
            restrict("Administration state could not be recovered.");
        }
        completeFreshInitialization();
    }

    static synchronized void recordModeration(CompanionLoadStatus status, boolean ready) {
        moderation = status;
        moderationReady = ready;
        if (restrictedAdmissionEnabled && status != null && status.restricted()) {
            restrict("Moderation state could not be recovered.");
        }
        completeFreshInitialization();
    }

    static synchronized boolean restricted() {
        return restrictedAdmissionEnabled
                && (!markerReason.isBlank() || administration.restricted() || moderation.restricted());
    }

    static synchronized String statusReason() {
        List<String> details = new ArrayList<>();
        if (administration.recoveredPrevious()) details.add("Administration recovered from its previous file");
        if (moderation.recoveredPrevious()) details.add("Moderation recovered from its previous file");
        if (restrictedAdmissionEnabled && administration.restricted()) details.add("administration recovery failed");
        if (restrictedAdmissionEnabled && moderation.restricted()) details.add("moderation recovery failed");
        if (restrictedAdmissionEnabled && !markerReason.isBlank()) details.add("recovery lock: " + markerReason);
        if (restricted()) {
            details.add("new identities are blocked; connected and retained identities may continue or reconnect");
            details.add("run 'maintenance off' from the trusted local console after reviewing the recovered files to reset admission");
        }
        return String.join("; ", details);
    }

    static synchronized void requestOperatorReset() {
        operatorResetPending = true;
    }

    static synchronized void completeAdministrationSave(boolean storedMaintenance) {
        if (!restrictedAdmissionEnabled || !operatorResetPending || storedMaintenance
                || !administrationReady || !moderationReady) return;
        try {
            if (markerPath != null) Files.deleteIfExists(markerPath);
            markerReason = "";
            administration = CompanionLoadStatus.current("operator reset");
            moderation = CompanionLoadStatus.current("operator reset");
            operatorResetPending = false;
        } catch (IOException ex) {
            markerReason = "Could not remove recovery lock: " + ex.getMessage();
            persistMarker();
        }
    }

    static synchronized void resetForTests() {
        markerPath = null;
        markerReason = "";
        administration = CompanionLoadStatus.current("not loaded");
        moderation = CompanionLoadStatus.current("not loaded");
        administrationReady = false;
        moderationReady = false;
        operatorResetPending = false;
        restrictedAdmissionEnabled = false;
        initializeMissingDefaults = false;
    }

    private static void completeFreshInitialization() {
        if (initializeMissingDefaults && administrationReady && moderationReady) initializeMissingDefaults = false;
    }

    private static boolean freshServer(Path dir, String saveName, Path recoveryMarker) {
        if (Files.exists(recoveryMarker)) return false;
        if (Files.exists(dir.resolve(saveName + "-admin.json"))
                || Files.exists(dir.resolve(saveName + "-admin-previous.json"))
                || Files.exists(dir.resolve(saveName + "-moderation.json"))
                || Files.exists(dir.resolve(saveName + "-moderation-previous.json"))) return false;
        if (!Files.isDirectory(dir)) return true;
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .noneMatch(path -> ServerSaveArchiveNames.belongsTo(saveName, path));
        } catch (IOException ex) {
            System.err.println("Could not inspect server save history for companion initialization: " + ex.getMessage());
            return false;
        }
    }

    private static void restrict(String reason) {
        if (!restrictedAdmissionEnabled) return;
        String safe = reason == null ? "Companion state recovery failed." : reason.trim();
        if (markerReason.isBlank()) markerReason = safe;
        else if (!markerReason.toLowerCase(Locale.ROOT).contains(safe.toLowerCase(Locale.ROOT))) markerReason += " " + safe;
        persistMarker();
    }

    private static void persistMarker() {
        if (!restrictedAdmissionEnabled || markerPath == null) return;
        try {
            Path parent = markerPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temp = markerPath.resolveSibling(markerPath.getFileName() + ".tmp");
            Files.writeString(temp, markerReason + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            CompanionStateFiles.moveReplacing(temp, markerPath,
                    (source, target, options) -> Files.move(source, target, options));
        } catch (IOException ex) {
            System.err.println("Could not persist companion recovery lock: " + ex.getMessage());
        }
    }
}
