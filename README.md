# StarChem

StarChem is a Java 17 2D top-down space RTS with solo play and dedicated-server multiplayer.

## StarChem v1.8.0

The v1.8.0 release line uses:

- multiplayer protocol **17**;
- rules version **27**;
- dedicated-server save format **6**.

Published StarChem v1.7.0 multiplayer uses protocol 8 and is intentionally incompatible with v1.8.0. Persistent v1.7.0 servers can be upgraded through the validated migration path described in [`UPGRADING_TO_1.8.0.md`](UPGRADING_TO_1.8.0.md).

Release-facing documentation:

- [`PLAY.txt`](PLAY.txt) — shortest player/server launch instructions.
- [`RELEASE_NOTES.md`](RELEASE_NOTES.md) — v1.8.0 feature and compatibility summary.
- [`UPGRADING_TO_1.8.0.md`](UPGRADING_TO_1.8.0.md) — required persistent-server upgrade procedure.
- [`AUTHENTICATION.md`](AUTHENTICATION.md) — commander accounts, password authentication, session resume, and remembered sign-ins.
- [`TLS_IDENTITY_SECURITY.md`](TLS_IDENTITY_SECURITY.md) — server TLS identity, certificate pinning, managed key files, and external keystore configuration.
- [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md) — maintainer tag/publish checklist.

## Download and launch

Download the official release ZIP and its matching `.sha256` file, verify the checksum, then extract the **complete** ZIP before launching. Keep `StarChem.jar`, `config/`, the launchers, and packaged documentation together.

Java 17 or newer is required. Players do not need Gradle or source files.

### Windows player

Run:

```text
run-starchem.bat
```

### Linux player

Run:

```text
./run-starchem.sh
```

### Direct Java launch

```text
java -jar StarChem.jar
```

Run this to print the build identity:

```text
java -jar StarChem.jar --version
```

Run this to display the authoritative command-line option list:

```text
java -jar StarChem.jar --help
```

Unknown options and missing option values are rejected.

## Graphical lobby

The graphical lobby is the normal player entry point. Its primary game actions are **SOLO** and **JOIN**, with additional controls for:

- LAN server discovery and refresh;
- recent-server selection and reconnect;
- direct address/port entry;
- approved read-only observer joining;
- commander sign-in and saved-sign-in clearing;
- solo starting system and galaxy copies;
- skirmish preset and NPC difficulty;
- victory condition;
- diplomacy mode, friendly-fire, shared-vision, and shared-victory settings;
- NPC faction spawn selection;
- Codex access;
- Settings.

JOIN clients receive authoritative world/game state from the server. Solo setup controls do not override a remote dedicated server's saved settings.

## Dedicated server

StarChem multiplayer uses TLS-protected framed TCP. The default game port is `50000`.

### Windows packaged launcher

Run:

```text
run-starchem-server.bat
```

The launcher defaults to:

```text
STARCHEM_PORT=50000
STARCHEM_SERVER_NAME=StarChem-Server
```

On Windows it also chooses a per-user server-data directory by default:

```text
%LOCALAPPDATA%\StarChem\server
```

Override it before launch when needed:

```text
set STARCHEM_SERVER_SAVE_DIR=D:\StarChemServer
set STARCHEM_PORT=50100
set STARCHEM_SERVER_NAME=Public Server
run-starchem-server.bat
```

### Linux packaged launcher

Run:

```text
./run-starchem-server.sh
```

The Linux launcher defaults to TCP port `50000`, server name `StarChem-Server`, and the application's normal dedicated-server `saves` directory under the extracted launch folder. Override the save directory with an explicit application option:

```text
STARCHEM_PORT=50100 STARCHEM_SERVER_NAME="Public Server" \
  ./run-starchem-server.sh --save-dir /srv/starchem
```

### Direct dedicated-server launch

```text
java -Djava.awt.headless=true -jar StarChem.jar \
  --server 50000 \
  --name StarChem-Server
```

Important dedicated-server options include:

```text
--server [PORT]
--name NAME
--system SYSTEM_ID
--galaxy-copies 1|2
--skirmish-preset peaceful|standard|hostile|sandbox
--npc-difficulty relaxed|normal|hard|brutal
--victory-condition ID
--save-dir DIR
--save-name NAME
--autosave-seconds N
--backup-count N
--new-world
--disable-events
--enable-events
--event-frequency 0..4
--event-categories LIST
```

Use `java -jar StarChem.jar --help` rather than copying an old option list from a prior release.

### Persistent settings and new worlds

World/scenario state is authoritative and persisted. Changing launch arguments on a later restart does not silently rewrite an existing world's saved policy. Use `--new-world` only when you intentionally want a new session rather than the existing persistent world.

Dynamic galaxy-event startup controls accept `all`, `none`, or comma-separated configured category IDs for `--event-categories`. Event frequency is clamped to the supported 0..4 range by the application parser.

### Network exposure

Internet-hosted servers must allow inbound **TCP** traffic on the selected game port. Do not expose unrelated local management services merely because the StarChem game port is public.

Stop the dedicated server through its console `stop` / `shutdown` command or a normal process termination path. The server performs an authoritative save/transport shutdown sequence and reports whether shutdown was clean.

## Dedicated-server console

The authoritative dedicated-server console provides administration, save/backup, player/session, moderation, observation, system, performance, production/research, and developer commands.

Do not maintain an external copied list as the source of truth. At the server console, enter:

```text
help
```

or:

```text
help <command>
```

Useful discovery/diagnostic commands include `status`, `server-info`, `save-info`, `players`, `sessions`, `systems`, `health`, `perf`, `version`, and `help`. The running server's help output is authoritative for the exact build.

Runtime operator changes that are explicitly process-scoped, such as runtime developer mode or pause state, should not be assumed to persist after restart unless the command/help states otherwise.

## Server data and backups

A persistent server is more than one `.starchem-save` file. Depending on enabled/current features, the server-data directory can contain:

- current, previous, and timestamped save archives;
- TLS identity and password files;
- retained identity/session state;
- authentication-decoy material;
- administration and moderation state;
- observations and activity records;
- recovery and previous-state files;
- persistent fog/intelligence/discovery state;
- event and other companion state.

Back up the **entire server-data directory as one coherent set**. Do not publish a real server backup in a bug report: it can contain security-sensitive and moderation data.

Before moving a persistent server between versions, read [`UPGRADING_TO_1.8.0.md`](UPGRADING_TO_1.8.0.md).

## TLS server identity

StarChem verifies a remote server's TLS certificate before sending reusable login material. A changed pinned fingerprint fails closed so credentials are not automatically sent to a different certificate.

Managed dedicated-server TLS files normally live beside the save as:

```text
<save-name>-tls.p12
<save-name>-tls.password
```

Do not delete them to resolve a startup problem. Losing or replacing the key can change the identity clients have pinned. The server can also use an operator-supplied PKCS#12 keystore/password file through the configuration documented in [`TLS_IDENTITY_SECURITY.md`](TLS_IDENTITY_SECURITY.md).

## Commander authentication

Commander accounts use TLS-protected password challenge/proof authentication and resumable session tokens. The server does not store raw player passwords.

The stock graphical JOIN flow allows local/loopback creation of an unused commander name. Remote JOIN is presented as sign-in to an existing commander. An explicit server-side remote-registration bridge exists but is disabled by default and should be enabled only when the operator intentionally wants that admission model. See [`AUTHENTICATION.md`](AUTHENTICATION.md).

Remembered reusable credentials use the operating-system credential service when available (Windows DPAPI, macOS Keychain, Linux Secret Service), with an owner-only fallback where necessary. Clearing remembered sign-ins does not erase the server certificate trust record or client-device identifier.

## Observer sessions

The graphical lobby can request **Join as approved observer**. Observer sessions require server approval/invitation and are read-only. The server, not the client UI, enforces observer visibility and rejection of gameplay/developer mutations.

## Developer access

`--dev` does not by itself grant a remote client authority over a dedicated server.

For a protected dedicated-server developer token, keep the token in an owner-only file and pass only its path:

Windows server:

```text
set STARCHEM_DEV_TOKEN_FILE=C:\secure\starchem-dev-token.txt
run-starchem-server.bat --dev
```

Linux server:

```text
umask 077
printf '%s\n' 'replace-with-a-strong-random-token' > /secure/starchem-dev-token
STARCHEM_DEV_TOKEN_FILE=/secure/starchem-dev-token ./run-starchem-server.sh --dev
```

A direct client launch can request developer access with a matching protected token file:

```text
java -jar StarChem.jar --join HOST 50000 --dev --dev-token-file /secure/client-dev-token
```

The legacy `--dev-token TOKEN` argument remains supported but is intentionally discouraged because command-line secrets can leak through shell history, process listings, service definitions, diagnostics, or crash reports.

## Gameplay reference

The in-game UI is the primary current reference for loaded rules:

- `F1` — searchable Codex for ships, stations, resources, research, recipes, factions, and controls.
- `I` — resource/material catalog and natural-resource placement information.
- `M` — galaxy map.
- tactical minimap — contacts, resources, wormholes, friendly assets, pings, and current camera area.

Rules and manufacturing data are loaded from the packaged `config/` directory. Multiplayer compatibility includes a packaged configuration fingerprint, so do not casually mix configuration files from different builds.

## Development and verification

Local source builds resolve to the repository development version (`1.8.0-dev` during v1.8 preparation). Release builds receive the final semantic version and build commit through the release workflow.

The normal repository verification command is:

```text
gradle clean check jar --no-daemon
```

The canonical release regression gate is:

```text
bash validation/run-release-regressions.sh 'build/classes/java/main:build/resources/main'
```

That gate includes release-metadata/docs checks, permanent regression validators, and the real published-v1.7.0-to-current persistence migration test.

## Release process

StarChem uses `.github/workflows/release.yml` as the current release publisher. The old v1.7.0 one-shot workflow is retained only as a read-only historical tombstone and has no release-write capability.

Before tagging, follow [`RELEASE_CHECKLIST.md`](RELEASE_CHECKLIST.md). In summary:

1. Keep `RELEASE_NOTES.md`, `gradle.properties`, runtime fallback identity, protocol/rules/save documentation, and upgrade docs consistent.
2. Require the exact final `main` commit to pass normal CI and the generic release-package workflow.
3. Tag that exact immutable commit with `v1.8.0`.
4. The tag-triggered workflow rebuilds and verifies the JAR, runs canonical regressions, creates a deterministic ZIP and checksum, validates the extracted Linux package, validates Windows launchers, and only then publishes the GitHub Release assets.
5. Do not manually rebuild or substitute release artifacts after the validated workflow.

## License

StarChem is proprietary software. Copyright © 2026 tndmadman. All rights reserved.

The source code is visible for inspection only. Public repository access does not grant permission to copy, compile, modify, redistribute, publish, sell, reuse, or incorporate StarChem code, rules data, assets, or other protected material into another project.

Official unmodified compiled releases may be run only under the limited personal, non-commercial permission stated in [`LICENSE`](LICENSE). StarChem is not open source. Outside implementation contributions are not currently accepted; see [`CONTRIBUTING.md`](CONTRIBUTING.md). Third-party notice policy is documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
