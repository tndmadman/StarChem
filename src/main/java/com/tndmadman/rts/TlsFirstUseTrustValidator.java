package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;

/** Regression coverage for explicit first-use TLS trust and pinned-certificate replacement rules. */
public final class TlsFirstUseTrustValidator {
    private static final String FIRST_FINGERPRINT = "11".repeat(32);
    private static final String SECOND_FINGERPRINT = "22".repeat(32);
    private static final String WRONG_EXPECTED = "33".repeat(32);

    private TlsFirstUseTrustValidator() { }

    public static void main(String[] args) throws Exception {
        Path store = Files.createTempFile("starchem-tls-first-use-", ".properties");
        Files.deleteIfExists(store);
        System.setProperty("starchem.sessionStore", store.toString());
        try {
            validateFirstUseRequiresConfirmation();
            validatePinnedMatchAndMismatch();
            validateEndpointScopedTrust();
            validateLoopbackConvenience();
            System.out.println("StarChem TLS first-use trust validation passed.");
        } finally {
            System.clearProperty("starchem.sessionStore");
            Files.deleteIfExists(store);
        }
    }

    private static void validateFirstUseRequiresConfirmation() throws Exception {
        Config remote = remoteConfig("First Use Commander");
        SessionTokenStore.clearServerFingerprint(remote);

        TlsIdentity.FingerprintChangedException firstUse = expectTrustRequired(remote, FIRST_FINGERPRINT);
        TlsIdentity.FingerprintChange request = firstUse.change();
        require(request != null && request.valid(), "first-use trust request was invalid");
        require(request.firstUse(), "first-use certificate was reported as a replacement certificate");
        require(TlsIdentity.UNVERIFIED_FIRST_USE.equals(request.expected()),
                "first-use trust request did not identify the absence of a previous pin");
        require(FIRST_FINGERPRINT.equals(request.presented()),
                "first-use trust request lost the presented fingerprint");
        require(SessionTokenStore.serverFingerprint(remote).isBlank(),
                "first-use certificate was silently pinned before user approval");

        require(SessionTokenStore.replaceServerFingerprint(remote, request.expected(), request.presented()),
                "approved first-use certificate could not be stored");
        require(FIRST_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(remote)),
                "approved first-use certificate was not persisted");
        TlsIdentity.verifyServerFingerprint(remote, FIRST_FINGERPRINT);
    }

    private static void validatePinnedMatchAndMismatch() throws Exception {
        Config remote = remoteConfig("Pinned Commander");
        SessionTokenStore.clearServerFingerprint(remote);
        SessionTokenStore.saveServerFingerprint(remote, FIRST_FINGERPRINT);

        TlsIdentity.verifyServerFingerprint(remote, FIRST_FINGERPRINT);

        TlsIdentity.FingerprintChangedException changed = expectTrustRequired(remote, SECOND_FINGERPRINT);
        TlsIdentity.FingerprintChange request = changed.change();
        require(request != null && request.valid(), "replacement trust request was invalid");
        require(!request.firstUse(), "changed certificate was misreported as first use");
        require(FIRST_FINGERPRINT.equals(request.expected()),
                "changed certificate request did not preserve the trusted fingerprint");
        require(SECOND_FINGERPRINT.equals(request.presented()),
                "changed certificate request did not preserve the presented fingerprint");
        require(FIRST_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(remote)),
                "mismatching certificate silently replaced the existing pin");

        require(!SessionTokenStore.replaceServerFingerprint(remote, WRONG_EXPECTED, SECOND_FINGERPRINT),
                "stored certificate was replaced without matching the expected existing pin");
        require(FIRST_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(remote)),
                "failed replacement attempt changed the stored certificate");

        require(SessionTokenStore.replaceServerFingerprint(remote, request.expected(), request.presented()),
                "explicitly approved replacement certificate could not be stored");
        require(SECOND_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(remote)),
                "approved replacement certificate was not persisted");
        TlsIdentity.verifyServerFingerprint(remote, SECOND_FINGERPRINT);
    }

    private static void validateEndpointScopedTrust() throws Exception {
        Config firstCommander = remoteConfig("Endpoint Commander A");
        Config secondCommander = remoteConfig("Endpoint Commander B");
        SessionTokenStore.clearServerFingerprint(firstCommander);
        SessionTokenStore.saveServerFingerprint(firstCommander, FIRST_FINGERPRINT);
        require(FIRST_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(secondCommander)),
                "TLS trust was scoped to commander credentials instead of the server endpoint");
        TlsIdentity.verifyServerFingerprint(secondCommander, FIRST_FINGERPRINT);
    }

    private static void validateLoopbackConvenience() throws Exception {
        Config loopback = Config.join("Loopback Commander", "127.0.0.1", 42835, false);
        SessionTokenStore.clearServerFingerprint(loopback);
        require(TlsIdentity.automaticallyTrustLoopbackServer(loopback),
                "loopback server was not recognized for local automatic trust");

        TlsIdentity.verifyServerFingerprint(loopback, FIRST_FINGERPRINT);
        require(FIRST_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(loopback)),
                "loopback first-use fingerprint was not stored automatically");

        TlsIdentity.verifyServerFingerprint(loopback, SECOND_FINGERPRINT);
        require(SECOND_FINGERPRINT.equals(SessionTokenStore.serverFingerprint(loopback)),
                "loopback replacement fingerprint was not updated automatically");
    }

    private static Config remoteConfig(String commander) {
        return Config.join(commander, "203.0.113.10", 42835, false);
    }

    private static TlsIdentity.FingerprintChangedException expectTrustRequired(Config config, String fingerprint)
            throws Exception {
        try {
            TlsIdentity.verifyServerFingerprint(config, fingerprint);
        } catch (TlsIdentity.FingerprintChangedException expected) {
            return expected;
        }
        throw new IllegalStateException("TLS fingerprint was accepted without the required trust decision");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
