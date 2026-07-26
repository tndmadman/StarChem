package com.tndmadman.rts;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** Proves that authentication fields copied from a save cannot authenticate as a player. */
public final class SavedCredentialReplayValidator {
    private static final String FINGERPRINT = "77".repeat(32);
    private static final String PLAYER_NAME = "Saved Credential Player";
    private static final String PASSWORD = "saved-credential-password";
    private static final String TOKEN = "saved-token-" + "x".repeat(48);

    private SavedCredentialReplayValidator() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("starchem-saved-credential-");
        try {
            Config config = Config.dedicatedServer("Saved Credential Host", 0, false, false, Set.of(),
                    StarSystems.DEFAULT_SYSTEM_ID, "", 1, directory, "credential-replay", 0, 1, true);
            World world = new World(config.playerName, Set.of(), StarSystems.DEFAULT_SYSTEM_ID, false);
            PlayerRegistry.activate(world);
            PlayerRegistry.reset("SOLO", config.playerName, 0x50BEFF);

            byte[] storedSalt = PasswordAuth.newVersionedPasswordSalt();
            byte[] digestSalt = PasswordAuth.digestSalt(storedSalt);
            String scopedVerifier = PasswordAuth.scopedVerifier(PLAYER_NAME, PASSWORD, FINGERPRINT, digestSalt);
            byte[] passwordDigest = PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(scopedVerifier), digestSalt);
            byte[] tokenDigest = PasswordAuth.tokenDigest(TOKEN);
            PersistentPlayerSession session = new PersistentPlayerSession("P1", PLAYER_NAME, 0x50BEFF,
                    storedSalt, passwordDigest, tokenDigest, new byte[0], 0);

            ServerSaveStore store = new ServerSaveStore(directory, "credential-replay", 1);
            store.save(world, config, "credential-replay-validator", List.of(session));
            Path save = directory.resolve("credential-replay-current.starchem-save");
            Map<String,Object> savedSession = readSavedSession(save);
            byte[] extractedPasswordDigest = PasswordAuth.decodeVerifier(
                    ServerSaveStore.string(savedSession, "passwordVerifierSha256", ""));
            byte[] extractedTokenDigest = Base64.getUrlDecoder().decode(
                    ServerSaveStore.string(savedSession, "tokenDigestSha256", ""));
            byte[] extractedSalt = PasswordAuth.digestSalt(PasswordAuth.decodeHex(
                    ServerSaveStore.string(savedSession, "passwordSalt", "")));

            require(PasswordAuth.passwordCredentialMatches(extractedPasswordDigest,
                            PasswordAuth.decodeVerifier(scopedVerifier), extractedSalt),
                    "correct scoped credential was rejected");
            require(!PasswordAuth.passwordCredentialMatches(extractedPasswordDigest,
                            extractedPasswordDigest, extractedSalt),
                    "password digest copied from the save authenticated as a client credential");
            require(PasswordAuth.sessionTokenMatches(extractedTokenDigest, TOKEN),
                    "correct raw session token was rejected");
            String copiedDigestAsToken = Base64.getUrlEncoder().withoutPadding().encodeToString(extractedTokenDigest);
            require(!PasswordAuth.sessionTokenMatches(extractedTokenDigest, copiedDigestAsToken),
                    "session digest copied from the save authenticated as a raw token");

            V160UpgradeReleaseGate.validateIfRequired();
            System.out.println("StarChem saved credential replay validation passed.");
        } finally {
            deleteTree(directory);
        }
    }

    private static Map<String,Object> readSavedSession(Path save) throws Exception {
        try (ZipFile zip = new ZipFile(save.toFile(), StandardCharsets.UTF_8)) {
            var entry = zip.getEntry("players.json");
            require(entry != null, "players.json was missing from the save");
            try (InputStream input = zip.getInputStream(entry)) {
                Object parsed = MiniJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                Map<String,Object> players = ServerSaveStore.object(parsed);
                List<Object> sessions = ServerSaveStore.list(players.get("sessions"));
                require(sessions.size() == 1, "save did not contain exactly one retained session");
                return ServerSaveStore.object(sessions.get(0));
            }
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
