# Dedicated-server moderation

StarChem dedicated-server moderation is controlled from the authoritative server console.

## Recommended enforcement

Use a player ban together with the captured IP and client-device identifier when stronger enforcement is needed:

```text
ban player P2 permanent Repeated abuse
bans all
```

A player ban automatically records the player's active numeric IP address and StarChem client-device ID when available. Operators may also manage those signals directly:

```text
ban ip 203.0.113.42 7d Repeated account creation
ban ip 2001:db8:1234::/48 permanent Blocked network
ban device <device-id> permanent Repeated evasion
unban <entry-id-or-target>
```

`ban mac` is an alias for `ban device`; it does **not** represent a hardware Ethernet or Wi-Fi MAC address. Hardware MAC addresses do not traverse internet routers. The StarChem device identifier is a locally persisted random value and remains a best-effort signal that can be reset or spoofed. IP addresses may change, be shared through NAT, or be hidden by a VPN.

Kicks and bans retain the affected player's session, ships, bases, research, and systems. They block JOIN, password reclaim, and RESUME until removed or expired.

## Admission and recovery

```text
whitelist add-connected
whitelist on
kick P2 30m Cooldown
kicks
unkick P2
activity last 50
```

Whitelist, kick, and ban state is stored beside the server save in `<save-name>-moderation.json`.
