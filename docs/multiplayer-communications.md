# Multiplayer chat and tactical pings

StarChem multiplayer communication is server-relayed and server-authoritative. Clients can request a chat message or tactical ping, but they do not supply the trusted sender identity shown to other players. The server resolves every sender from the authenticated TCP connection before applying channel membership, rate limits, visibility rules, and recipients.

## Chat

Press **Enter** to open chat. The binding is available as **Interface → Chat** in the normal controls settings and can be rebound. While the real Swing text field owns keyboard focus, gameplay hotkeys do not receive typed characters. Enter sends, Escape closes chat, Tab changes channels, and Page Up/Page Down or the mouse wheel scrolls bounded history.

Channels:

- **Global** — all currently connected authenticated players, when Global chat is enabled by server policy.
- **System** — connected players whose current server-approved view is the same system as the sender. Merely owning an asset in another system does not subscribe a player to that system's chat.
- **Team** — connected players currently considered allied by the authoritative diplomacy system, including fixed teams and live alliances.
- **Direct** — one exact connected player ID. Direct-recipient failure is intentionally generic and does not distinguish an unknown retained identity from a disconnected player.

Useful local commands:

- `/global`, `/system`, `/team` — select a channel.
- `/direct P2 [message]`, `/dm P2 [message]`, `/w P2 [message]` — select or send to an exact player ID.
- `/block P2` — locally suppress that player's chat and tactical pings.
- `/unblock P2` — remove the local block.
- `/blocks` — show locally blocked player IDs.
- `/help` — show the compact communication help line.

Local blocks are display-only. They do not change authoritative player identity, diplomacy, admission, kick/ban state, or what other clients receive.

Ordinary chat is intentionally not written into the world save and is not replayed after reconnect. Client scrollback is bounded globally and per channel.

## Tactical pings

Five ping types are available:

1. Attention / rally
2. Enemy / threat
3. Defend
4. Resource
5. Move here

Keyboard placement uses **Ctrl+Alt+1** through **Ctrl+Alt+5**. This chord intentionally does not conflict with the existing Alt+number control-group bindings. **Alt+middle-click** places a quick Attention/Rally ping.

On the tactical view, a world ping uses the cursor's world location. On the galaxy map, the keyboard shortcut targets the known system under the cursor. Server-approved pings are rendered in the world and minimap when relevant; galaxy-system pings are rendered over their galaxy node while the map is open.

World pings require finite in-bounds coordinates in the sender's current server-approved system and must be inside the sender's current authoritative sensor visibility. Allied recipients receive a world ping only when they are viewing the same system and can currently see that location. Galaxy-system pings require the target to exist in the sender's projected known galaxy, and allied recipients receive them only if that system is also present in their projected known galaxy.

Pings expire after a short lifetime, repeated nearby pings are coalesced, and active pings are capped per sender and per client.

## Server policy

Multiplayer communication policy lives beside the server save as:

`<save-name>-comms.properties`

The server seeds the file with secure bounded defaults the first time multiplayer communication is used and reloads changes while running. Operators may also create the file before players use chat.

Supported properties:

```properties
enabled=true
global=true
system=true
team=true
direct=true
pings=true
mutedPlayers=
```

`enabled=false` disables player chat and tactical pings globally. Individual channel flags disable only that channel. `pings=false` disables tactical pings. `mutedPlayers` is a comma- or whitespace-separated list of exact retained player IDs such as `P2,P7`; muted players can remain connected and play normally but cannot send player chat or tactical pings.

This policy is intentionally separate from kick/ban moderation. A communications mute must never accidentally become an admission ban or alter retained player identity.

## Safety and limits

- Untrusted chat uses Unicode NFKC normalization, whitespace collapsing, control/format/bidi removal, and a fixed code-point limit.
- Player-controlled text is Base64-framed inside the existing strict UTF-8 TCP frame and is never used as a trusted sender field.
- Chat and ping packets have dedicated pre-split size limits before allocation-heavy parsing.
- The existing fair bounded inbound scheduler still limits all client traffic; communications also use independent server-wide, per-sender, per-channel, direct-recipient-pair, and ping token buckets.
- Outbound chat and pings remain ordered and non-coalescing at the TCP control layer. Semantic ping coalescing happens before send.
- Slow-client backpressure and transport queue limits remain unchanged.
- Normal chat history is not persisted or replayed.
