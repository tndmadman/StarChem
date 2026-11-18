# StarChem early-game economic progression audit

Baseline: `main` at `0c16204cf835bacb25449ee58af016b2e786f3a5`.
Implementation base: PR #453 feature head `035a8e8eec20bafa453eec012f833e09a92d8ee0`.
Working integration branch: `integration/four-branch-20261105` (draft PR #457).

## Root cause and repairs

Previously the Lab needed Radiation Shielding and Research Matrix. Radiation Shielding was authorized only by `defensive_engineering`, which required a functioning Lab. Research Matrix also depended on the shielding recipe. The new chain makes ordinary shielding available at the Manufacturing Plant without this late research grant. `defensive_engineering` still unlocks advanced Shield Generators.

Early electronics had further hard blockers: Gold Contact Mesh, Argon and Rare Earths appeared upstream of the first Lab and Hauler even though these resources were not in the starter Sol Standard resource belts. The starter routes for Printed Circuit Boards, Sensor Arrays, Research Matrices, Capacitor Banks, Navigation Computers, Point Defense assembly and shielding now use ordinary Sol Standard raw resources. Other high-tier recipes retain specialized material needs. Existing material and recipe IDs are unchanged.

This is a legitimate manufacturing path. No free-build, completed-tech injection, default player resource gifts, or production authorization bypass were introduced. Fresh solo, Guided Start, and new multiplayer commanders now receive the configured starter fleet (15 Prospectors by default) and one owned T1 Radar Picket; no free resources or research are granted.

## Order of operations

1. Begin with an Outpost, **15 Prospectors by default** and one T1 Radar Picket. The Solo/Guided lobby permits 1–50 starting Prospectors; new dedicated servers use `--starting-miners N`. Saved worlds retain their policy even if the CLI changes after restart.
2. Select Prospectors as a group, mine Iron and Copper and **unload at least 5 Iron into the Outpost** to complete the first Guided objective; no second Prospector purchase is required.
3. Build a Deployer, load and place Manufacturing (320 Iron, 180 Copper, 220 Silicates, 100 Ice, 60 Aluminum, 50 Nickel).
4. Craft Steel Plate, Nickel Steel, Structural Frames, Copper Wiring, Printed Circuit Boards, Regulators, Shielding, Research Matrices and Water Coolant.
5. Build another Deployer, then load and place the Lab (12 Structural Frames, 4 Research Matrices, 6 Regulators, 10 Water Coolant, 6 Shielding).
6. Harvest starter Sol gas clouds with a Prospector, manufacture Fuel from Hydrogen, Helium and Methane, and deliver it to the Lab.
7. Research Advanced Industry (35 seconds; grants the Hauler **blueprint**, not the complete manufacturing chain).
8. Research Logistics Engineering (44 seconds; grants Cargo Pods), then Autonomous Logistics (55 seconds; grants Logistics Control Modules).
9. Build another Deployer, manufacture the Shipyard package, and place the Shipyard.
10. Produce Hauler components and build the Hauler at the Shipyard. Continue with unrestricted exploration or combat if desired.

## Starting resources, batches, and time budget

The following values come from the revised catalogs, cumulative from an **empty station inventory**. They include recipe output rounding and carry over unused components between stages. These are engineering cost targets, **not observed playtime**. Mining, travel, unloading, delivery, queue delays, additional fuel burn while waiting, and combat risks are not included. Actual timing requires graphical playtesting.

| Milestone | Raw resources consumed cumulatively | Manufacturing batches / minimum processing |
| --- | --- | --- |
| First Manufacturing Plant | Iron 540; Copper 300; Silicates 320; Ice 140; Aluminum 100; Nickel 50 | 0 / 0 s |
| First Research Lab package | Iron 868; Copper 460; Silicates 492; Ice 210; Aluminum 156; Nickel 80; Carbon 32; Nitrogen 5 | 17 / 172 s |
| First Advanced Industry research | Lab totals plus Copper 120; Silicates 100; Aluminum 70; Nickel 90; Carbon 50; Hydrogen 30; Helium 10; Methane 12 | 18 / 184 s |
| First Hauler and all three research topics | Iron 1,202; Copper 886; Silicates 759; Ice 330; Aluminum 314; Nickel 205; Carbon 126; Nitrogen 5; Hydrogen 90; Helium 30; Methane 54 **before** Lab fuel burn | 46 / 455 s before operational Fuel reserve |

The first Lab bill uses **2 Research Matrix, 2 Regulator, 2 Shielding, 2 Structural Frame, 2 Nickel Steel, 2 Steel Plate, 2 Printed Circuit Board and 2 Copper Wiring batches**, plus 1 Water Coolant batch (17 total).

The 3 relevant research topics take 134 seconds total and the Lab consumes Fuel at 0.25/s when working, or at least **33.5 additional Fuel** if continuously supplied. Together with research costs and Fuel Cell Stack manufacturing, that makes at least **4 Fuel recipe batches** (not just 3). The conservative cumulative gas budget is therefore Hydrogen 120, Helium 40, Methane 66, and the minimum crafting time becomes 467 seconds. More Fuel may be needed if queues idle or other research is performed.

The starter fleet is granted once for a new commander, so the former second-Prospector construction cost and time are no longer mandatory. Construction time for the three Deployers, Manufacturing, Lab, Shipyard and Hauler must be recalculated and measured in an authoritative full playthrough. Research still adds 134 seconds. These figures are engineering estimates, not a first-playthrough benchmark.

## Validation boundaries

- `StarterProgressionValidator`: deterministic material / station / ship / research reachability with effective research-topic grants and cross-system Lab loop regression.
- `StarterEconomyBudgetValidator`: catalog-derived cumulative recipes, batch counts, available raw resources, and operational Lab Fuel requirements.
- Tutorial validators: economic-first objective ordering, skips, solo-only activation, and player-side world-state checking.
- Scenario validation: Guided Start's authored starting state grants no **extra** assets; the new-world starter policy grants the Outpost, 1–50 Prospectors (default 15), and one Radar Picket I. A first objective observes at least 5 delivered/stored Iron in an owned station, rather than an instantly completed second-miner count. Historical server archives without policy metadata restore their former one-miner/zero-radar starter policy.
- These are *static* or simulated-authoritative-state validators, **not proof of an actual full mining-to-Hauler live simulation**. A bounded command-driven world progression test and fresh-profile hands-on walkthrough are still required to prove pacing and interactions.

## Compatibility and risk

Changes preserve recipe/material IDs, existing manufacturing job authorization, the feature-branch lifecycle changes, protocol 17 and save format 6. Balance changes are to shared recipes; they also affect normal worlds and multiplayer. The Guided Start scenario itself is explicitly separate from Solo / Custom and disables hostile NPC factions without enabling developer cheats. Verify existing saved jobs, recipes and resource fingerprint policies before release.

Render Performance on the pre-existing feature head of PR #453 had **32 of 33 comparisons pass**, with `background-default` at zoom 0.45 exceeding the 20% gate (13.622 ms baseline versus 24.814 ms candidate median p95, +82.2%). Treat independently from onboarding; rerun paired benchmarks on the final SHA.

## Manual acceptance remaining

Follow only in-game instructions with a fresh profile: select a Prospector group, mine and unload Iron; place first Manufacturing; craft the Lab components; deliver Fuel; complete first research; unlock logistics; build a Shipyard and Hauler. Repeat in standard Solo, NPC-disabled play, a small display, after tutorial pause/restart, on an existing save, and in multiplayer while checking fog and authority. Record observed time, clicks, and stalled steps. No claim of a complete graphical walkthrough is made by catalog-only validators.
