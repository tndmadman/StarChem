# Dedicated-server developer and recovery controls

Developer mutations are accepted only from the trusted local dedicated-server console. Runtime developer mode can be enabled without restarting:

```text
dev mode on
dev status
dev mode off confirm
```

Runtime mode is process-local. Disabling it revokes remote developer grants and free-build permissions, restores normal AI flags, resets the AI difficulty preset, and restores the startup production-timer setting.

## Help and role management

The console exposes nested developer help instead of requiring operators to know hidden subcommands:

```text
help dev
help dev role
help dev access
help dev freebuild
help dev mode

dev help
dev help role
```

The preferred interface assigns one explicit effective role:

```text
dev role list
dev role list connected
dev role list granted
dev role show P2

dev role set P2 developer
dev role set P2 developer-freebuild
dev role set P2 none
```

Role meanings:

- `none` revokes developer access and free-build.
- `developer` grants developer access without free-build.
- `developer-freebuild` grants both permissions.

Role listing includes retained/offline identities, not only connected peers. A retained player can be changed or revoked while offline, and the selected state is applied if that identity reconnects during the same server process.

## Access and free-build compatibility commands

The older access and free-build commands remain supported:

```text
dev access list
dev access requests
dev access grant P2
dev access revoke P2
dev access revoke-all

dev freebuild status P2
dev freebuild P2 on
dev freebuild P2 off
```

`dev access grant` changes developer access without automatically enabling free-build. `dev access revoke` clears both access and free-build and can target a retained/offline identity. Prefer `dev role set` when assigning the complete intended role.

Remote developer packets are accepted only while runtime developer mode is enabled and the player identity has an active developer grant.

## Resources and research

```text
dev resource list
dev resource inspect P2:B1
dev resource add P2 P2:B1 FUEL 500
dev resource set P2 P2:B1 COPPER 2000
dev resource fill P2 all-bases 1000
dev resource clear P2 P2:B1 confirm

research status P2
research topic advanced_industry
dev research grant P2 combat_doctrine with-prerequisites
dev research complete-queued P2 battlefleet_engineering
dev research revoke P2 advanced_industry cascade
dev research reset P2 confirm
```

Research grants remove duplicate queued jobs and refund reserved research resources. Revocation refuses to break completed dependency chains unless `cascade` is supplied.

## AI and factions

```text
dev ai freeze-players on
dev ai freeze-npc-combat on
dev ai attacks off
dev ai economy off
dev ai preset list
dev ai preset aggressive
dev ai snapshot
dev ai reload

dev faction list
dev faction NPC_CORSAIRS status
dev faction NPC_CORSAIRS spawn
dev faction NPC_CORSAIRS give-resources all 1000
dev faction NPC_CORSAIRS force raid
dev faction NPC_CORSAIRS reset confirm
```

## Production, assets, and recovery

```text
dev production fund P2:B1 P4
dev production finish P2:B1 P4
dev production cancel P2:B1 P5
dev production move P2:B1 P5 2
dev production clear P2:B1 confirm

dev asset heal P2:7
dev asset move P2:7 sol_standard
dev asset destroy P2:7 confirm
dev player heal-all P2
dev player relocate P2 sol_standard
dev player respawn P2
dev spawn ship P2 frigate 3 sol_standard
dev spawn base P2 shipyard sol_standard 1500 900
```

Destructive operations require explicit confirmation and create a verified backup when recovery risk is meaningful. All mutations execute on the authoritative server tick and force client state correction.

## Diagnostics and audit

```text
tell P2 Server message
notice system sol_standard Maintenance soon
threads
memory
gc-status
dump player P2
dump system sol_standard
activity export operator-audit
observations P2
```

The bounded operator journal persists in `<save-name>-activity.log`. Last-seen player IP and StarChem client-device signals persist in `<save-name>-observations.json` for moderation use. These files contain sensitive operational data and should be protected with the same care as server saves.

`ServerDevCommandValidator` exercises nested developer help, explicit role transitions, compatibility grant/revoke commands, free-build separation, resources, production, research, spawning, healing, notices, resynchronization, and runtime-mode shutdown against a real connected TCP client.
