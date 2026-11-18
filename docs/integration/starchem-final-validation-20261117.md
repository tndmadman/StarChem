# StarChem — final validation matrix (working document)

Date: 2026-11-17. **Merge validation NOT COMPLETE.**

Original integration SHA: `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f`. Final candidate branch: `integration/starchem-all-ready-20261117` (the SHA will change when these documents are committed).

## Evidence from previous head only

GitHub Actions lookup for `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f` returned 26 pull-request-triggered workflow runs, 26 completed successfully. These are previous-head evidence and MUST NOT be recycled as checks on the new candidate documentation commit.

| Workflow | Run | Result on previous head |
|---|---|---|
| Research validation | [42397666546](https://github.com/tndmadman/StarChem/actions/runs/42397666546) | completed/success |
| Visual Content Validation | [42397666622](https://github.com/tndmadman/StarChem/actions/runs/42397666622) | completed/success |
| Station Visual Validation | [42397666513](https://github.com/tndmadman/StarChem/actions/runs/42397666513) | completed/success |
| Scenario Validation | [42397666545](https://github.com/tndmadman/StarChem/actions/runs/42397666545) | completed/success |
| Game Swing UI Validation | [42397666575](https://github.com/tndmadman/StarChem/actions/runs/42397666575) | completed/success |
| Ship Visual Validation | [42397666582](https://github.com/tndmadman/StarChem/actions/runs/42397666582) | completed/success |
| AI Validation | [42397666499](https://github.com/tndmadman/StarChem/actions/runs/42397666499) | completed/success |
| Galaxy Event Validation | [42397666632](https://github.com/tndmadman/StarChem/actions/runs/42397666632) | completed/success |
| AI Stress | [42397666492](https://github.com/tndmadman/StarChem/actions/runs/42397666492) | completed/success |
| TLS identity and private storage validation | [42397666520](https://github.com/tndmadman/StarChem/actions/runs/42397666520) | completed/success |
| Security Integration Validation | [42397666617](https://github.com/tndmadman/StarChem/actions/runs/42397666617) | completed/success |
| Observer Session Validation | [42397666556](https://github.com/tndmadman/StarChem/actions/runs/42397666556) | completed/success |
| Diplomacy Validation | [42397666614](https://github.com/tndmadman/StarChem/actions/runs/42397666614) | completed/success |
| Ambient Graphics Validation | [42397666568](https://github.com/tndmadman/StarChem/actions/runs/42397666568) | completed/success |
| Windows Observation Store | [42397666602](https://github.com/tndmadman/StarChem/actions/runs/42397666602) | completed/success |
| StarChem v1.8 Release Candidate | [42397666510](https://github.com/tndmadman/StarChem/actions/runs/42397666510) | completed/success |
| Feature Rollup Validation | [42397666525](https://github.com/tndmadman/StarChem/actions/runs/42397666525) | completed/success |
| Solo Save Load Validation | [42397666534](https://github.com/tndmadman/StarChem/actions/runs/42397666534) | completed/success |
| Celestial Gameplay Validation | [42397666569](https://github.com/tndmadman/StarChem/actions/runs/42397666569) | completed/success |
| Graphics Integration Validation | [42397666629](https://github.com/tndmadman/StarChem/actions/runs/42397666629) | completed/success |
| Release StarChem | [42397666616](https://github.com/tndmadman/StarChem/actions/runs/42397666616) | completed/success |
| Orbital Resource Belt Radar Validation | [42397666600](https://github.com/tndmadman/StarChem/actions/runs/42397666600) | completed/success |
| CI | [42397666613](https://github.com/tndmadman/StarChem/actions/runs/42397666613) | completed/success |
| Multiplayer JOIN lifecycle verification | [42397666612](https://github.com/tndmadman/StarChem/actions/runs/42397666612) | completed/success |
| Java CI | [42397666615](https://github.com/tndmadman/StarChem/actions/runs/42397666615) | completed/success |
| Render Performance | [42397666580](https://github.com/tndmadman/StarChem/actions/runs/42397666580) | completed/success |

## Required verification on final candidate

| Gate | Exact requirement | Status | Evidence / blocker |
|---|---|---|---|
| Java canonical | Java 17; Gradle 8.10.2; `gradle clean check jar --no-daemon` | NOT RUN | No executable Git checkout/build agent in the connected repository tools |
| Focused Gradle validators | `validateTcpJoinLifecycle validateRadarBeltTracking validateSoloSaveLoad validateStartingFleet validateWorldLifecycle validateResourceRingPlacement` | NOT RUN | No command execution against repository |
| Canonical regressions | `validation/run-release-regressions.sh` | NOT RUN | No command execution against repository |
| Release candidate | `validation/run-v180-rc-acceptance.sh` | NOT RUN | No command execution against repository |
| TCP soak | `gradle tcpSoak -PsoakSeconds=900` | NOT RUN | No command execution against repository |
| Render benchmark | 33 scene/zoom conditions, five base/head pairs, 20% p95 ceiling | NOT RUN | Previous-head CI success cannot stand in for new candidate |
| Windows UI | 900×720, resize, fullscreen, DPI, campaign UI, overlays and packaged launchers | NOT RUN | No Windows game client or physical interaction access |
| Two-client multiplayer | Dedicated server, two clients, reconnect/restart, identity, fog, radar, protocol-17 rejection | NOT RUN | No physical/independent clients |
| Guided Start to Hauler | Real mining, production, lab, research, shipyard, save and resume | NOT RUN | No game-running acceptance environment |
| Corrupt save rollback | At least ten failures, including after world construction | NOT RUN | No game-running acceptance environment |
| Final head CI | Full workflows after candidate commit/PR | PENDING | Must inspect exact final SHA after PR exists |

All missing acceptance evidence is merge-blocking. Do not report PASS for any test not executed on the candidate SHA. CI checks on the previous branch are historical evidence, not final sign-off.

## Follow-up evidence checkpoint (2026-11-17, after audit tooling commits)

This section adds evidence without changing any earlier NOT RUN into PASS.

- Source review on candidate cdda4db33792beebf3e0662258abdd94747b2b86:
  MultiplayerCompatibility.PROTOCOL_VERSION = 18;
  config/starchem.json rulesVersion = 27;
  ServerSaveStore.SAVE_FORMAT_VERSION = 6.
  These are static code/configuration findings, not migration or protocol acceptance.
- scripts/audit-branches.py passed Python compilation and a synthetic three-branch
  Git smoke test. This is a tooling test, **not** a StarChem repository audit.
- The new Branch Structural Audit workflow was added to PR #462. It requires a
  completed run and review of generated CSV/JSON evidence before structural
  classification can be counted as checked.
- Graphics Integration Validation on the earlier candidate
  cc26b3a0f4db26837873a6ffb2fc2d1b04fe1373 FAILED:
  default background at zoom 0.45 had p50 9.988 -> 12.302 ms (+23.2%);
  multi-large-station at zoom 1.00 had p50 14.801 -> 17.951 ms (+21.3%).
  The workflow's sustained-p50 gate allows at most 20% regression.
  [Failure run](https://github.com/tndmadman/StarChem/actions/runs/44214893774).
  Do not assume it is reproduced on the final head without reviewing that run.
- The separate Render Performance workflow performs five paired runs with
  33 scene/zoom combinations and a 20% p95 regression contract; it is separate
  from the graphics integration workflow's p50 check.
- Final-head Java, graphics, render, and structural-audit workflows remain
  **PENDING / NOT VERIFIED** until the exact last commit finishes its checks.
- Actual Windows GUI, 900-second TCP soak, two-client dedicated-server acceptance,
  ten corrupted-load tests, and actual first-Hauler progression remain NOT RUN.

Do not merge from a static source review or partial workflow results.
