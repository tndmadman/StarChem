package com.tndmadman.rts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

/** Verifies private fit libraries recover safely, cache reads, and fail closed when corrupt. */
public final class ClientFitStoreRecoveryValidator {
    private static final String STORE_PROPERTY = "starchem.fitStore";

    private ClientFitStoreRecoveryValidator() { }

    public static void main(String[] args) {
        validate();
        System.out.println("StarChem private fit store recovery validation passed.");
    }

    static void validate() {
        String original = System.getProperty(STORE_PROPERTY);
        Path root = null;
        try {
            root = Files.createTempDirectory("starchem-fit-recovery-");
            PrivateFileSecurity.ensurePrivateDirectory(root);
            Path current = root.resolve("fits.json");
            Path previous = current.resolveSibling(current.getFileName() + ".previous");
            System.setProperty(STORE_PROPERTY, current.toString());
            ClientFitStore.resetCacheForTest();

            require(ClientFitStore.fits("Alic", "").isEmpty(),
                    "a new private fit library did not start empty");
            validateReadCache();

            writePrivate(current, "{broken-current");
            expectCorruptFailure("a corrupt current library without a backup was silently accepted");
            require("{broken-current".equals(Files.readString(current, StandardCharsets.UTF_8)),
                    "failed private fit loading modified the corrupt current file");

            writePrivate(previous, emptyLibrary());
            require(ClientFitStore.fits("Alic", "").isEmpty(),
                    "a verified previous private fit library was not used for recovery");

            ClientFitStore.setStandard("Alic", "scout", "");
            require(ClientFitStore.fits("Alic", "").isEmpty(),
                    "a recovered private fit library could not be persisted");
            require(Files.readString(current, StandardCharsets.UTF_8).contains("\"commanders\""),
                    "persisting recovered state did not repair the current library");
            require(emptyLibrary().equals(Files.readString(previous, StandardCharsets.UTF_8)),
                    "repairing the current library replaced the verified recovery copy");

            writePrivate(current, "{broken-current-again");
            writePrivate(previous, "[broken-previous");
            expectCorruptFailure("two corrupt private fit copies were silently replaced with an empty library");
            require("{broken-current-again".equals(Files.readString(current, StandardCharsets.UTF_8)),
                    "fail-closed loading modified the corrupt current copy");
            require("[broken-previous".equals(Files.readString(previous, StandardCharsets.UTF_8)),
                    "fail-closed loading modified the corrupt recovery copy");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not validate private fit store recovery.", ex);
        } finally {
            ClientFitStore.resetCacheForTest();
            if (original == null) System.clearProperty(STORE_PROPERTY);
            else System.setProperty(STORE_PROPERTY, original);
            deleteTree(root);
        }
    }

    private static void validateReadCache() {
        long loads = ClientFitStore.loadCountForTest();
        long securitySetups = ClientFitStore.securitySetupCountForTest();
        String[] hulls = {"prospector", "station_builder", "hauler", "deep_miner",
                "gas_harvester", "freighter", "salvager", "frigate", "destroyer",
                "cruiser", "battle_cruiser", "battleship", "carrier", "dreadnought",
                "supercarrier", "titan", "monolith"};
        for (int pass = 0; pass < 4; pass++) {
            for (String hull : hulls) {
                require(ClientFitStore.fits("Alic", hull).isEmpty(),
                        "cached empty fit library returned an unexpected fit");
            }
        }
        require(ClientFitStore.loadCountForTest() == loads,
                "repeated hull lookups reloaded and reparsed the private fit store");
        require(ClientFitStore.securitySetupCountForTest() == securitySetups,
                "repeated hull lookups repeated private-file security setup");
    }

    private static void expectCorruptFailure(String failureMessage) {
        try {
            ClientFitStore.fits("Alic", "");
            throw new IllegalStateException(failureMessage);
        } catch (IllegalStateException expected) {
            String message = expected.getMessage() == null ? "" : expected.getMessage();
            require(message.contains("Private fit library is corrupt")
                            && message.contains("no valid recovery copy"),
                    "corrupt private fit diagnostic was not precise: " + message);
        }
    }

    private static void writePrivate(Path path, String text) throws IOException {
        PrivateFileSecurity.ensurePrivateDirectory(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        PrivateFileSecurity.secureFile(path);
    }

    private static String emptyLibrary() {
        return "{\"schemaVersion\":1,\"commanders\":{}}\n";
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
