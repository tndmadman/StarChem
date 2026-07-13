# Getting Started

This page covers the packaged **StarChem v1.1.0-alpha** release. Players do not need Gradle, source files, or a local compile step.

## Requirements

- Java 17 or newer.
- A complete extracted StarChem release ZIP.
- Windows or Linux for the supplied launch scripts.
- Matching release and configuration files for multiplayer.

## Install Java

Confirm Java is available:

```text
java -version
```

The reported major version must be 17 or newer. If the command is missing or reports an older version, install a current Java runtime before starting StarChem.

## Install the game

1. Download `StarChem-v1.1.0-alpha.zip` from the GitHub Release.
2. Extract the entire ZIP into its own folder.
3. Keep `StarChem.jar`, the `config/` directory, launch scripts, README, license, and notices together.
4. Do not run the JAR from inside the ZIP archive.

The configuration directory is required at runtime. Moving only `StarChem.jar` elsewhere can cause startup or compatibility failures.

## Launch on Windows

Double-click:

```text
run-starchem.bat
```

The graphical lobby opens and offers Solo, Host, and Join modes.

## Launch the Linux client

Open a terminal in the extracted folder and run:

```text
chmod +x run-starchem.sh
./run-starchem.sh
```

The `chmod` command is only needed when executable permission was lost during download or extraction.

## Direct Java launch

Open the lobby:

```text
java -jar StarChem.jar
```

Start directly in a solo game:

```text
java -jar StarChem.jar --solo --name Commander
```

Print the packaged release identity:

```text
java -jar StarChem.jar --version
```

Print every supported startup option:

```text
java -jar StarChem.jar --help
```

Unknown options and missing option values are rejected rather than silently ignored.

## Lobby modes

### Solo

Runs the simulation locally without a network connection. Use this mode to learn controls, economy, research, combat, and galaxy navigation.

### Host

Starts an authoritative server and a local client in the same application. Other players connect to the host machine’s TCP address and port.

### Join

Connects to an existing graphical host or dedicated server. The client must match the server’s protocol, application version, build commit, rules version, and release-critical configuration fingerprint.

## First-session checklist

1. Start **Solo** with the default system and one galaxy copy.
2. Select the starting Prospector.
3. Right-click a nearby resource node to begin automatic harvesting.
4. Learn camera movement with WASD or arrow keys and mouse-wheel zoom.
5. Open the resource catalog with `I`.
6. Open the galaxy map with `M`.
7. Accumulate Iron and Copper for another Prospector or a Deployer.
8. Follow [[First Game Progression]] to establish manufacturing, research, and ship production.

## Version and configuration safety

For multiplayer, every participant should use the same untouched release folder. StarChem rejects mismatched builds and release-critical configuration before creating player state. Copying configuration files from another branch or release can prevent a connection even when the displayed version appears similar.

## Alpha expectations

v1.1.0-alpha is playable but unfinished. Save compatibility, tuning, recipes, hull statistics, networking behavior, and configuration formats may change in later releases. Report reproducible issues with the exact version output, operating system, launch command, and relevant server/client logs.