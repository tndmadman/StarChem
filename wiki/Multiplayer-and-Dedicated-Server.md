# Multiplayer and Dedicated Server

StarChem v1.1.0-alpha uses an authoritative host and one full-duplex framed TCP connection per client. It no longer uses UDP for multiplayer.

## Game modes

### Solo

```text
java -jar StarChem.jar --solo --name Commander
```

Runs the entire simulation locally. No port forwarding is required.

### Graphical host

```text
java -jar StarChem.jar --host 50000 --name Host
```

Starts an authoritative server plus a local loopback client. The host plays through the normal graphical interface.

### Join

```text
java -jar StarChem.jar --join HOST 50000 --name PlayerName
```

Replace `HOST` with the server’s LAN address, public address, or resolvable hostname.

### Dedicated Linux server

From the extracted release folder:

```text
chmod +x run-starchem-server.sh
./run-starchem-server.sh
```

Defaults:

- TCP port: `50000`
- Server name: `StarChem-Server`
- Java executable: `java`

Override launcher defaults with environment variables:

```text
STARCHEM_PORT=50100 STARCHEM_SERVER_NAME="Public Server" ./run-starchem-server.sh --galaxy-copies 2
```

Use a specific Java executable:

```text
JAVA_BIN=/opt/jdk-21/bin/java ./run-starchem-server.sh
```

Equivalent direct command:

```text
java -Djava.awt.headless=true -jar StarChem.jar --server 50000 --name StarChem-Server
```

## Startup options

| Option | Meaning |
|---|---|
| `--solo` | Start a local solo session |
| `--host PORT` | Host with a local graphical client |
| `--join HOST PORT` | Connect to a host |
| `--server [PORT]` | Run a headless dedicated server; default port 50000 |
| `--name NAME` | Set player or server name |
| `--system SYSTEM_ID` | Select initial system template |
| `--galaxy-copies 1|2` | Create one or two permanent copies of each static system template |
| `--dev` | Enable local developer mode request |
| `--dev-token TOKEN` | Supply remote developer authorization token |
| `--enable-timers` | Keep production timers enabled in developer mode |
| `--disable-timers` | Disable production timers in developer mode |
| `--version`, `-V` | Print version and build identity |
| `--help`, `-h` | Print command help |

## Port forwarding

Internet hosts behind a router usually need to forward the chosen **TCP** port to the server computer.

1. Give the server computer a stable LAN address or DHCP reservation.
2. Forward external TCP port 50000—or your selected port—to the same internal TCP port.
3. Allow inbound TCP traffic through the host operating system firewall.
4. Give remote players the public address and port.

Do not create a UDP-only forwarding rule. Normal joining clients behind NAT do not require port forwarding.

## Server lifecycle

The headless server:

- Runs a fixed 60 Hz simulation clock.
- Allows at most five catch-up ticks before resetting its schedule.
- Prints a readiness line and initial status after startup.
- Prints another status line every 60 seconds.
- Cleans up network transport on `Ctrl+C` or `SIGTERM`.

Stop a foreground server with:

```text
Ctrl+C
```

For service managers, send normal `SIGTERM` and allow the Java process to exit cleanly.

## Compatibility checks

Before player or world state is created, client and server compare:

- Protocol version.
- Application version.
- Build commit.
- Rules version.
- SHA-256 fingerprint of release-critical configuration files.

A mismatch is rejected. All participants should use the same extracted v1.1.0-alpha package. v0.1.5 clients are incompatible.

## Player names and sessions

Duplicate active player names are rejected. Choose a unique name per server.

The server separates temporary TCP connections from persistent player sessions. During the configured reconnect grace period, it retains:

- Player identity and ownership.
- Ships and stations.
- Inventory.
- Research.
- Production queues.
- Home system.
- Current system view.

A reconnecting client presents its stored resume token. Accepted tokens rotate after recovery, and an obsolete socket cannot reclaim an already active replacement session.

## Remote system viewing

The galaxy map can request authoritative views of non-adjacent systems. View requests carry increasing revision numbers. The client rejects stale responses, preventing a delayed old system view from replacing the newest selection.

Approved remote views include complete visible ships and stations. Normal updates continue after the switch settles.

## Capacity and slow clients

The server accepts up to 128 simultaneous TCP connections. Each client has bounded inbound and outbound queues. Snapshot classes use replaceable slots so obsolete snapshots can be coalesced, while ordered control messages remain ordered.

A client that cannot drain required control traffic is disconnected rather than blocking the authoritative simulation or consuming unbounded memory.

## Security limitations

TCP provides reliable ordered transport, but v1.1.0-alpha does not add encryption or cryptographic server identity. Traffic is not confidential, and a client does not receive TLS-style proof that it reached the intended server. Use trusted networks or external protected transport where confidentiality is required.

## Troubleshooting connections

### Connection refused

- Server is not running or not ready.
- Wrong host or port.
- Firewall blocks the TCP port.
- Router forwarding points to the wrong LAN address.

### Compatibility rejection

- Client and server are from different releases or builds.
- A release-critical configuration file was edited.
- One participant is running files from `main` rather than the release ZIP.

### Duplicate-name message

Another active session already uses the name. Choose a different name. If a prior session disconnected unexpectedly, allow the server’s recovery handling to settle before trying again.

### Dedicated server exits immediately

Run the direct Java command in a terminal and inspect the printed error. Common causes are Java older than 17, missing `config/`, an occupied port, invalid startup options, or malformed configuration.