# StarChem — merge-readiness report

Date: 2026-11-17.

## Decision: **NOT READY — BLOCKERS REMAIN**

This work established a conservative consolidation branch and documented the remote branch snapshot. No unverified divergent code was imported, and **no automated or manual tests were executed locally**. Do not merge to main, publish, tag, or enable automatic merging.

| Field | Value |
|---|---|
| Original/main baseline SHA | `0c16204cf835bacb25449ee58af016b2e786f3a5` |
| Starting integration SHA | `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f` |
| Destination | `integration/starchem-all-ready-20261117` |
| Branch references in current snapshot | 296 including newly created candidate and main |
| PR heads with confirmed ancestry | #453, #454, #455, #456, #457, #458, #459, #460, #461 |
| Candidate files added this task | Three integration documentation files only |
| New application features integrated | None; no missing feature has been proven safe to add |
| Code defects repaired | None; no undocumented code changes |
| Git conflict resolutions | None required for documentation-only commit |
| Previously verified integration changes | 171 commits ahead of main, 147 files changed (pre-documentation) |
| Previously verified CI | 26/26 PR workflows successful on `371b5a51fb4be8df3bdb47c4245c660c1c66fb0f` |
| Actual final-head tests | NOT RUN at document creation |
| Manual Windows/multiplayer/gameplay acceptance | NOT RUN |
| Final PR | [#462](https://github.com/tndmadman/StarChem/pull/462) — draft only |

## Compatibility

The integration contains multiplayer protocol **18** (verified from `MultiplayerCompatibility.java`); published v1.8.2 is documented as protocol **17**. Matching client/server protocol 18 builds are required. Previous integration PR #461 reports rules version 27 and save format 6 unchanged; verify these settings and migration/rollback on the final code before approval. Never lower the protocol version only to pass checks.

## Blockers and repair path

1. **Branch semantic audit incomplete.** Of 296 refs, only nine earlier PR heads have confirmed ancestor status. Review the 285 blocked historical refs with git graph and patch-equivalence. The five explicitly divergent priority branches need focused inspection.
2. **No proven missing code integrated.** Verify unique functionality against current code; integrate compatible missing work, add tests, and document each changed path and conflict.
3. **No exact-final-head comprehensive checks.** Execute Java/Gradle, regressions, render checks and 15-minute TCP soak; review the CI matrix on the *final* SHA.
4. **Real Windows GUI acceptance absent.** Run the required sizes, DPI scaling, launchers, dialogs, overlays and save/reload checks.
5. **Two independent clients and server acceptance absent.** Validate authentication, JOIN/RESUME, reconnect/restart, owner intel and mismatched protocol rejection.
6. **Real Guided Start Hauler acceptance absent.** Complete the entire mined-resource, production, lab, research and shipyard path without cheats.
7. **Corrupt-load rollback and campaign isolation not verified.** Test ten load failures, including post-construction failure and A-B-A world switching.

## Release policy

Keep this PR in draft. Do not merge to `main` until the owner explicitly approves and all gates pass. Existing source branches and PRs are unchanged.

## Additional implementation / review checkpoint (2026-11-17)

Work added directly to the existing integration branch:

- fcc1eef74f6225cc0457a1c5c6182b9c2fb25a22:
  scripts/audit-branches.py — read-only, full-history remote-branch
  structural audit that emits JSON/CSV without automatic semantic approval.
- 2ccf75913f6bea9836783343f81f35787f486ee3:
  add five source-tree inspection results to the existing branch audit report.
- cdda4db33792beebf3e0662258abdd94747b2b86:
  add Branch Structural Audit CI workflow, scoped to this consolidation PR.

The five priority branch trees were compared with candidate
cc26b3a0f4db26837873a6ffb2fc2d1b04fe1373.
Matching file blob counts by branch were 108, 97, 489, 428 and 495,
respectively, in the audit report's order. None is fully classified,
and no unverified feature has been bulk-merged. No application source
changes or runtime defect repairs have been claimed.

Source constants verified: protocol 18, rules 27, save format 6.
Compatibility behavior is **not** proven by those constants.

An earlier Graphics Integration Validation run failed two sustained-p50
scenes by 23.2% and 21.3% relative to its frozen baseline:
https://github.com/tndmadman/StarChem/actions/runs/44214893774.
Do not relax the 20% limit or count a prior-head pass as a final-head pass.

**Decision remains NOT READY — BLOCKERS REMAIN.**
The comprehensive branch semantic audit, automated final-head CI,
real Windows/multiplayer acceptance, 900-second TCP soak, rollback tests,
and genuine first-Hauler campaign run remain necessary.
