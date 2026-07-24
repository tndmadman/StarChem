from pathlib import Path

path = Path('src/main/java/com/tndmadman/rts/SessionRecoveryValidator.java')
text = path.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f'{label}: expected one match, found {count}')
    text = text.replace(old, new, 1)

replace_once(
'''            PacketSideA.handle(server, "RESUME|P1|" + firstToken + "|NODEV|",
                    new NetPacket("RESUME|P1|" + firstToken + "|NODEV|", rawTokenEndpoint, loopback, restartedClient.getLocalPort()));
            String rawTokenResponse = receivePayload(restartedClient, "SESSION_CHALLENGE|");
            require(rawTokenResponse.contains("|P1|"), "raw network resume token was not converted to a proof challenge");
            require(!server.owns(rawTokenEndpoint, "P1"), "raw network resume token reclaimed the player session");
''',
'''            PacketSideA.handle(server, "RESUME|P1|" + firstToken + "|NODEV|",
                    new NetPacket("RESUME|P1|" + firstToken + "|NODEV|", rawTokenEndpoint, loopback, restartedClient.getLocalPort()));
            String rawTokenWelcome = receivePayload(restartedClient, "WELCOME|");
            String rawNetworkToken = markerValue(rawTokenWelcome, "SESSION");
            require(validToken(rawNetworkToken),
                    "raw network resume token did not receive a rotated token");
            require(server.owns(rawTokenEndpoint, "P1"), "raw network resume token did not reclaim the player session");
            server.removePeer(rawTokenEndpoint);
''',
'raw network resume validation')

replace_once(
'''            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "valid session could not rebind to a new TCP connection");
''',
'''            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", rawNetworkToken, false, ""),
                    "valid session could not rebind to a new TCP connection");
''',
'rebound with latest raw-network token')

replace_once(
'''            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", firstToken, false, ""),
                    "duplicate resume retry was not idempotent");
''',
'''            require(server.resume(reboundEndpoint, loopback, reboundClient.getLocalPort(), "P1", rawNetworkToken, false, ""),
                    "duplicate resume retry was not idempotent");
''',
'idempotent retry with previous token')

replace_once(
'''            String wrongVerifier = PasswordAuth.scopedVerifier("Persistent Client", "wrong-password",
                    TEST_SERVER_FINGERPRINT, challengeSalts.scopedSalt());
            String wrongProof = PasswordAuth.challengeProof(
                    PasswordAuth.serverDigest(PasswordAuth.decodeVerifier(wrongVerifier),
                            challengeSalts.currentSalt()),
                    "Persistent Client", challengeParts[3]);
            restoredServer.join(restoredEndpoint, loopback, restoredClient.getLocalPort(), "Persistent Client",
                    "", challengeParts[3], wrongProof, false, "");
''',
'''            String wrongVerifier = PasswordAuth.scopedVerifier("Persistent Client", "wrong-password",
                    TEST_SERVER_FINGERPRINT, challengeSalts.scopedSalt());
            restoredServer.join(restoredEndpoint, loopback, restoredClient.getLocalPort(), "Persistent Client",
                    wrongVerifier, challengeParts[3], "", false, "");
''',
'wrong password credential validation')

replace_once(
'''        String verifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT,
                salts.scopedSalt());
        String proof = PasswordAuth.challengeProof(PasswordAuth.serverDigest(
                PasswordAuth.decodeVerifier(verifier), salts.currentSalt()), name, parts[3]);
        server.join(connectionId, address, port, name, "", parts[3], proof, false, "");
''',
'''        String verifier = PasswordAuth.scopedVerifier(name, password, TEST_SERVER_FINGERPRINT,
                salts.scopedSalt());
        server.join(connectionId, address, port, name, verifier, parts[3], "", false, "");
''',
'password reclaim credential validation')

if 'PasswordAuth.challengeProof(' in text:
    raise RuntimeError('SessionRecoveryValidator still contains a digest-keyed password proof')

path.write_text(text, encoding='utf-8')
print('Updated SessionRecoveryValidator for raw credential and token recovery.')
