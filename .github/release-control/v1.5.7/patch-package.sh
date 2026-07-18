#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="${1:?repository directory is required}"
cd "$REPO_DIR"

git fetch origin main --tags
if git rev-parse -q --verify refs/tags/v1.5.7 >/dev/null; then
  echo 'Tag v1.5.7 already exists; refusing to replace it.' >&2
  exit 1
fi

python3 - <<'PY'
from pathlib import Path


def replace_once(path, old, new, label):
    file = Path(path)
    text = file.read_text(encoding='utf-8')
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'Expected exactly one {label}, found {count}')
    file.write_text(text.replace(old, new, 1), encoding='utf-8')


replace_once(
    'src/main/java/com/tndmadman/rts/TlsIdentity.java',
    'import java.net.Socket;\n',
    'import java.net.InetAddress;\nimport java.net.Socket;\n',
    'InetAddress import')

replace_once(
    'src/main/java/com/tndmadman/rts/TlsIdentity.java',
    '''            if (config != null && config.localHostClientMode()) return;
            String pinned = SessionTokenStore.serverFingerprint(config);
            if (pinned.isBlank()) {
                SessionTokenStore.saveServerFingerprint(config, fingerprint);
            } else if (!MessageDigest.isEqual(PasswordAuth.decodeVerifier(pinned), PasswordAuth.decodeVerifier(fingerprint))) {
                throw new FingerprintChangedException(new FingerprintChange(pinned, fingerprint));
            }
''',
    '''            if (config != null && config.localHostClientMode()) return;
            String pinned = SessionTokenStore.serverFingerprint(config);
            if (automaticallyTrustLoopbackServer(config)) {
                if (!fingerprint.equalsIgnoreCase(pinned)) {
                    SessionTokenStore.saveServerFingerprint(config, fingerprint);
                }
                return;
            }
            if (pinned.isBlank()) {
                SessionTokenStore.saveServerFingerprint(config, fingerprint);
            } else if (!MessageDigest.isEqual(PasswordAuth.decodeVerifier(pinned), PasswordAuth.decodeVerifier(fingerprint))) {
                throw new FingerprintChangedException(new FingerprintChange(pinned, fingerprint));
            }
''',
    'TLS pin decision')

replace_once(
    'src/main/java/com/tndmadman/rts/TlsIdentity.java',
    '''    record FingerprintChange(String expected, String presented) {
''',
    '''    static boolean automaticallyTrustLoopbackServer(Config config) {
        if (config == null || config.serverAddress == null) return false;
        InetAddress address = config.serverAddress.getAddress();
        return address != null && address.isLoopbackAddress();
    }

    record FingerprintChange(String expected, String presented) {
''',
    'loopback trust helper')

replace_once(
    'src/main/java/com/tndmadman/rts/NetworkSecurityValidator.java',
    '''  secondServer = PeerTransport.server(secondServerConfig, new PerfStats());
  secondClient = PeerTransport.client(clientConfig, new PerfStats());
  secondServer.start();
  secondClient.start();
  waitFor(secondClient::serverCertificateTrustRequired, 5_000,
          "changed TLS fingerprint did not require an explicit trust decision");
  require(!secondClient.connected(), "client accepted a changed server TLS fingerprint before approval");
  TlsIdentity.FingerprintChange change = secondClient.pendingServerFingerprintChange();
  require(change != null && change.valid() && firstFingerprint.equals(change.expected()),
          "pending TLS trust decision did not retain the expected fingerprint");
  require(secondClient.trustPendingServerCertificate(),
          "explicit TLS fingerprint replacement was rejected");
  waitFor(secondClient::connected, 5_000,
          "client did not reconnect after explicitly trusting the replacement certificate");
  require(change.presented().equals(SessionTokenStore.serverFingerprint(alternateCommander)),
          "replacement TLS trust was not stored for the server endpoint");
''',
    '''  secondServer = PeerTransport.server(secondServerConfig, new PerfStats());
  secondClient = PeerTransport.client(clientConfig, new PerfStats());
  secondServer.start();
  secondClient.start();
  waitFor(secondClient::connected, 5_000,
          "same-machine client did not automatically accept the replacement TLS certificate");
  require(!secondClient.serverCertificateTrustRequired(),
          "same-machine certificate replacement incorrectly required user confirmation");
  String replacementFingerprint = SessionTokenStore.serverFingerprint(clientConfig);
  require(PasswordAuth.validVerifier(replacementFingerprint)
                  && !firstFingerprint.equals(replacementFingerprint),
          "same-machine certificate replacement did not update the stored server trust");
  require(replacementFingerprint.equals(SessionTokenStore.serverFingerprint(alternateCommander)),
          "replacement TLS trust was not stored for the server endpoint");
  Config explicitIpv4Loopback = Config.join("IPv4 Loopback Client", "127.0.0.1", port, false);
  require(TlsIdentity.automaticallyTrustLoopbackServer(explicitIpv4Loopback),
          "127.0.0.1 was not recognized as a same-machine server");
  Config remoteServer = Config.join("Remote TLS Client", "203.0.113.10", port, false);
  require(!TlsIdentity.automaticallyTrustLoopbackServer(remoteServer),
          "remote server was incorrectly allowed automatic certificate replacement");
''',
    'loopback certificate replacement regression')

notes = Path('RELEASE_NOTES.md')
text = notes.read_text(encoding='utf-8')
if not text.startswith('# StarChem v1.5.6\n'):
    raise SystemExit('Expected v1.5.6 release notes before applying v1.5.7')
text = text.replace('# StarChem v1.5.6\n', '# StarChem v1.5.7\n', 1)
text = text.replace(
    'StarChem v1.5.6 is a multiplayer connectivity and dedicated-server save-state hotfix covering changes introduced after v1.4.0.\n',
    'StarChem v1.5.7 is a multiplayer connectivity and dedicated-server save-state hotfix covering changes introduced after v1.4.0.\n',
    1)
marker = '## Multiplayer Connectivity Hotfix\n\n'
addition = (
    '- Same-machine servers reached through `127.0.0.1`, `::1`, or another loopback address now replace stale TLS certificate pins automatically without asking the player.\n'
    '- Graphical HOST mode and a client joining a dedicated server on the same computer no longer show TRUST NEW CERTIFICATE after an update or local TLS-key regeneration.\n'
    '- Certificate changes from non-loopback remote servers remain blocked before login secrets are sent and still require explicit confirmation.\n')
if text.count(marker) != 1:
    raise SystemExit('Release-note hotfix section was not found exactly once')
text = text.replace(marker, marker + addition, 1)
text = text.replace('v1.5.6', 'v1.5.7')
text = text.replace(
    '- On a first connection to a new server, players should treat the server fingerprint like any first-contact trust decision. If the fingerprint changes later, StarChem refuses to send login material.\n',
    '- Same-machine loopback connections update local certificate trust automatically. Non-loopback remote certificate changes remain blocked before StarChem sends login material.\n',
    1)
notes.write_text(text, encoding='utf-8')
PY

grep -Fq 'automaticallyTrustLoopbackServer' src/main/java/com/tndmadman/rts/TlsIdentity.java
grep -Fq 'same-machine certificate replacement incorrectly required user confirmation' src/main/java/com/tndmadman/rts/NetworkSecurityValidator.java
grep -Fx '# StarChem v1.5.7' RELEASE_NOTES.md
! grep -Fq 'StarChem v1.5.6' RELEASE_NOTES.md

BASE_SHA="$(git rev-parse HEAD)"
gradle clean check --no-daemon \
  -PreleaseVersion=1.5.7 \
  -PbuildCommit="$BASE_SHA"

git config user.name 'StarChem Release Bot'
git config user.email 'actions@users.noreply.github.com'
git add RELEASE_NOTES.md \
        src/main/java/com/tndmadman/rts/TlsIdentity.java \
        src/main/java/com/tndmadman/rts/NetworkSecurityValidator.java
git diff --cached --check
git commit -m 'Automatically trust same-machine TLS rotation for v1.5.7'
NEW_SHA="$(git rev-parse HEAD)"
git push origin HEAD:main
echo "new_sha=$NEW_SHA" >> "$GITHUB_OUTPUT"

gradle clean check jar --no-daemon \
  -PreleaseVersion=1.5.7 \
  -PbuildCommit="$NEW_SHA"

rm -rf release
mkdir -p release/StarChem
cp build/libs/StarChem.jar release/StarChem/StarChem.jar
cp -R config release/StarChem/config
cp packaging/run-starchem.bat release/StarChem/run-starchem.bat
cp packaging/run-starchem-server.bat release/StarChem/run-starchem-server.bat
cp packaging/run-starchem.sh release/StarChem/run-starchem.sh
cp packaging/run-starchem-server.sh release/StarChem/run-starchem-server.sh
chmod +x release/StarChem/run-starchem.sh release/StarChem/run-starchem-server.sh
cp README.md release/StarChem/README.md
cp LICENSE release/StarChem/LICENSE
cp THIRD_PARTY_NOTICES.md release/StarChem/THIRD_PARTY_NOTICES.md

COMMIT_EPOCH="$(git show -s --format=%ct "$NEW_SHA")"
find release/StarChem -exec touch -d "@$COMMIT_EPOCH" {} +
(
  cd release
  mapfile -t ENTRIES < <(find StarChem -print | LC_ALL=C sort)
  printf '%s\n' "${ENTRIES[@]}" | zip -X -q StarChem-v1.5.7.zip -@
  printf '%s\n' "${ENTRIES[@]}" | zip -X -q StarChem-v1.5.7.zip.repro -@
  cmp StarChem-v1.5.7.zip StarChem-v1.5.7.zip.repro
  rm StarChem-v1.5.7.zip.repro
)
cp release/StarChem-v1.5.7.zip .
sha256sum StarChem-v1.5.7.zip > StarChem-v1.5.7.zip.sha256
sha256sum -c StarChem-v1.5.7.zip.sha256
java -jar build/libs/StarChem.jar --version | grep -Fx "StarChem 1.5.7 (${NEW_SHA:0:12})"

VERIFY_ROOT="$RUNNER_TEMP/starchem-v157-package"
rm -rf "$VERIFY_ROOT"
mkdir -p "$VERIFY_ROOT"
unzip -q StarChem-v1.5.7.zip -d "$VERIFY_ROOT"
"$VERIFY_ROOT/StarChem/run-starchem.sh" --version | grep -Fx "StarChem 1.5.7 (${NEW_SHA:0:12})"
"$VERIFY_ROOT/StarChem/run-starchem-server.sh" --help | grep -Fq -- '--server [PORT]'
