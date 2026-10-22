# World lifecycle, celestial bridge, and procedural scheduler (unreleased)

This note covers the stabilization changes proposed after the published v1.8.2 baseline.
It does not describe previously published behavior or alter protocol 17, rules 27 or save format 6.

## World/system ownership

A `World` owns its `GalaxyCoordinator`, which owns each live `WorldSystemState`.
Each system owns its `CelestialSystem`, persistent celestial bodies and resources.
Systems remain registered throughout movement between live solar systems. Disposal is
appropriate only when a system is deleted, its galaxy is replaced, or its world is
permanently shut down.

The four celestial registries (`CelestialGameplaySystem`,
`CelestialExtractionSystem`, `CelestialMoonInheritance` and
`CelestialResourceBridge`) expose deterministic unregister functions. WeakHashMap
does not make `CelestialSystem -> WorldSystemState -> CelestialSystem` weak:
the value strongly owns its own key. The resource-node maps likewise had a
`ResourceNode -> WorldSystemState -> resources -> ResourceNode` path. Teardown
removes the system and its resource-node reverse associations, extraction data,
world binding and moon reservations. Save restore and rebuild dispose the old
systems before registering replacements; removed systems are unregistered as
soon as deleted, without impacting other live systems.

`World.dispose()` clears remaining world-scoped caches, registries and audio
listener state after the UI/network lifecycle has stopped. `GameFrame` calls it
after network shutdown; dedicated startup calls it after persistence close on
shutdown. It is idempotent. Callers must not use a disposed World again.

## Resource bridge

`ResourceSpawner.update` continues to bridge persistent celestial resource nodes
before their orbit step. The source of truth remains `WorldSystemState.resources`.
The bridge scans the current tactical list once into a reusable, epoch-stamped
primitive int membership set and then visits persistent nodes once. It never
retains a long-lived object-to-ID map; the scratch set is rebuilt on every pass
so save restore, node insertion/removal and system switches cannot leave stale IDs.
Sealed fields retain inactive nodes with zero respawn timers. Released fields are
added once by ID and preserve the original authoritative ResourceNode objects.

Baseline: the previous containsId loop made up to N*M ID comparisons when N
released persistent resources were missing from an M-element live list. The new
ordinary-case pass performs O(M+N) membership work with capacity >= 2*(M+N),
without allocating per-ID wrapper objects. This is an analytical complexity
comparison, NOT a measured wall-clock improvement. No benchmark time is asserted.

Regression: `CelestialResourceBridgeValidator` tests release, depletion and
refire, authoritative object reuse and idempotence; additional density scenarios
of 0/1/100/500/1000 ordinary resources are repeated 20 times with ID uniqueness
checks and a mix of active and depleted nodes.

## Procedural galaxies

The four-system/frame round-robin budget and per-system elapsed-time accumulation
remain unchanged. Before this change, the authoritative scheduler passed through
`GalaxyCoordinator.mapSnapshot` each tick; that built a full UI-oriented snapshot
including resource, ship and wormhole counts across the whole galaxy. The scheduler
also allocated a temporary full-system ID list and live set each update.

The coordinator now provides a cached immutable, ordered system-ID roster that is
invalidated on galaxy rebuild/restore, system creation and deletion. The scheduler
recomputes its filtered inactive roster only when that list's identity or the
active system changes, and prunes timing state after a membership change.
The existing snapshot-based overload remains for callers/tests; the authoritative
simulation hot path uses the roster directly. Unknown dt, true long-session memory
profiles and large-galaxy throughput still require instrumented integration runs.

## Validation commands

```bash
gradle clean classes check jar --no-daemon
gradle validateWorldLifecycle validateGalaxyInactiveSchedulerRoster --no-daemon
java -Djava.awt.headless=true -cp 'build/classes/java/main:build/resources/main' com.tndmadman.rts.CelestialResourceBridgeValidator
bash validation/run-release-regressions.sh 'build/classes/java/main:build/resources/main'
bash validation/run-v180-rc-acceptance.sh 'build/classes/java/main:build/resources/main'
```

The existing PR render-performance workflow performs three paired baseline/head
runs over 33 scene/zoom keys, enforcing the unchanged 20% limit. Full Swing,
fog/HUD, VFX/audio, real LAN/WAN multiplayer and long-duration soak acceptance
must be performed on suitable machines before release. Never infer success from
compilation or the headless benchmark alone.
