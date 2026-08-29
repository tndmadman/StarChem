# StarChem

StarChem is a Java 2D top-down multiplayer RTS prototype.

## Download

Download the release ZIP and its matching `.sha256` file, verify the checksum, then extract the complete ZIP.

The player package contains the compiled `StarChem.jar`, the required `config`
folder, Windows and Linux launchers, and the packaged legal and quick-start documents.

On Windows, double-click `run-starchem.bat` to open the lobby. The graphical menu contains only **SOLO** and **JOIN**; multiplayer servers run as separate dedicated-server processes.

On Linux, open a terminal in the extracted folder and run:

```text
./run-starchem.sh
```

Java 17 or newer is required.

Players do not need Gradle, source files, or a local compile step.

## Dedicated server

### Windows

Start a dedicated server from the extracted release folder with:

```text
run-starchem-server.bat
```

The launcher defaults to TCP port `50000` and the server name `StarChem-Server`. Override either value before launching:

```text
set STARCHEM_PORT=50100
set STARCHEM_SERVER_NAME=Public Server
run-starchem-server.bat --galaxy-copies 2
```

For protected remote developer authorization, store the token in an owner-only file and set its path without placing the reusable token in the Java command line:

```text
set STARCHEM_DEV_TOKEN_FILE=C:\secure\starchem-dev-token.txt
run-starchem-server.bat --dev
```

### Linux

Start a headless dedicated server from the extracted release folder with:

```text
./run-starchem-server.sh
```

Override its default port or name with environment variables and pass additional StarChem options after the script name:

```text
STARCHEM_PORT=50100 STARCHEM_SERVER_NAME="Public Server" ./run-starchem-server.sh --galaxy-copies 2
```

Create an owner-only token file and pass only its path through the launcher:

```text
umask 077
printf '%s\n' 'replace-with-a-random-token' > /secure/starchem-dev-token
STARCHEM_DEV_TOKEN_FILE=/secure/starchem-dev-token ./run-starchem-server.sh --dev
```

The token file is read once during startup. Replacing it does not rotate the active token until the server is restarted.

The equivalent direct Java command on either platform is:

```text
java -Djava.awt.headless=true -jar StarChem.jar --server 50000 --name StarChem-Server
```

Choose a new world's scenario and NPC pressure with:

```text
--skirmish-preset peaceful|standard|hostile|sandbox
--npc-difficulty relaxed|normal|hard|brutal
```

Control dynamic galaxy events for a newly created session with:

```text
--disable-events
--enable-events
--event-frequency 0..4
--event-categories all
--event-categories RICH_RESOURCE,DERELICT_SALVAGE,DISTRESS_SIGNAL,PIRATE_AMBUSH,ENVIRONMENTAL,UNSTABLE_WORMHOLE
```

`--event-frequency 1` is the default configured rate, `0` suppresses random event creation without changing the category allow-list, and values up to `4` increase evaluation probability. `--event-categories none` disables all categories while preserving the general event setting. The authoritative event policy is persisted in the server save; restarting an existing save keeps its saved policy rather than silently replacing it from new launch arguments. Use `--new-world` when intentionally changing event policy for a new session.

The graphical lobby applies these settings only to **SOLO**. A JOIN client receives the dedicated server's authoritative settings. Dedicated-server settings are captured in the save and remain unchanged on restart even if later launch arguments differ; use `--new-world` to intentionally create a different scenario.

Open or forward the selected **TCP** port. Stop the server with `Ctrl+C` or a normal termination signal; the server closes its network transport before the process exits. It prints a status line at startup and every 60 seconds while running.

### Console commands

When the dedicated server is attached to an interactive terminal, enter commands directly in that terminal. Input is queued and executed by the authoritative server tick instead of changing game state from the console-reader thread. Closing or redirecting standard input does not stop the server.

Available commands:

```text
help [command]             Show available commands or detailed command help.
status                     Print server, network, save, admission, and autosave status.
players                    List connected and retained player sessions.
leaderboard [top <count>]  Show authoritative cross-system player rankings.
player <player> [assets|research|systems]
                           Show detailed player state.
sessions [connected|retained]
                           Show sanitized session and queue details.
uptime                     Show start time, uptime, save counts, and autosave timing.
perf [all|network|simulation]
                           Show simulation and network performance counters.
health [disk|network|simulation]
                           Show JVM, disk, network, and simulation health.
systems [active|controlled|player <player>]
                           List authoritative galaxy systems.
system <id-or-name>        Show detailed information for one galaxy system.
connection <player>        Show sanitized connection diagnostics.
assets <player|system> <selector> [ships|bases]
asset <unit-or-base-id>    Inspect authoritative ships and bases.
research topics|topic <topic>|status|completed|queued|available|blocked <player>
                           Inspect loaded research rules and player progress.
production <summary|player <player>|system <system>|base <base-id>|stalled>
factions                   Show NPC faction totals and runtime-record count.
faction <id-or-name>       Inspect one NPC faction.
resync <player|all|resources>
                           Resend authoritative state or force resource correction.
server-info [compatibility|tls]
                           Show build, protocol, config fingerprint, and TLS identity.
save-info                  Show current, fallback, and administration-file state.
save                       Write a manual dedicated-server save.
autosave status            Show runtime and startup autosave settings.
autosave set <duration>    Change the interval for the current process.
autosave on|off|reset      Enable, disable, or restore the startup interval.
backups list               List current, previous, and timestamped save archives.
backups create [label]     Save, copy, and checksum-verify a manual backup.
backups verify <selector>  Verify current, previous, or a named archive.
backups prune              Apply the configured backup-retention limit.
maintenance status         Show admission-control state.
maintenance on [reason]    Reject new identities while allowing reconnects.
maintenance off            Allow new player identities again.
slots                      Show connected, retained, and maximum sessions.
slots set <count>          Set the persistent player-session limit.
slots unlimited            Remove the session limit.
motd show|set|clear|send   Manage the persistent message of the day.
whitelist status|on|off|list
whitelist add|remove <player-or-name>
whitelist add-connected    Manage persistent identity admission.
kick <player> [duration] [reason]
kicks                      List active temporary kicks.
unkick <entry-id|player|name>
ban [player|ip|device|mac] <target> <duration|permanent> [--include-stale] [reason]
bans [all|player|ip|device]
unban <entry-id|player|name|target>
                           Manage persistent identity, IP/CIDR, and client-device bans.
pause status|on [reason]|off
                           Pause simulation while networking and administration continue.
activity [last <count>|player <player>|type <type>|clear|export <filename>]
                           Inspect or export the bounded persistent operator journal.
observations [player]      Show retained IP and client-device signals with per-signal ages.
observations delete <player>
observations prune
observations clear confirm  Delete retained observation data.
identity list [active|archived]
identity dormant <age>     Inspect retained identities by lifecycle and inactivity.
identity archive <player> confirm
identity restore <player>
identity delete <player> confirm
                           Archive or permanently delete retained identities.
prune-systems preview      Preview abandoned dynamic systems.
prune-systems run confirm Create a verified backup, then prune eligible systems.
tell <player> <message>    Send one connected player a private server notice.
notice all <message>       Send a scoped server notice.
notice system <system> <message>
threads                    List live JVM threads and states.
memory                     Show JVM heap and non-heap usage.
gc-status                  Show garbage-collector statistics without forcing collection.
dump player|system <selector> [filename]
                           Write a sanitized JSON administration dump.
say <message>              Broadcast a server notice to connected clients.
shutdown now               Save and stop immediately.
shutdown <duration> [reason]
                           Schedule shutdown; durations accept seconds, s, m, h, or d.
shutdown status            Show the pending shutdown.
shutdown cancel            Cancel the pending shutdown.
disconnect <player> [reason]
                           Temporarily disconnect a player while retaining the session.
dev status                 Show runtime developer state.
dev mode status|on|off [confirm]
                           Enable or disable developer controls for this process.
dev access list|requests|grant|revoke|revoke-all ...
dev freebuild status <player>|<player> on|off
dev resource ...           Inspect, add, remove, set, fill, or clear base inventory.
dev research ...           Grant, finish, revoke, cascade, or reset research.
dev ai ...                 Control AI pause, speed, freezes, rules, preset, snapshot, and reload.
dev timers status|on|off   Control production timers at runtime.
dev faction ...            Spawn, inspect, reset, remove, fund, or trigger any NPC faction.
dev production ...         Fund, finish, cancel, move, or clear production jobs.
dev asset ...              Heal, move, or safely destroy an asset.
dev player ...             Heal, relocate, or respawn a player.
dev spawn ...              Spawn validated ships, bases, loot, or attack waves.
version                    Print the running build identity.
stop                       Save and stop immediately.
```

Maintenance mode and the slot limit apply only to brand-new player identities. Existing connected players remain online, and retained identities may reconnect or reclaim their session. Lowering the slot limit never disconnects an existing session. Fresh dedicated servers default to a finite limit of 128 retained identities; operators may change it with `slots set` or explicitly choose `slots unlimited`.

Identity creation and last-seen timestamps, archive state, and the monotonic player-ID high-water mark are stored in `<save-name>-identities.json`. Archived identities keep their names and world state but cannot authenticate until restored. Permanent deletion requires a disconnected player, an explicit `confirm`, a fresh verified backup, and a verified post-deletion save. Deletion removes the session, ships, bases, research, home state, and system ownership; the deleted name becomes reusable while player IDs are never recycled. Identity-scoped whitelist, kick, and player-ban entries are removed, while IP and device bans remain as independent security records.

The message of the day, maintenance state, maintenance reason, and slot limit are stored beside the server save in `<save-name>-admin.json`. Whitelist entries, kicks, and bans are stored in `<save-name>-moderation.json`. The bounded operator journal is retained in `<save-name>-activity.log`, and age-limited last-seen moderation signals are retained in the owner-only `<save-name>-observations.json` companion file. Runtime autosave, simulation pause, and runtime developer mode changes last only until the process exits.

Observation records exist only to support server moderation. Each retained IP address and random client-device identifier has its own last-seen timestamp. Signals expire automatically after 90 days by default. Automatic `ban player` expansion uses only signals seen within the last 30 days; use `--include-stale` only after reviewing the displayed ages. Configure these periods with JVM properties `-Dstarchem.observations.retentionDays=N` and `-Dstarchem.observations.banMaxAgeDays=N`, or environment variables `STARCHEM_OBSERVATION_RETENTION_DAYS` and `STARCHEM_OBSERVATION_BAN_MAX_AGE_DAYS`. Use `observations delete <player>` or `observations clear confirm` to remove retained data.

A player ban records the player identity and, when available, also records that connection's numeric IP address and client device ID. IP bans accept exact IPv4 or IPv6 addresses and CIDR ranges. A game server cannot obtain a remote computer's Ethernet or Wi-Fi MAC address across the internet because routers do not forward MAC addresses. The `mac` spelling is therefore an explicit alias for StarChem's locally persisted random client device ID, not a hardware MAC address. Client device IDs can be reset or spoofed, IP addresses can change or be hidden by VPNs, and an IP ban may affect multiple players behind the same shared address; use identity, IP/CIDR, and device bans together when stronger enforcement is needed.

Kicks and bans retain the player's session, ships, bases, research, and systems. They prevent JOIN, password reclaim, and RESUME until removed or expired instead of using the normal disconnected-session expiry path. `prune-systems run confirm` is intentionally separate and creates a verified backup before deleting abandoned dynamic systems.

The `say`, `tell`, `notice`, and scheduled-shutdown commands send notices to connected clients. A temporary `disconnect` keeps the player's resumable session and assets; it is not a ban or permanent kick.

Runtime developer mode can be enabled only from the trusted local server console. It is independent from immutable startup configuration and resets after restart. Disabling it revokes all remote developer grants and free-build permissions, restores normal AI flags and the startup timer setting, and informs affected clients. Remote developer authorization and free-build are separate controls. Destructive developer operations require explicit confirmation and create a verified backup where recovery risk is meaningful. Resource grants, research changes, production changes, spawns, repairs, relocations, and faction operations run on the authoritative server tick and force client resynchronization.

Run `java -jar StarChem.jar --help` to view all supported startup options. Unknown options and missing option values are rejected instead of being silently ignored.

## Version

Run the following command from the extracted release folder to print the application version and build commit:

```text
java -jar StarChem.jar --version
```

Clients and servers should use the same StarChem release version.

## Multiplayer networking

StarChem multiplayer uses framed TCP connections. A dedicated server listens on the selected game port, and clients connect to that server with **JOIN**. Internet-hosted games must allow inbound TCP traffic on the selected port; StarChem no longer uses UDP for multiplayer.

Remote servers use a pinned TLS certificate. If a server is intentionally moved or its TLS key is replaced, StarChem blocks login secrets and displays the old and new fingerprints. Verify the change with the server owner before choosing **TRUST NEW CERTIFICATE**. Player password verifiers are derived with an intentionally expensive PBKDF2 step from the verified TLS fingerprint, player name, and a server-provided random account salt, so credentials captured from one server cannot be reused on another. Existing dedicated-server accounts created with the legacy verifier require one password re-entry; after successful authentication the server upgrades the account in place without replacing its player identity or assets. Dedicated-server operators should still back up the complete save directory, including the `*-tls.p12` identity file.

## In-game reference menus

Press `F1` during a game, or choose **CODEX** in the lobby, to open the searchable StarChem codex. It is generated from the currently loaded rule definitions and covers ships, stations, resources, research prerequisites and unlocks, manufacturing recipes, NPC factions, and controls. Filter by category or search names, IDs, stats, costs, descriptions, and unlock text. The codex is read-only and works during solo and joined games without changing game state.

Press `I` during a game to open the resource catalog. The catalog lists every loaded material and shows the loaded star-system templates, configured system roles, and resource-node types where the selected raw resource can naturally appear. Manufactured and salvage materials are identified separately because they are not placed in natural system belts.

Press `M` to open the galaxy map. Press the active menu key again or `Escape` to close a reference overlay.

A tactical minimap appears in the lower-right corner during normal play. It shows resources, wormholes, friendly ships and bases, enemy contacts, and the current camera view. Click anywhere inside its map area to pan the camera there. Contact, wormhole, and friendly-loss pings briefly highlight important locations; build and developer panels take input priority if they overlap it.

## Manufacturing economy

StarChem uses a JSON-driven intermediate manufacturing economy. Material display metadata, family, rarity, color, and raw/manufactured status are loaded from `config/materials.json`. Manufacturing recipes are loaded from the files listed under `files.craftables` in `config/starchem.json`.

The Manufacturing Plant organizes recipes into processed materials, chemicals, electronics, industrial assemblies, power and defense, weapons, and capital systems. Recipes may require completed research through their `requiresResearch` field. Starter Prospectors, Deployers, and the first Manufacturing Plant remain directly craftable from raw resources so a new game cannot deadlock before manufacturing is available.

Salvage can be recycled through reclamation recipes, while ships, stations, and later research consume progressively more manufactured components instead of enormous flat piles of raw ore and gas.

## Galaxy topology

`config/galaxy.json` controls the number of extra seeded shortcuts added on top of the permanent connected ring:

```json
{
  "topology": {
    "wanderingWormholePairs": 4
  }
}
```

Set `wanderingWormholePairs` to `0` to keep only the base topology. Accepted values are `0` through `32`. The same galaxy seed and setting produce the same additional links. The host reads this setting when a session is created, so changing it requires starting a new session. Multiplayer clients should use the same packaged configuration as the host.

## Development

Run from source with Gradle during development.

Local builds use an identifiable `-dev` application version. Release builds receive their semantic version and commit SHA from the release workflow.

### Remote developer access

Remote clients never receive developer authority solely because they launch with `--dev`.

Create separate protected token files containing the same strong random token on the host and client machines, then pass only the file paths:

```text
java -jar StarChem.jar --host 50000 --dev --dev-token-file /secure/host-dev-token
java -jar StarChem.jar --join HOST 50000 --dev --dev-token-file /secure/client-dev-token
```

Tokens must contain 16-128 letters, numbers, `.`, `_`, `~`, or `-`. Token files must be regular, non-symbolic-link files owned by the current user. On POSIX filesystems they must not grant group or other permissions. Broad Windows ACL access is also rejected. One trailing newline is accepted. The file is loaded only at startup, so token rotation requires restarting the process.

The legacy `--dev-token TOKEN` form remains available for migration but prints a warning because command-line secrets can be exposed through shell history, process listings, service definitions, crash reports, and diagnostics. Do not use the legacy form for normal deployment.

A graphical host's loopback client remains authorized automatically; dedicated servers require the token even for loopback clients.

A graphical host can also grant or revoke a connected client's requested developer access from the **Remote dev access** section of the in-game dev crafting panel. Revocation takes effect immediately on the client and server.

## License

StarChem is proprietary software. Copyright © 2026 tndmadman. All rights
reserved.

The source code is visible for inspection only. Public repository access does
not grant permission to copy, compile, modify, redistribute, publish, sell,
reuse, or incorporate StarChem code, rules data, assets, or other protected
material into another project.

Official unmodified compiled releases may be run only under the limited
personal, non-commercial permission stated in [`LICENSE`](LICENSE). StarChem is
not open source. Outside implementation contributions are not currently
accepted; see [`CONTRIBUTING.md`](CONTRIBUTING.md). Third-party notice policy is
documented in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

## Release

1. Update `RELEASE_NOTES.md` so its first line is exactly `# StarChem v<version>`.
2. Create and push that immutable semantic-version tag, for example `v1.1.0-alpha`.
3. The release workflow rebuilds the JAR twice and requires byte-identical output, runs the complete verification suite, creates the release ZIP twice and requires byte-identical output, verifies its SHA-256 checksum, smoke-tests the extracted Linux client and dedicated server, and validates both Windows launchers.
4. Only after every validation job passes does the tag-triggered publish job attach the ZIP and `.sha256` file to the GitHub Release.

The workflow never creates, moves, or force-updates a release tag. Pull requests that modify release-critical files run the same build and package validation without publishing anything.
