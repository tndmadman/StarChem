# StarChem — branch audit (stage 1)

Date: 2026-11-17. Repository: `tndmadman/StarChem`.

**Disposition: NOT COMPLETE — semantic audit and code integration remain blocked.** This file contains a complete remote-ref/SHA snapshot but only limited verified ancestry and five divergent comparisons. It MUST NOT be treated as proof that all development work is integrated. A head that is not reviewed is listed as **BLOCKED / NOT REVIEWED**, not as missing, equivalent, superseded, or obsolete.

## Repository checkpoints

- `main`: `0c16204cf835bacb25449ee58af016b2e786f3a5` (unchanged).
- Previous integration: `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f`; previously verified 171 ahead of main, zero behind, 147 files changed.
- New candidate: `integration/starchem-all-ready-20261117`; initially at `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f`.
- Initial branch count before candidate creation: 295 refs including main. Current snapshot after creation: 296 refs including main.
- Known PR heads #453–#461: all 9 verified direct ancestors of the candidate by GitHub compare (0 commits ahead of candidate).

## Disposition totals (stage 1 only)

| State | Count | Meaning |
|---|---:|---|
| BASELINE | 1 | main |
| INTEGRATED (ancestry verified) | 10 | 9 previous PR heads plus new candidate |
| BLOCKED — complete review pending | 285 | No sound code-equivalence or missing-work decision yet |
| EQUIVALENT verified | 0 | Not assessed |
| SUPERSEDED verified | 0 | Not assessed |
| MISSING verified | 0 | Not assessed |

**The zero counts are not findings of absence. They mean that classification work has not yet been completed.**

## Follow-up structural inspection — 2026-11-17

The earlier snapshot remains a historical checkpoint, **not** a final branch audit.
The five priority refs below were compared against integration candidate
cc26b3a0f4db26837873a6ffb2fc2d1b04fe1373 using GitHub commit
comparisons and recursive Git tree blob-SHA comparisons.

| Historical ref | Head SHA | Matching file blobs | Paths with differing blobs | Historical-only paths |
|---|---|---:|---:|---:|
| agent/fix-corsair-remote-view | 0d7450e7d21c55e6436f687c68073d78a6f50553 | 108 | 221 | 0 |
| automation/ai-lifecycle-fix | c0ff8e18a04cb8fad37e34ec6b112606264ace5c | 97 | 228 | 3 |
| graphics-388-foundation-overhaul | dac9801764ff47e1c92ae2479149a64f83d1b8ac | 489 | 195 | 0 |
| feature/centralized-manufacturing-ui | 8b1528a8b301b94cd764c7997234039b2f005ccc | 428 | 191 | 0 |
| feature/issue-384-scenario-framework | 9a5165c3dcfd55c7c58ca71c082b97ea5fbc6db7 | 495 | 178 | 0 |

These counts compare complete file trees at each historical head. A file
with different contents is **not** proof of missing features. The absence
of historical-only paths does not prove behavioral equivalence.

Specific source findings:

- ManufacturingPolicyPanel.java in the historical manufacturing UI branch
  has exactly the same blob as the candidate:
  8214712ee1e71c72ba81c99d74a34218aa8705a2.
  The candidate ManufacturingOverlay.java uses additional
  ProductionCatalogRules, ResearchPolicy, and ProductionRequirementGuide
  paths. Full behavior comparison remains necessary.
- NpcFactionScopedRuntimeReset.java in the AI lifecycle branch has exactly
  the same blob as the candidate:
  e7799630f110bb20d011f9a6b648593fc62d76e8.
  NpcSystem.java differs, including later GalaxyEventDirector.ownsUnit
  checks and WeaponRules.armed(unit) calls. The three historical-only
  paths are one-off lifecycle-automation workflows and
  tools/ai-lifecycle-fix.patch; do not import without reviewing purpose.
- The candidate ScenarioDirector.java retains all substantive historical
  lines in the inspected direct content comparison, with an additional
  OWN_STORED_MATERIAL objective case. ScenarioFrameworkValidator.java
  contains more cases, including Guided Start ore validation.
- The candidate RenderPerformanceValidator.java contains additional pixel
  tests. The newer StationRenderer.java uses StationSpriteCache and
  far-LOD behavior, so the historical renderer is not safe to copy over it.
- The Corsair branch matches the candidate on
  NpcFactionScopedRuntimeReset.java but diverges in other NPC/AI sources.

**All five remain BLOCKED for complete semantic comparison.** No missing
feature was inferred from divergence. No historical branch was merged.

A reproducible, read-only structural audit script now exists at
scripts/audit-branches.py on the consolidation branch. From a complete
Git checkout with Python 3, use:

~~~bash
python3 scripts/audit-branches.py
~~~

It fetches refs, records exact SHAs, ahead/behind counts, merge bases,
patch-unique nonmerge commits, and changed paths, then emits JSON and CSV
under build/branch-audit/. It classifies verified ancestry and exact tree
identity only; divergent branches stay BLOCKED for semantic review.
The script passed Python syntax checking and a synthetic three-branch
Git smoke test. **It was NOT run on the full StarChem repository**, since
this session has no runnable checkout.

## Remote branch ref snapshot

The table lists all branch references and their observed full SHA. Verified ancestry applies only to the nine PR heads; other branches remain blocked for investigation. Full main-relative ahead/behind counts and patch-equivalence checks have NOT been performed for the unreviewed branches.

| Branch | Full head SHA | State / evidence |
|---|---|---|
| `agent/add-in-game-codex` | `bbce7f6d79ee5f441e7df6dcaff4acc6875336cb` | BLOCKED — not reviewed |
| `agent/add-proprietary-license` | `d9225beb0f3bef41521e85fee6bf5d3dfe038044` | BLOCKED — not reviewed |
| `agent/add-tactical-minimap` | `c904a4df197e273d2b52020c8c3c9a6921cd8d04` | BLOCKED — not reviewed |
| `agent/authorize-remote-dev-access` | `6dc1c5daee2e9b6a6838acdb098cc550daf41d8e` | BLOCKED — not reviewed |
| `agent/bound-activity-journal-load` | `69cf147dde4185f4f64ccc7f9568cdf64365a83b` | BLOCKED — not reviewed |
| `agent/bound-inbound-processing-195` | `b18343f3dcd30b103abbe9e9bb23feb5b9719ce7` | BLOCKED — not reviewed |
| `agent/bound-save-json-resource-usage` | `75d3fe434ca72372ab763d0d22aef5601fe6cd55` | BLOCKED — not reviewed |
| `agent/cache-leaderboard-aggregation` | `fe0cb87f7ec4bd088acc223bcc84bdf8c20ea51f` | BLOCKED — not reviewed |
| `agent/canonical-server-identity` | `5a5c12f8bc0ae28663dc352ba4df6adf69e266b7` | BLOCKED — not reviewed |
| `agent/centralize-dev-world-cleanup` | `690ec4cb58ec4ffca6f4a20235e6a30372f20fd7` | BLOCKED — not reviewed |
| `agent/clarify-login-account-flow` | `5b5221e7c2c775f2d66c28f040bb6b9969167459` | BLOCKED — not reviewed |
| `agent/compact-production-menu-previews` | `1b255e979e1685629d29e96ab0bd09782d6d05bb` | BLOCKED — not reviewed |
| `agent/coordinate-client-session-store` | `1118852b74fe4080a9102d365ceef23c276954bd` | BLOCKED — not reviewed |
| `agent/debug-local-host-connect` | `e7302b301e5bdbfbc0ddcace4b8d39e82541c3c8` | BLOCKED — not reviewed |
| `agent/finish-live-diplomacy` | `99b3dd4ff312bad7828aece352ad24e245bb339c` | BLOCKED — not reviewed |
| `agent/finish-npc-budget-frame` | `193640a3d665c2f8b176d931b0c8aced2850e65b` | BLOCKED — not reviewed |
| `agent/fix-activity-journal-clear` | `6f125637db77b2274f446f6bc58fad89b2f846c0` | BLOCKED — not reviewed |
| `agent/fix-activity-journal-rollover` | `0a0ee626c4068f1fbcf4a6688ed1162acc6c7683` | BLOCKED — not reviewed |
| `agent/fix-activity-journal-rollover-tests` | `3f582883c1051bbb4512470fa9ef840c11753827` | BLOCKED — not reviewed |
| `agent/fix-admission-recording` | `f5d638adaae398c6bba0ba1e7b1fa358f203d349` | BLOCKED — not reviewed |
| `agent/fix-auth-enumeration-220` | `97fbce13c7d4e4b2829f5de1314599022aa600f6` | BLOCKED — not reviewed |
| `agent/fix-auto-production-allocation` | `93acbcef9ae1f9fa49a0a1b740a82a4782eae117` | BLOCKED — not reviewed |
| `agent/fix-backup-filename-collisions` | `440fe944327341cd0c816af919c674f99590e3c8` | BLOCKED — not reviewed |
| `agent/fix-backup-prefix-isolation` | `a375b7476d374fb57611969a29ddf29fd10ed10a` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading` | `95ce6215c9818ef63c6ae7862924b3279fe233c3` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-actual` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-final` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-implementation` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-patch` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-stop` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-use-this` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-v2` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-v3` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-client-initial-sync-loading-working` | `6a1d5ea4ea0c340324f8439e242f1c0d1878b597` | BLOCKED — not reviewed |
| `agent/fix-companion-state-recovery` | `be4f9e00f7bf01022356665dc535345324d368c4` | BLOCKED — not reviewed |
| `agent/fix-corsair-remote-view` | `0d7450e7d21c55e6436f687c68073d78a6f50553` | BLOCKED — diverged +309/−1337 vs candidate |
| `agent/fix-dedicated-server-failure-exit` | `b17cb8dbbfe8cd00877c19e0cf4ed9a954ea137e` | BLOCKED — not reviewed |
| `agent/fix-dev-access-request-state` | `7ac63eea65dd47e7ca02060fc903f83bcda43195` | BLOCKED — not reviewed |
| `agent/fix-dev-session-state` | `60f701d7e9aed635540666811d90ba0ba4ae0580` | BLOCKED — not reviewed |
| `agent/fix-final-save-shutdown` | `b15fa58d704cc9d4c91a8cf67b5a84506044100e` | BLOCKED — not reviewed |
| `agent/fix-fow-persistence` | `55f69333b47b31658940493c2183eedee303fb62` | BLOCKED — not reviewed |
| `agent/fix-game-audio-events` | `43e686a51f79d004b26702e13d7ebd50be83ba9d` | BLOCKED — not reviewed |
| `agent/fix-graphical-host-identity` | `955597c7e154adcd173b76a5699ae49e16a8f272` | BLOCKED — not reviewed |
| `agent/fix-host-dev-tools` | `701beb35b25157014a083b1603ca483eade9351a` | BLOCKED — not reviewed |
| `agent/fix-host-ghost-mining` | `ddfa07b49769b395a994511397dc279e42042096` | BLOCKED — not reviewed |
| `agent/fix-immediate-system-wake` | `2543dcb22b0a0fb37a0f7e6805d66e897873fd18` | BLOCKED — not reviewed |
| `agent/fix-lobby-name-field` | `1eb4c2a9f397b9a2698ae838de7c0794acd7630e` | BLOCKED — not reviewed |
| `agent/fix-local-host-duplicate-home` | `dd007e1cfffe2ded8380e91a63068af5dcf25fe0` | BLOCKED — not reviewed |
| `agent/fix-macos-keychain-argv` | `9724c3477c984aeb1963eb9a30035300ec489aca` | BLOCKED — not reviewed |
| `agent/fix-moderation-entry-ids` | `bc0953cc6779b2a61766461808a43f2bf3d3722c` | BLOCKED — not reviewed |
| `agent/fix-multi-batch-recipe-funding` | `964b23f7685eb0e2535d719a3d2b480398fcdb1f` | BLOCKED — not reviewed |
| `agent/fix-narration-process-output` | `9abda58d9840105e12c040c1ef100f0a8caf4175` | BLOCKED — not reviewed |
| `agent/fix-notification-registry-lifecycle` | `aaaf1ff9545f2bf78fc38b9300f1fd7dc476bf0b` | BLOCKED — not reviewed |
| `agent/fix-npc-resource-budget` | `14fd8d9f92654e2e6c1ac34b70e7eb57dbc201ab` | BLOCKED — not reviewed |
| `agent/fix-npc-system-state-95` | `f930d597d78777c35c1c3a287b29112d381deb9b` | BLOCKED — not reviewed |
| `agent/fix-numeric-command-validation-194` | `c60c37a2e347e59c4fe7834f443d433aa7717819` | BLOCKED — not reviewed |
| `agent/fix-previous-token-proof-reconnect` | `c19966bcd6642b3b1a910af184d9d07b2a311b67` | BLOCKED — not reviewed |
| `agent/fix-production-recipe-selection` | `7ec1b0747bcdb564fb95d1d3d2158d9bf0a2674f` | BLOCKED — not reviewed |
| `agent/fix-research-lab-fuel-supply` | `feed2c224bc254b36105f1b07014cf00ae336e84` | BLOCKED — not reviewed |
| `agent/fix-research-progression-deadlocks` | `cf80375970b3caf02d5a78faae7721279dc45bd4` | BLOCKED — not reviewed |
| `agent/fix-salvager-idle-unload-range` | `0a687bbd2338ae9ac2b2a5c175b06db785a278d9` | BLOCKED — not reviewed |
| `agent/fix-save-auth-keys` | `073062f8efd610fcaee2b650899da4c8ac455747` | BLOCKED — not reviewed |
| `agent/fix-solo-wormhole-graph-97` | `5617e37dec6dccefbec971ddacf63b48dd38adb7` | BLOCKED — not reviewed |
| `agent/fix-viewed-system-resource-desync` | `87da5516ad7350232edc960d6c8f78beaaa142b5` | BLOCKED — not reviewed |
| `agent/fix-windows-default-storage` | `e60ec00f1fa020dcf466e5dba102ab60068358b3` | BLOCKED — not reviewed |
| `agent/fix-windows-observation-acl` | `d445848fef6ebc6680d673639c3bfe8cfb846907` | BLOCKED — not reviewed |
| `agent/fix-windows-private-file-acls` | `4a048e189962dfcefad1872cac7480d2e19ff95e` | BLOCKED — not reviewed |
| `agent/fix-wormhole-audio-scheduler` | `c026af1c1cec4d4ade2dc1258a57f58a43743906` | BLOCKED — not reviewed |
| `agent/fow-performance` | `1f1a3fc2b561221618df11663a6723cc4634ca47` | BLOCKED — not reviewed |
| `agent/game-styled-scrollbars-tooltips` | `3eb2762721b09965b6721c26bc6accb5cd4794fd` | BLOCKED — not reviewed |
| `agent/harden-tls-identity` | `ca8ad04b5b7b6197f271a6cc191ede74972016e2` | BLOCKED — not reviewed |
| `agent/headless-linux-server` | `a6efa55aea0f6f7de988f0df72b49ff042d49976` | BLOCKED — not reviewed |
| `agent/hotfix-shipyard-scroll` | `eb21c8496a834fbc73e6987998ce394a94fd5d01` | BLOCKED — not reviewed |
| `agent/immutable-whitelist-identities` | `c3ae533142ce1a6e322d7f23269361fc75879ea2` | BLOCKED — not reviewed |
| `agent/integrate-launcher-wormhole-fixes` | `773d5ea80e4d075ef04c5cd2000fa3d29384a0f4` | BLOCKED — not reviewed |
| `agent/integrate-linux-and-crafting` | `75fbe9fda433fee9fe53c85820705ce591eb99d7` | BLOCKED — not reviewed |
| `agent/intermediate-crafting` | `c1f51cb0b7806f9fe7d5eca153bc97b83c482929` | BLOCKED — not reviewed |
| `agent/issue-100-build-identity` | `4dd5635152370bf57c0ae242ec129a8ab621cab4` | BLOCKED — not reviewed |
| `agent/issue-101-multiplayer-compatibility` | `063fe68050356d13c022f8a0e39a552167e69a04` | BLOCKED — not reviewed |
| `agent/issue-214-device-id-case-match` | `8d501479164b532866b06e3a5152cdb11c9fce0a` | BLOCKED — not reviewed |
| `agent/issue-289-completion-staging` | `2df123c1d7afa9002e49575003192f70e62e90b6` | BLOCKED — not reviewed |
| `agent/issue-289-final-hardening` | `085463c5bbf48cf4eb6fc9f25d7c9f2334e1b27c` | BLOCKED — not reviewed |
| `agent/issue-289-final-isolation` | `56a570eb2f64ac8069bc9fbe43756377a48fce68` | BLOCKED — not reviewed |
| `agent/issue-289-fow-restart` | `36c2a6b67a3c8e9770792831a597a85b0a432063` | BLOCKED — not reviewed |
| `agent/issue-289-fow-restart-v2` | `c4162d2e33e6df388589b3304a9136fcabd58f4a` | BLOCKED — not reviewed |
| `agent/issue-289-loadouts` | `4ffd9132b909cc7c38a2cccf7c89f4ec54b0f9f0` | BLOCKED — not reviewed |
| `agent/issue-289-source-export-base` | `0eeeb43b644bf1ee760c30aac9c2aa32d470126b` | BLOCKED — not reviewed |
| `agent/issue-290-control-groups` | `d09693cc283af77ad58e425be487a2047a9026c1` | BLOCKED — not reviewed |
| `agent/issue-290-main-baseline` | `f148cc27623dbf03015146ea1f4f37b5ef15e8d2` | BLOCKED — not reviewed |
| `agent/issue-291-command-queues` | `dd11f0aa9202be8fda01644902682afc1ed4f07b` | BLOCKED — not reviewed |
| `agent/issue-292-combat-policies` | `40e2440b5ea5a2ccd2793cada8e9ebafec98490a` | BLOCKED — not reviewed |
| `agent/issue-293-completion` | `9da4fd112d710dbe5f6c9918cfef0e75d213826e` | BLOCKED — not reviewed |
| `agent/issue-293-convoy-hardening` | `ee53d8195cf4392b235b7005ff21008d8eb9c82e` | BLOCKED — not reviewed |
| `agent/issue-293-logistics-routes` | `c861deacf46babd91576a14ebac272fda40f0383` | BLOCKED — not reviewed |
| `agent/issue-294-production-policies` | `9042a5d2c8c9869e842f98dfd3a8616f4417974f` | BLOCKED — not reviewed |
| `agent/issue-295-comms` | `1b6e704acd2a1e69f74be529ad8fa1ff5ef12ddf` | BLOCKED — not reviewed |
| `agent/issue-62-first-run-tutorial` | `1566ade7ae6de6263887a2669a48bf0b48ed30ff` | BLOCKED — not reviewed |
| `agent/issue-62-first-run-tutorial-sync` | `015ff36ccf5764d83745dd297e1e16a66be127bb` | BLOCKED — not reviewed |
| `agent/issue-66-match-objectives` | `a80e14ab973d4edcd0eaea4b1c1555810fd223a8` | BLOCKED — not reviewed |
| `agent/issue-69-authoritative-fog` | `b9cfaf54a41c9deeb4b17f8d7e7c3212d85ac5a1` | BLOCKED — not reviewed |
| `agent/issue-69-fog-implementation` | `3c4973f17797af6c5ac9dca68cbee6ef1475afa1` | BLOCKED — not reviewed |
| `agent/issue-73-diplomacy` | `cb3b6d93bedea3032e748f3371f21f9c0c4f52ce` | BLOCKED — not reviewed |
| `agent/issue-96-session-recovery` | `8aef1c3cc0907778ae35d40ec38a3b85f46e1a5b` | BLOCKED — not reviewed |
| `agent/lan-discovery-recent-servers` | `ed9e308e6ecb904e782abd7833b93dfe1034ad2e` | BLOCKED — not reviewed |
| `agent/live-diplomacy` | `1a86fd9677bf4ed8de05c62f0b59cb2d0ac00cca` | BLOCKED — not reviewed |
| `agent/move-persistence-off-tick` | `6fae5606d4f8de16384bf9b8bbe3e476bf01ff5b` | BLOCKED — not reviewed |
| `agent/native-production-menu-validation` | `b5c685aadbf709db832b99ca39b72ba9ed5b3a9a` | BLOCKED — not reviewed |
| `agent/observation-retention-controls` | `a93c44c1b9a66c89f15a41632aa1179f03f7579b` | BLOCKED — not reviewed |
| `agent/persist-logistics-production-queue-combined` | `150edd389c5ec7ed8f14d110e0a70f97ecf47d88` | BLOCKED — not reviewed |
| `agent/persist-pruned-state-199` | `19d25f2370ac6fa5207ee97f5d817cfd992c9f6a` | BLOCKED — not reviewed |
| `agent/preauth-connection-limits` | `495bf888256c201a22ca51f0e6bc11d932acfa27` | BLOCKED — not reviewed |
| `agent/prepare-0.1.5-release` | `91333633f662f0818ab172b3410564e2da6161a3` | BLOCKED — not reviewed |
| `agent/prepare-v1.7.0-release` | `c3a0c69b854b2efccb4d49da8973950297b6faff` | BLOCKED — not reviewed |
| `agent/prevent-active-session-takeover-20260711` | `d50d64b6c116ffdfb32810b97018c438c9e01957` | BLOCKED — not reviewed |
| `agent/production-queues` | `d2d3c290b39e2b27672f931225ef2e83fc297337` | BLOCKED — not reviewed |
| `agent/protected-dev-token-input` | `4d35cc63b5131274a1478a2f09d8b1c6016e8f90` | BLOCKED — not reviewed |
| `agent/radar-dispatch-caps` | `d1ff92db5ff052db9102b49262b65c844925c9e2` | BLOCKED — not reviewed |
| `agent/recover-stale-client-signin` | `ca62414f795b3af90d786a30c9fe78993e8ef042` | BLOCKED — not reviewed |
| `agent/redact-malformed-packet-logs` | `d722c7e66fe96906b00e3c7cf695938d551aea44` | BLOCKED — not reviewed |
| `agent/reject-malformed-production-queues` | `d85d0d22ed2b5df86423bc69f4e9618aceafc0c1` | BLOCKED — not reviewed |
| `agent/reject-unknown-rule-ids` | `19c29ba7c2d239eea9b217e4a8e8de5ad70f5cae` | BLOCKED — not reviewed |
| `agent/release-v1.5.4-connectivity-fix` | `247006d9166c0f3c600f78ee8195bb1a8a630468` | BLOCKED — not reviewed |
| `agent/release-v1.5.6-tls-identity` | `ae79a1d281e42aa14f13577bc3bdd1f83fc5340c` | BLOCKED — not reviewed |
| `agent/release-v1.5.7-loopback-tls` | `b5957650c197532a618257b8fbc9e9235b5906ec` | BLOCKED — not reviewed |
| `agent/release-v1.5.7-loopback-tls-control` | `b5957650c197532a618257b8fbc9e9235b5906ec` | BLOCKED — not reviewed |
| `agent/remove-graphical-host` | `dceaab6564da1fd22063d7c679c55b090c5f698e` | BLOCKED — not reviewed |
| `agent/remove-redundant-launchers-113` | `791a5b8fda9766af12029ceed6900e0bd6d32d95` | BLOCKED — not reviewed |
| `agent/remove-white-ui-frames` | `7a47bfb74a5eacd373d25b5d7f56adcf42a0ae01` | BLOCKED — not reviewed |
| `agent/resource-catalog-tabs-search` | `cc80ac336951daad30ccbd2f7629fffed1192464` | BLOCKED — not reviewed |
| `agent/resource-system-catalog` | `1074d08b8ffa1efb6d33a9bdfc70bd3da8e8ad47` | BLOCKED — not reviewed |
| `agent/retained-identity-lifecycle` | `73fc2950c10f0d4054b76bcc7fad73e29d9a13d6` | BLOCKED — not reviewed |
| `agent/sanitize-terminal-player-text` | `f3f4bdace6e5b01036dd18bf54bcea3fa67f9043` | BLOCKED — not reviewed |
| `agent/schedule-inactive-systems` | `fefc3fbb410d1d5ac0361908b82d6b07c1670acf` | BLOCKED — not reviewed |
| `agent/secure-client-credentials` | `e5a5425f0dfb2a17ffe139657eeed62fa6382bf5` | BLOCKED — not reviewed |
| `agent/secure-remote-client-launcher` | `cc75c3de13c621095ecc0d4a0692ec68a5a6c95d` | BLOCKED — not reviewed |
| `agent/separate-server-storage` | `f35292b864ddf2c4af25d2395ceede06f03ad910` | BLOCKED — not reviewed |
| `agent/server-auto-production-narration` | `42937a297ad50aa424c803a66ab03a233e09b2b4` | BLOCKED — not reviewed |
| `agent/server-console-commands` | `40e439351b3ba24386bab2118554dcd5a32d05e8` | BLOCKED — not reviewed |
| `agent/server-scoped-password-verifiers` | `59f15dc793b39a3d1c1c12638c7b675dc2c756fb` | BLOCKED — not reviewed |
| `agent/skirmish-presets` | `130c64608765bd79d92672271d6881220f665df9` | BLOCKED — not reviewed |
| `agent/static-galaxy-territory-expansion` | `11d0d2915f7af9c7598dd82c5b5a7365a3ca577c` | BLOCKED — not reviewed |
| `agent/station-control-menus` | `25c09f8dc99a2077243c3c1e9ea7f2a6a48beb58` | BLOCKED — not reviewed |
| `agent/strict-save-schema` | `4e62bc4d2575feca96db714999a81b65d4d80d17` | BLOCKED — not reviewed |
| `agent/tcp-network-refactor` | `38e1318eb48351da854bf5a0947f1895973762ae` | BLOCKED — not reviewed |
| `agent/trigger-player-save-hotfix` | `46fc797a5c1b468a036eab607e418febddc57ad2` | BLOCKED — not reviewed |
| `agent/validate-v160-upgrade` | `af72f5c0eee6d42cabc9aa48b019a1a82df1dbe8` | BLOCKED — not reviewed |
| `agent/verify-activity-journal-rollover` | `27499b8e985f6aaa00404cf6e6fe9eda00e262d2` | BLOCKED — not reviewed |
| `audit/2026-09-11-complete-source-omissions` | `eeb73796af8e3f563aadaf6b40e64c6ee76e1820` | BLOCKED — not reviewed |
| `auto-distribution-logistics` | `80eccb8badacec00287350b1c4ddaaf71ec0d86f` | BLOCKED — not reviewed |
| `automation/ai-lifecycle-fix` | `c0ff8e18a04cb8fad37e34ec6b112606264ace5c` | BLOCKED — diverged +217/−1337 vs candidate |
| `backup/pr-300-release` | `5d3557ffca6be601134915f972acffce6f8441d5` | BLOCKED — not reviewed |
| `client-dev-mode-handshake` | `3436ff9d1fa6d8004bef75d8b8de52fc6f70cf44` | BLOCKED — not reviewed |
| `codex-server-save-state` | `6df1123c863ab39fe6c439c1cfb96aba4aebc13d` | BLOCKED — not reviewed |
| `design/grounded-visual-palette` | `8e8504b4fc74ee2c6bc40ade36781f1d973c915f` | BLOCKED — not reviewed |
| `devmode-hybrid-crafting` | `bf5cac30b9aca7962987fc313162fe4030b14b9f` | BLOCKED — not reviewed |
| `feat/dev-perf-overlay-75` | `ff3e20c9b878e0e41ac64165620573ead8163796` | BLOCKED — not reviewed |
| `feat/unit-orders-74` | `94668ed089d8adf4c52c630725b6ca2c58248afd` | BLOCKED — not reviewed |
| `feature/celestial-gameplay` | `3aa168f1205cd98739c44c04f9069c1a0518b3b2` | BLOCKED — not reviewed |
| `feature/centralized-manufacturing-ui` | `8b1528a8b301b94cd764c7997234039b2f005ccc` | BLOCKED — diverged +34/−566 vs candidate |
| `feature/dynamic-galaxy-events` | `6747e83fdc63005e245b90b79595e5df63b16fb3` | BLOCKED — not reviewed |
| `feature/dynamic-star-systems` | `63a355f824919bd55112a7a3a0865a4764bcc247` | BLOCKED — not reviewed |
| `feature/game-menu` | `33d0b3b7b4e8b6745db444a6d3142e2caea03e6c` | BLOCKED — not reviewed |
| `feature/issue-296-observer-sessions` | `c7a575d34a7a1ab30efecab35b40ec484fc50e44` | BLOCKED — not reviewed |
| `feature/issue-298-empire-overview` | `60ea4177e7d4987af198b57683894b1faeae1b13` | BLOCKED — not reviewed |
| `feature/issue-384-scenario-framework` | `9a5165c3dcfd55c7c58ca71c082b97ea5fbc6db7` | BLOCKED — diverged +35/−479 vs candidate |
| `feature/json-galaxy-events-v3` | `f16c4b4173c9c4a5699c9d54798efc2dcab7764e` | BLOCKED — not reviewed |
| `feature/lab-manufacturing-fuel` | `48e866dc54f18c55840029a2a4bf65bc542a722e` | BLOCKED — not reviewed |
| `feature/live-ship-explosions` | `f9d1aabf4858a0cae20d2746cf8bd4b52fc3aef7` | BLOCKED — not reviewed |
| `feature/local-miner-ai` | `525d9153a79b21ac7f57e5e15ab21c243762c753` | BLOCKED — not reviewed |
| `feature/manufacturing-procedural-icons` | `ca020df59631d102c7dc96ddb373db6c8a695622` | BLOCKED — not reviewed |
| `feature/multiplayer-chat-and-tactical-map-pings` | `58c382f6092fde329c795b6c787a93a51cb7633d` | BLOCKED — not reviewed |
| `feature/new-player-progression-guided-start-20261105` | `a1691ea8a5d85643102eaf69ebafc3e473be8684` | INTEGRATED — PR ancestry checked |
| `feature/phase-one-npcs` | `21748fd4a31a71b90e2c9d5fa9c460d11b56a667` | BLOCKED — not reviewed |
| `feature/radar-orbital-belt-tracking-20261106` | `8a09e80af8ccffd00987671d714b4d84d3845788` | INTEGRATED — PR ancestry checked |
| `feature/research-unlocks` | `bb27d7d39bb30f5f7c9e5e9044ba1863ea5a9d69` | BLOCKED — not reviewed |
| `feature/solo-save-load` | `b547f79270bb066178bf3f1618c5102cacb61d81` | INTEGRATED — PR ancestry checked |
| `feature/world-lifecycle-resource-performance-20261021` | `035a8e8eec20bafa453eec012f833e09a92d8ee0` | INTEGRATED — PR ancestry checked |
| `feature/world-loot-salvage` | `7fd77ddee839385213d1316a16a85de7270f8c7d` | BLOCKED — not reviewed |
| `finish/2026-09-10-acceptance-gaps` | `13d66f533857670e382a6491a25b615b23560aa2` | BLOCKED — not reviewed |
| `fix-issue-19-deployer-idle-range` | `37f1cbc83b0efaba1901b09377a0ead5331df1aa` | BLOCKED — not reviewed |
| `fix-issue-45-dev-research-bypass` | `586d246088f5efdd1416643ab107a149be662f4f` | BLOCKED — not reviewed |
| `fix-respawn-camera-31` | `7dad384cffb7e823209514ce183d4f8f12809486` | BLOCKED — not reviewed |
| `fix/2026-09-10-complete-acceptance-audit` | `2c3d457ff8f034623d9b705b5716056679f2cc9a` | BLOCKED — not reviewed |
| `fix/2026-09-10-issue-389-style-contract` | `ff322148b3372df7229d96f1d1b5103b7568314a` | BLOCKED — not reviewed |
| `fix/398-contextual-station-overlays` | `313b1c17ac6e6975ebb0d8ac36f494ef820808bf` | BLOCKED — not reviewed |
| `fix/398-contextual-station-overlays-current` | `b206258711a5d25d10fd437aa72938cad2b50506` | BLOCKED — not reviewed |
| `fix/audio-system-scope` | `fa7eb19f03009a8d05f7a030629e21e87989963b` | BLOCKED — not reviewed |
| `fix/background-system-simulation` | `d4b057a7f50eb30eddee48ab486cb6be2c25974b` | BLOCKED — not reviewed |
| `fix/celestial-extraction-refire-sync-vfx` | `40de9369492009c0cbd7f88b8f7aca66a2b6f9f1` | BLOCKED — not reviewed |
| `fix/ci-checks-57` | `ac054407e3a542c0623303f2324b228be63f4584` | BLOCKED — not reviewed |
| `fix/cross-system-production-logistics` | `5bb7ef0ba871a67a573ab4bfa5bd11a3123ca724` | BLOCKED — not reviewed |
| `fix/dedicated-server-storage` | `c9b0b87f9b1d14c95ad538c50ff9b20a365d12a4` | BLOCKED — not reviewed |
| `fix/deployer-package-selection-13` | `56604276a913ac594a86b99b9016e553b70fe437` | BLOCKED — not reviewed |
| `fix/duplicate-player-names` | `f0e6a5e91455c34e9e1faaeac1a7f5202d02b6f4` | BLOCKED — not reviewed |
| `fix/formation-arrival-stability` | `8ba3893aebc838a74ddbbe119fdae42150866d36` | BLOCKED — not reviewed |
| `fix/idle-miner-station-self-dispatch-16` | `a92a3815e8eae74380897ef02549bc11a9625429` | BLOCKED — not reviewed |
| `fix/instance-player-registry-59` | `d53221753188f957c594ce10812ea9f2a2e72da2` | BLOCKED — not reviewed |
| `fix/issue-28-spawn-spacing` | `67af33eca7c8a3fe129cea96306212a376ba4f3b` | BLOCKED — not reviewed |
| `fix/issue-319-queued-deployer-package` | `706fe64dd90178588a1db178bb0b3a817c63491b` | BLOCKED — not reviewed |
| `fix/issue-320-radar-wormhole-search` | `48f3fe252dcc8adf5dcda7908e61132bd592a27a` | BLOCKED — not reviewed |
| `fix/issue-320-remembered-wormhole-rendering` | `eb7d7bfd4ed1cbd7a95e95076892f9613cf682ec` | BLOCKED — not reviewed |
| `fix/issue-34-double-click-type-select` | `24015411833e94ef300231453e059cf79c8e775d` | BLOCKED — not reviewed |
| `fix/issue-36-balanced-miner-dispatch` | `67f061415cba8892b254bccc6885fe9e1a81b4d6` | BLOCKED — not reviewed |
| `fix/issue-36-spread-miners` | `7c36318e3eeb3ac9b4084dff01fda63d5ef35553` | BLOCKED — not reviewed |
| `fix/issue-371-production-policy-sync` | `72b4fc9db212606bed493fcc2da44efcddd114df` | BLOCKED — not reviewed |
| `fix/issue-372-fog-performance` | `0b1dad7a5c626ef9344ccc7797ca2902c1c513c3` | BLOCKED — not reviewed |
| `fix/issue-395-respawn-authority` | `8cd64fe96278c87c53cdf020395606847f72d2cb` | BLOCKED — not reviewed |
| `fix/issue-400-session-resume-proof` | `46580cb76a89227769e5298747dc7c1e9b8c40b9` | BLOCKED — not reviewed |
| `fix/issue-402-auth-source-address` | `7b80fd9d3fe8d3a40c446931f42a80c22e09ebf2` | BLOCKED — not reviewed |
| `fix/issue-404-preauth-handshake-hardening` | `78dc7993d11284d549119379744d96f7be3906ca` | BLOCKED — not reviewed |
| `fix/issue-406-tls-first-use` | `1eb6466eb430e73ed68439ee230130a9e872b0d8` | BLOCKED — not reviewed |
| `fix/issue-47-miner-anchor-return` | `04811b7cca1fab4f24f4c8cb536f15913f37ad3b` | BLOCKED — not reviewed |
| `fix/issues-98-99-network-hardening` | `d4fedf597fd0f0258da929415c440e06c667319b` | BLOCKED — not reviewed |
| `fix/menu-text-horizontal-overflow` | `996272c0a64cd3282d22f4b9756706c6173c0234` | BLOCKED — not reviewed |
| `fix/miner-range-toggle` | `f6f1411ad50ad40d8bfd2bb912a920387f187e23` | BLOCKED — not reviewed |
| `fix/multiplayer-join-reconnect-stability-20261106` | `e0f6206924cc521b39f7d745f5598680099cff3c` | INTEGRATED — PR ancestry checked |
| `fix/packet-chunks-instance-safe` | `22a33f79f8e9b8dbdb4d4876d6912c173a4ca9c2` | BLOCKED — not reviewed |
| `fix/planet-station-orbit-anchoring` | `92f01826597a63724d9fb744b3066ed96883f30f` | BLOCKED — not reviewed |
| `fix/player-ship-colors` | `96886f8f50a77715def87f9ccff721fa1f1533ac` | BLOCKED — not reviewed |
| `fix/rules-validator-76` | `16019558ec88bca1f27c17a92b18745eb373b3a1` | BLOCKED — not reviewed |
| `fix/ship-visual-facing` | `006cd7bd63165921f5c1cc0b2d685b39f933cf61` | BLOCKED — not reviewed |
| `fix/shipyard-station-packages` | `1829f54e92c8a2359d29d0d939b3bf74a9cc679c` | BLOCKED — not reviewed |
| `fix/stabilization-validation-20261105` | `c2afdda582a200bf07274082ed9e05c902c29693` | INTEGRATED — PR ancestry checked |
| `fix/station-deploy-no-explosion` | `adbbb636c16849d0423b0338e5ee27ed5b2c0098` | BLOCKED — not reviewed |
| `fix/tts-backend-detection` | `27260d92483381f25a85a802f2d028fe8feb0e3c` | BLOCKED — not reviewed |
| `fix/v1.8-phase3-auth-docs` | `c449c14fd0eeca11039127410ad3f3aaa74feb4d` | BLOCKED — not reviewed |
| `fleet-formations` | `90f7a57634d3b2d241607ace3c409eb84c402e6c` | BLOCKED — not reviewed |
| `graphics-388-final-integration` | `182d80bdaec18caca55316cf147b8188cbf49521` | BLOCKED — not reviewed |
| `graphics-388-foundation-overhaul` | `dac9801764ff47e1c92ae2479149a64f83d1b8ac` | BLOCKED — diverged +48/−562 vs candidate |
| `graphics-388-pre392-safety` | `566c8e43530f0085bafb7dc2001eba2e790ea117` | BLOCKED — not reviewed |
| `graphics-388-prototype-integration-backup` | `0dad13ff1f530b735237257260a1e4e6a9588b37` | BLOCKED — not reviewed |
| `graphics-388-testbase` | `566c8e43530f0085bafb7dc2001eba2e790ea117` | BLOCKED — not reviewed |
| `graphics-392-onto-388-test` | `1218a0b32c9a4ffbfa10559eea156dc2e941f8ba` | BLOCKED — not reviewed |
| `graphics/issue-392-celestial-rendering` | `b825aac64cc0b5c8060cb5717f19429309b0f9db` | BLOCKED — not reviewed |
| `hotfix/v1.8.2-multiplayer-join` | `b110d2ec39e46ca00dcf6ab1b7eae4ced241d46c` | BLOCKED — not reviewed |
| `integration-392-temp` | `566c8e43530f0085bafb7dc2001eba2e790ea117` | BLOCKED — not reviewed |
| `integration/2026-09-09-features` | `ec2cc8edbb06880c37c3ba721db9e842b88514a9` | BLOCKED — not reviewed |
| `integration/2026-09-09-final` | `6a21395e25090b56d4b3a81a2ad8c8db47842e27` | BLOCKED — not reviewed |
| `integration/2026-09-09-graphics` | `9ec1907b15f1243e792571c894e84c808bf9efd7` | BLOCKED — not reviewed |
| `integration/2026-09-09-security` | `ed0d8a4e55099557228501bc4b93b4c1388f6469` | BLOCKED — not reviewed |
| `integration/four-branch-20261105` | `6aab3eb7733ac6aeead4029d6594cfca2788ddcb` | INTEGRATED — PR ancestry checked |
| `integration/four-plus-orbital-radar-20261106` | `4a3d37615744a5b9776a977cba8c20eed5c3d0ca` | INTEGRATED — PR ancestry checked |
| `integration/four-plus-radar-plus-multiplayer-stability-20261107` | `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f` | INTEGRATED — PR ancestry checked |
| `integration/starchem-all-ready-20261117` | `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f` | INTEGRATED — PR ancestry checked |
| `integration/starchem-functional-2026-08-27` | `a08f6c08518dc516ba8c5089109ed66c610b697d` | BLOCKED — not reviewed |
| `issue-380-persistent-fleets` | `266d37b04f1e3a79dc2eea363853bd6e4c3d23b2` | BLOCKED — not reviewed |
| `issue-380-persistent-fleets-finish` | `218e701134c491bd35bf60d9650f632239a8b02c` | BLOCKED — not reviewed |
| `issue-381-system-control-v2` | `4ddde683a1136d712e9d39bec37541c7ccaf9b9c` | BLOCKED — not reviewed |
| `issue-382-research-tech-tree` | `260fc68c50de55a5564856a984aa7b64963adc36` | BLOCKED — not reviewed |
| `issue-382-research-tech-tree-refresh` | `cd3f057182224bb96b49fcdc48b3975c7fc2aaa3` | BLOCKED — not reviewed |
| `issue-383-procedural-galaxy` | `6cefd0acc818132d34f51fcc1bd7f1b44fb2ef09` | BLOCKED — not reviewed |
| `issue-385-rich-visuals` | `8ca7f383d6bd2e7fa44172fad4fea6e4316f6e7a` | BLOCKED — not reviewed |
| `issue-385-rich-visuals-v2` | `f401b55b9ac655089ee667b866d988ef1015eb35` | BLOCKED — not reviewed |
| `issue-385-visual-definition-finish` | `7a9103baf93bf180b1390f266681264d2f9f82b1` | BLOCKED — not reviewed |
| `issue-386-formation-v2` | `a6ee2c3d789c25d369b877301b83fe09d53be35f` | BLOCKED — not reviewed |
| `issue-387-production-diagnostics` | `7b3d54ea36ce7200c8bb843a134dca1cee1d7e20` | BLOCKED — not reviewed |
| `issue-389-visual-design-language` | `a29a5f8aee34665bc867c62feab2db77ac1376e0` | BLOCKED — not reviewed |
| `issue-390-visual-content-architecture` | `a9f1e69150f6bce3b0a85f56072af91293715d4d` | BLOCKED — not reviewed |
| `issue-391-space-backgrounds` | `67cfb5bf5ced2c9e01431fb5040b04547de628d2` | BLOCKED — not reviewed |
| `issue-393-authored-ship-hulls` | `f4f79f208b31b2195578e5f7f1df6536e3386e9e` | BLOCKED — not reviewed |
| `issue-394-ship-materials` | `ba19e50cd7c47578acfa8d68fa91181e10d63915` | BLOCKED — not reviewed |
| `issue-396-station-architecture` | `115e86f567905e715b538d284a8d6e7a184b1bdf` | BLOCKED — not reviewed |
| `issue-399-resource-environments` | `40d6532870e76c34aab6118835c4698be0471695` | BLOCKED — not reviewed |
| `issue-401-art-asset-pipeline` | `0c7e1038a4e6c18004e4824dd4f25e4810421f33` | BLOCKED — not reviewed |
| `issue-403-combat-vfx` | `4417bfa0d0a04c326d8a8580f2a02858313cc1e5` | BLOCKED — not reviewed |
| `issue-405-destruction-effects` | `82011c98747e536c0443a64b0d92f5d7e04a0062` | BLOCKED — not reviewed |
| `issue-407-nebula-expanse-vertical-slice` | `e4848553afdf2e857508818fb206e7276cfe7eda` | BLOCKED — not reviewed |
| `issue-408-render-performance` | `d4d886d72452eb281e7bd91400ffc440937dc201` | BLOCKED — not reviewed |
| `issue-409-ambient-environment` | `4d8d97230eebc28fbe5c1a7a16aa15f235d17ecb` | BLOCKED — not reviewed |
| `issue-409-renderer-core` | `f401b55b9ac655089ee667b866d988ef1015eb35` | BLOCKED — not reviewed |
| `main` | `0c16204cf835bacb25449ee58af016b2e786f3a5` | BASELINE |
| `perf/procedural-sprite-preload` | `e97a391a381a1029fdadac34ef456df6880793ce` | BLOCKED — not reviewed |
| `perf/salvage-fow-331` | `70a1fca5a53e22dc1618181258ba7e3e86ee8e15` | BLOCKED — not reviewed |
| `procedural-audio` | `fd100d4ac0ac155684a6487d8ad6c56b74bed7a9` | BLOCKED — not reviewed |
| `release-publish/v1.8.1` | `f5eba6a0232cc6a90678eb16be4449cb5eab3242` | BLOCKED — not reviewed |
| `release-publish/v1.8.2` | `4c13d7ebb56e9ff7aaf542bd4bd5fcf065a0fef6` | BLOCKED — not reviewed |
| `release/v1.1.0-alpha` | `81075c4618bed69dca7a0e53f6d8ca4628683384` | BLOCKED — not reviewed |
| `release/v1.1.0-alpha.1` | `1ff69fbb312185c336685376bc8881d98086f407` | BLOCKED — not reviewed |
| `release/v1.2.0` | `2b39c05ea509f57de7595828b79a577771712385` | BLOCKED — not reviewed |
| `release/v1.3.0` | `ded8c9d2543fbfc85f07ef315a8482d583d90038` | BLOCKED — not reviewed |
| `release/v1.3.1` | `be5d00c98825875baf94812923135de086a0a23f` | BLOCKED — not reviewed |
| `release/v1.4.0` | `0a43229efdf4e36a23fc8066dddbf7ec91c395a4` | BLOCKED — not reviewed |
| `release/v1.8.0-phase1-hardening` | `868ed7aa4f437cb028d443f7f0724f18764cd93e` | BLOCKED — not reviewed |
| `release/v1.8.0-phase2-version-prep` | `05218ce00eb69d8b925f9a860479036681a70f27` | BLOCKED — not reviewed |
| `release/v1.8.0-phase3-docs-readiness` | `606477550e3efdeda6380017be3431a1f179bd34` | BLOCKED — not reviewed |
| `release/v1.8.0-phase4-rc` | `247af96892794cb640826ff4d75a83248596d261` | BLOCKED — not reviewed |
| `release/v1.8.0-tag-publisher` | `3ec41375157c0674a29f01903c147cfcf53ce79e` | BLOCKED — not reviewed |
| `release/v1.8.1` | `8850fbbe621ca8dec2976997a5584fdafb10ca37` | BLOCKED — not reviewed |
| `resource-size-variety` | `e0103710978491bde4b12dfd16c285454db82b57` | BLOCKED — not reviewed |
| `salvager-tractor-beams` | `4bc006a0b0d51a82025d78b66e5060c987873cc4` | BLOCKED — not reviewed |
| `security/issue-397-observer-conversion` | `2c065142e81c11df3f1530ac1938aabaaa9d8732` | BLOCKED — not reviewed |
| `seeded-ship-variety` | `3ce562c29b75cb490199716e5cf1a311f7a48d81` | BLOCKED — not reviewed |
| `tmp-392-import-checkpoint` | `566c8e43530f0085bafb7dc2001eba2e790ea117` | BLOCKED — not reviewed |
| `tmp-392-import-checkpoint2` | `566c8e43530f0085bafb7dc2001eba2e790ea117` | BLOCKED — not reviewed |
| `tmp-392-import-checkpoint3` | `566c8e43530f0085bafb7dc2001eba2e790ea117` | BLOCKED — not reviewed |
| `tmp/2026-09-10-fleet-lifecycle-merge` | `eeb73796af8e3f563aadaf6b40e64c6ee76e1820` | BLOCKED — not reviewed |

## Priority divergent branches

The following compare results show history not contained in the candidate. They do not demonstrate that all unique patches are still needed. A blind merge can regress current behavior.

### `agent/fix-corsair-remote-view`

- GitHub compare relative to candidate: 309 commits ahead; 1337 behind; status diverged.
- Changed-path inventory returned by comparison (75 paths): `.github/workflows/ai-validation.yml`, `.github/workflows/phase6-expedition-stress.yml`, `.github/workflows/release.yml`, `.gitignore`, `README.md`, `RELEASE_NOTES.md`, `config/npcs.json`, `config/ships/early.json`, `docs/AI_BRAIN_LOG.md`, `gradle/ai-validation.gradle`, `packaging/run-starchem-server.bat`, `packaging/run-starchem.bat`, `settings.gradle`, `src/main/java/com/tndmadman/rts/AiBrainLog.java`, `src/main/java/com/tndmadman/rts/AiBrainLogAsyncWriter.java`, `src/main/java/com/tndmadman/rts/AiBrainLogValidator.java`, `src/main/java/com/tndmadman/rts/AiDevCommands.java`, `src/main/java/com/tndmadman/rts/AiDevLog.java`, `src/main/java/com/tndmadman/rts/AiDevPanel.java`, `src/main/java/com/tndmadman/rts/AiDevSnapshot.java`, `src/main/java/com/tndmadman/rts/BuildSystem.java`, `src/main/java/com/tndmadman/rts/DedicatedTcpServerValidator.java`, `src/main/java/com/tndmadman/rts/GamePanel.java`, `src/main/java/com/tndmadman/rts/HaulerSystem.java`, `src/main/java/com/tndmadman/rts/MobileDepot.java`, `src/main/java/com/tndmadman/rts/NpcCollapseSystem.java`, `src/main/java/com/tndmadman/rts/NpcCrossSystemOperationsValidator.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionDefenseValidator.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionReadinessSystem.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionResilienceValidator.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionSystem.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionValidator.java`, `src/main/java/com/tndmadman/rts/NpcFactionCapacitySystem.java`, `src/main/java/com/tndmadman/rts/NpcFactionLifecycleValidator.java`, `src/main/java/com/tndmadman/rts/NpcFactionResetReason.java`, `src/main/java/com/tndmadman/rts/NpcFactionRuntime.java`, `src/main/java/com/tndmadman/rts/NpcFactionScopedRuntimeReset.java`, `src/main/java/com/tndmadman/rts/NpcFactionSpawner.java`, `src/main/java/com/tndmadman/rts/NpcGalaxyDirector.java`, `src/main/java/com/tndmadman/rts/NpcGalaxyDirectorValidator.java`, `src/main/java/com/tndmadman/rts/NpcHomeSystem.java`, `src/main/java/com/tndmadman/rts/NpcMobileDepotSystem.java`, `src/main/java/com/tndmadman/rts/NpcMobileDepotValidator.java`, `src/main/java/com/tndmadman/rts/NpcRecoverySystem.java`, `src/main/java/com/tndmadman/rts/NpcRecoveryValidator.java`, `src/main/java/com/tndmadman/rts/NpcRepairEvacuationSystem.java`, `src/main/java/com/tndmadman/rts/NpcResourceBudget.java`, `src/main/java/com/tndmadman/rts/NpcResourceBudgetCapabilityValidator.java`, `src/main/java/com/tndmadman/rts/NpcResourceBudgetValidator.java`, `src/main/java/com/tndmadman/rts/NpcRules.java`, `src/main/java/com/tndmadman/rts/NpcRuntimeResetValidator.java`, `src/main/java/com/tndmadman/rts/NpcSquadCombatSystem.java`, `src/main/java/com/tndmadman/rts/NpcSquadCombatValidator.java`, `src/main/java/com/tndmadman/rts/NpcStationConstructionSystem.java`, `src/main/java/com/tndmadman/rts/NpcStationConstructionValidator.java`, `src/main/java/com/tndmadman/rts/NpcStationDeployerRecoverySystem.java`, `src/main/java/com/tndmadman/rts/NpcStationDeployerRecoveryValidator.java`, `src/main/java/com/tndmadman/rts/NpcStationReplacementSystem.java`, `src/main/java/com/tndmadman/rts/NpcStrategicDirector.java`, `src/main/java/com/tndmadman/rts/NpcStrategicDirectorValidator.java`, `src/main/java/com/tndmadman/rts/NpcStrategicStabilityValidator.java`, `src/main/java/com/tndmadman/rts/NpcSystem.java`, `src/main/java/com/tndmadman/rts/NpcSystemIsolationValidator.java`, `src/main/java/com/tndmadman/rts/NpcWorkerProductionSystem.java`, `src/main/java/com/tndmadman/rts/NpcWorkerProductionValidator.java`, `src/main/java/com/tndmadman/rts/PeerClientSide.java`, `src/main/java/com/tndmadman/rts/SystemSimulationScheduler.java`, `src/main/java/com/tndmadman/rts/SystemSimulationSchedulerValidator.java`, `src/main/java/com/tndmadman/rts/TcpIntegrationHarness.java`, `src/main/java/com/tndmadman/rts/TcpReconnectIntegrationValidator.java`, `src/main/java/com/tndmadman/rts/TcpRemoteSystemVisibilityValidator.java`, `src/main/java/com/tndmadman/rts/ViewSnapshotReset.java`, `src/main/java/com/tndmadman/rts/World.java`, `src/main/java/com/tndmadman/rts/WorldNetAccess.java`, `src/main/java/com/tndmadman/rts/WormholeGate.java`.
- Current disposition: **BLOCKED** pending `git cherry`, `git range-diff`, patch-equivalence and current-code review; no merge or cherry-pick was performed.

### `automation/ai-lifecycle-fix`

- GitHub compare relative to candidate: 217 commits ahead; 1337 behind; status diverged.
- Changed-path inventory returned by comparison (65 paths): `.github/workflows/ai-validation.yml`, `.github/workflows/apply-ai-lifecycle-fix.yml`, `.github/workflows/publish-ai-lifecycle-fix.yml`, `.gitignore`, `config/npcs.json`, `config/ships/early.json`, `docs/AI_BRAIN_LOG.md`, `gradle/ai-validation.gradle`, `src/main/java/com/tndmadman/rts/AiBrainLog.java`, `src/main/java/com/tndmadman/rts/AiBrainLogValidator.java`, `src/main/java/com/tndmadman/rts/AiDevCommands.java`, `src/main/java/com/tndmadman/rts/AiDevLog.java`, `src/main/java/com/tndmadman/rts/AiDevPanel.java`, `src/main/java/com/tndmadman/rts/AiDevSnapshot.java`, `src/main/java/com/tndmadman/rts/BuildSystem.java`, `src/main/java/com/tndmadman/rts/GamePanel.java`, `src/main/java/com/tndmadman/rts/HaulerSystem.java`, `src/main/java/com/tndmadman/rts/MobileDepot.java`, `src/main/java/com/tndmadman/rts/NpcCollapseSystem.java`, `src/main/java/com/tndmadman/rts/NpcCrossSystemOperationsValidator.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionReadinessSystem.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionResilienceValidator.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionSystem.java`, `src/main/java/com/tndmadman/rts/NpcExpeditionValidator.java`, `src/main/java/com/tndmadman/rts/NpcFactionCapacitySystem.java`, `src/main/java/com/tndmadman/rts/NpcFactionLifecycleValidator.java`, `src/main/java/com/tndmadman/rts/NpcFactionResetReason.java`, `src/main/java/com/tndmadman/rts/NpcFactionRuntime.java`, `src/main/java/com/tndmadman/rts/NpcFactionScopedRuntimeReset.java`, `src/main/java/com/tndmadman/rts/NpcFactionSpawner.java`, `src/main/java/com/tndmadman/rts/NpcGalaxyDirector.java`, `src/main/java/com/tndmadman/rts/NpcGalaxyDirectorValidator.java`, `src/main/java/com/tndmadman/rts/NpcHomeSystem.java`, `src/main/java/com/tndmadman/rts/NpcMobileDepotSystem.java`, `src/main/java/com/tndmadman/rts/NpcMobileDepotValidator.java`, `src/main/java/com/tndmadman/rts/NpcRecoverySystem.java`, `src/main/java/com/tndmadman/rts/NpcRecoveryValidator.java`, `src/main/java/com/tndmadman/rts/NpcRepairEvacuationSystem.java`, `src/main/java/com/tndmadman/rts/NpcResourceBudget.java`, `src/main/java/com/tndmadman/rts/NpcResourceBudgetCapabilityValidator.java`, `src/main/java/com/tndmadman/rts/NpcResourceBudgetValidator.java`, `src/main/java/com/tndmadman/rts/NpcRules.java`, `src/main/java/com/tndmadman/rts/NpcRuntimeResetValidator.java`, `src/main/java/com/tndmadman/rts/NpcSquadCombatSystem.java`, `src/main/java/com/tndmadman/rts/NpcSquadCombatValidator.java`, `src/main/java/com/tndmadman/rts/NpcStationConstructionSystem.java`, `src/main/java/com/tndmadman/rts/NpcStationConstructionValidator.java`, `src/main/java/com/tndmadman/rts/NpcStationDeployerRecoverySystem.java`, `src/main/java/com/tndmadman/rts/NpcStationDeployerRecoveryValidator.java`, `src/main/java/com/tndmadman/rts/NpcStationReplacementSystem.java`, `src/main/java/com/tndmadman/rts/NpcStrategicDirector.java`, `src/main/java/com/tndmadman/rts/NpcStrategicDirectorValidator.java`, `src/main/java/com/tndmadman/rts/NpcStrategicStabilityValidator.java`, `src/main/java/com/tndmadman/rts/NpcSystem.java`, `src/main/java/com/tndmadman/rts/NpcSystemIsolationValidator.java`, `src/main/java/com/tndmadman/rts/NpcWorkerProductionSystem.java`, `src/main/java/com/tndmadman/rts/NpcWorkerProductionValidator.java`, `src/main/java/com/tndmadman/rts/SystemSimulationScheduler.java`, `src/main/java/com/tndmadman/rts/SystemSimulationSchedulerValidator.java`, `src/main/java/com/tndmadman/rts/TcpIntegrationHarness.java`, `src/main/java/com/tndmadman/rts/TcpRemoteSystemVisibilityValidator.java`, `src/main/java/com/tndmadman/rts/ViewSnapshotReset.java`, `src/main/java/com/tndmadman/rts/World.java`, `src/main/java/com/tndmadman/rts/WorldNetAccess.java`, `tools/ai-lifecycle-fix.patch`.
- Current disposition: **BLOCKED** pending `git cherry`, `git range-diff`, patch-equivalence and current-code review; no merge or cherry-pick was performed.

### `graphics-388-foundation-overhaul`

- GitHub compare relative to candidate: 48 commits ahead; 562 behind; status diverged.
- Changed-path inventory returned by comparison (79 paths): `.github/workflows/ci.yml`, `.github/workflows/issue409-ambient-validation.yml`, `.github/workflows/render-performance.yml`, `.github/workflows/ship-visuals.yml`, `.github/workflows/visual-content-validation.yml`, `CONTRIBUTING.md`, `config/starchem.json`, `config/visuals.json`, `config/visuals/celestials.json`, `config/visuals/nebula-expanse.json`, `docs/ART_ASSETS.md`, `docs/issue-409-ambient-environment.md`, `docs/render-performance.md`, `docs/visual-design-language.md`, `gradle/issue409-ambient-validation.gradle`, `gradle/render-performance.gradle`, `scripts/render-performance.ps1`, `scripts/render-performance.sh`, `src/main/java/com/tndmadman/rts/AmbientSystemRenderer.java`, `src/main/java/com/tndmadman/rts/ArtAssetCache.java`, `src/main/java/com/tndmadman/rts/ArtAssetValidator.java`, `src/main/java/com/tndmadman/rts/Base.java`, `src/main/java/com/tndmadman/rts/CatalogCelestialVisualDefinition.java`, `src/main/java/com/tndmadman/rts/CatalogShipVisualDefinition.java`, `src/main/java/com/tndmadman/rts/CelestialRenderValidator.java`, `src/main/java/com/tndmadman/rts/CelestialRenderer.java`, `src/main/java/com/tndmadman/rts/CelestialSpriteCache.java`, `src/main/java/com/tndmadman/rts/CelestialSystem.java`, `src/main/java/com/tndmadman/rts/CelestialVisualCatalog.java`, `src/main/java/com/tndmadman/rts/CelestialVisualClass.java`, `src/main/java/com/tndmadman/rts/CelestialVisualDefinition.java`, `src/main/java/com/tndmadman/rts/CombatVfxSystem.java`, `src/main/java/com/tndmadman/rts/DamageStateEffects.java`, `src/main/java/com/tndmadman/rts/DestructionProfile.java`, `src/main/java/com/tndmadman/rts/ExplosionEffect.java`, `src/main/java/com/tndmadman/rts/IntelStructureRenderer.java`, `src/main/java/com/tndmadman/rts/Issue391SpaceBackgroundValidator.java`, `src/main/java/com/tndmadman/rts/Issue394ShipMaterialValidator.java`, `src/main/java/com/tndmadman/rts/Issue396StationVisualValidator.java`, `src/main/java/com/tndmadman/rts/Issue399ResourceVisualValidator.java`, `src/main/java/com/tndmadman/rts/Issue403CombatVfxValidator.java`, `src/main/java/com/tndmadman/rts/Issue405DestructionEffectsValidator.java`, `src/main/java/com/tndmadman/rts/Issue407GraphicsBenchmark.java`, `src/main/java/com/tndmadman/rts/Issue409AmbientEnvironmentValidator.java`, `src/main/java/com/tndmadman/rts/PerfOverlay.java`, `src/main/java/com/tndmadman/rts/RenderPerformanceValidator.java`, `src/main/java/com/tndmadman/rts/ResourceFieldRenderer.java`, `src/main/java/com/tndmadman/rts/ResourceNode.java`, `src/main/java/com/tndmadman/rts/ShipShape.java`, `src/main/java/com/tndmadman/rts/ShipSpriteCache.java`, `src/main/java/com/tndmadman/rts/ShipSurfaceArt.java`, `src/main/java/com/tndmadman/rts/ShipVfxProfile.java`, `src/main/java/com/tndmadman/rts/ShipVisualCatalog.java`, `src/main/java/com/tndmadman/rts/ShipVisualDefinition.java`, `src/main/java/com/tndmadman/rts/ShipVisualStyle.java`, `src/main/java/com/tndmadman/rts/ShipVisualValidator.java`, `src/main/java/com/tndmadman/rts/SpaceBackgroundRenderer.java`, `src/main/java/com/tndmadman/rts/StationPresentation.java`, `src/main/java/com/tndmadman/rts/StationPresentationValidator.java`, `src/main/java/com/tndmadman/rts/StationRenderer.java`, `src/main/java/com/tndmadman/rts/StationVisualDefinition.java`, `src/main/java/com/tndmadman/rts/SystemVisualDefinition.java`, `src/main/java/com/tndmadman/rts/UnitRenderer.java`, `src/main/java/com/tndmadman/rts/VisualCatalog.java`, `src/main/java/com/tndmadman/rts/VisualContentValidator.java`, `src/main/java/com/tndmadman/rts/WeaponSystem.java`, `src/main/java/com/tndmadman/rts/WeaponVfxStyle.java`, `src/main/java/com/tndmadman/rts/WorldRenderCandidates.java`, `src/main/resources/art/README.md`, `src/main/resources/art/backgrounds/.gitkeep`, `src/main/resources/art/decals/.gitkeep`, `src/main/resources/art/effects/.gitkeep`, `src/main/resources/art/materials/hull-panels-01.png`, `src/main/resources/art/planets/.gitkeep`, `src/main/resources/art/ships/.gitkeep`, `src/main/resources/art/stations/.gitkeep`, `src/main/resources/art/validation/README.md`, `src/main/resources/art/validation/corrupt-image.png`, `validation/run-release-regressions.sh`.
- Current disposition: **BLOCKED** pending `git cherry`, `git range-diff`, patch-equivalence and current-code review; no merge or cherry-pick was performed.

### `feature/centralized-manufacturing-ui`

- GitHub compare relative to candidate: 34 commits ahead; 566 behind; status diverged.
- Changed-path inventory returned by comparison (16 paths): `src/main/java/com/tndmadman/rts/BuildSystem.java`, `src/main/java/com/tndmadman/rts/CombatPolicyHud.java`, `src/main/java/com/tndmadman/rts/FleetSelectionOverlay.java`, `src/main/java/com/tndmadman/rts/GameCamera.java`, `src/main/java/com/tndmadman/rts/GameFrame.java`, `src/main/java/com/tndmadman/rts/GamePanel.java`, `src/main/java/com/tndmadman/rts/ManufacturingOverlay.java`, `src/main/java/com/tndmadman/rts/ManufacturingPolicyPanel.java`, `src/main/java/com/tndmadman/rts/ProductionPolicyCommandBridge.java`, `src/main/java/com/tndmadman/rts/ProductionPolicyMenu.java`, `src/main/java/com/tndmadman/rts/SelectionPerformanceValidator.java`, `src/main/java/com/tndmadman/rts/SelectionRenderPolicy.java`, `src/main/java/com/tndmadman/rts/SelectionSummaryHud.java`, `src/main/java/com/tndmadman/rts/StationControlMenu.java`, `src/main/java/com/tndmadman/rts/UnitRenderer.java`, `src/main/java/com/tndmadman/rts/WeaponSystem.java`.
- Current disposition: **BLOCKED** pending `git cherry`, `git range-diff`, patch-equivalence and current-code review; no merge or cherry-pick was performed.

### `feature/issue-384-scenario-framework`

- GitHub compare relative to candidate: 35 commits ahead; 479 behind; status diverged.
- Changed-path inventory returned by comparison (22 paths): `.github/workflows/scenario-validation.yml`, `config/scenarios/conquest-path.json`, `config/scenarios/corsair-endurance.json`, `config/scenarios/frontier-industry.json`, `config/scenarios/index.json`, `src/main/java/com/tndmadman/rts/App.java`, `src/main/java/com/tndmadman/rts/AuthoritativeSystemScheduler.java`, `src/main/java/com/tndmadman/rts/EndStatePanel.java`, `src/main/java/com/tndmadman/rts/GalaxyRuntimeOptions.java`, `src/main/java/com/tndmadman/rts/ScenarioDirector.java`, `src/main/java/com/tndmadman/rts/ScenarioFrameworkValidator.java`, `src/main/java/com/tndmadman/rts/ScenarioInitializer.java`, `src/main/java/com/tndmadman/rts/ScenarioLaunch.java`, `src/main/java/com/tndmadman/rts/ScenarioMetrics.java`, `src/main/java/com/tndmadman/rts/ScenarioObjectiveBridge.java`, `src/main/java/com/tndmadman/rts/ScenarioRules.java`, `src/main/java/com/tndmadman/rts/SkirmishRuntime.java`, `src/main/java/com/tndmadman/rts/SnapshotValidator.java`, `src/main/java/com/tndmadman/rts/SystemSimulationScheduler.java`, `src/main/java/com/tndmadman/rts/TcpConnectionIdentityValidator.java`, `src/main/java/com/tndmadman/rts/WorldNetAccess.java`, `validation/run-release-regressions.sh`.
- Current disposition: **BLOCKED** pending `git cherry`, `git range-diff`, patch-equivalence and current-code review; no merge or cherry-pick was performed.

## Required follow-up to complete the audit

1. For every blocked branch, compare main-relative ahead/behind and its commit graph, unique patch IDs, whole file diff and feature/test behavior.
2. Mark individual branch content as INTEGRATED, EQUIVALENT, SUPERSEDED, OBSOLETE, MISSING, or BLOCKED with evidence. Do not infer status from names.
3. For each verified MISSING patch, import only compatible changes, run focused tests, and document commit hashes and conflict resolutions.
4. Run comprehensive build, canonical release regressions, performance benchmark, real Windows GUI, two-client multiplayer, and genuine first-Hauler gameplay acceptance on the final code candidate.
5. Keep main and source PR branches unchanged; do not promote this audit to complete until these steps finish.
