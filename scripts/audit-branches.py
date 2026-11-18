#!/usr/bin/env python3
"""Collect reproducible structural evidence for the StarChem remote-branch audit.

This tool is deliberately conservative: ancestry and identical tree objects are
provable; diverged histories require a separate patch/semantic review. It never
merges, checks out, rewrites, or deletes a branch.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path


def git(*args: str, allow_failure: bool = False) -> str:
    result = subprocess.run(["git", *args], text=True, capture_output=True, check=False)
    if result.returncode and not allow_failure:
        raise RuntimeError(f"git {' '.join(args)} failed ({result.returncode}): {result.stderr.strip()}")
    return result.stdout.strip()


def is_ancestor(ancestor: str, descendant: str) -> bool:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor, descendant],
        text=True, capture_output=True, check=False,
    )
    if result.returncode not in (0, 1):
        raise RuntimeError(f"cannot check ancestry: {result.stderr.strip()}")
    return result.returncode == 0


def count_pair(left: str, right: str) -> tuple[int, int]:
    behind, ahead = git("rev-list", "--left-right", "--count", f"{left}...{right}").split()
    return int(behind), int(ahead)


def remote_branches(remote: str) -> list[tuple[str, str]]:
    lines = git("for-each-ref", "--format=%(refname:short) %(objectname)", f"refs/remotes/{remote}").splitlines()
    return [(name, sha) for line in lines if " " in line
            for name, sha in [line.split(" ", 1)]
            if name != f"{remote}/HEAD"]


def inspect_branch(ref: str, sha: str, main_sha: str, candidate_sha: str,
                   candidate_tree: str, main_ref: str, candidate_ref: str) -> dict:
    main_behind, main_ahead = count_pair(main_sha, sha)
    cand_behind, cand_ahead = count_pair(candidate_sha, sha)
    branch_tree = git("rev-parse", f"{sha}^{{tree}}")
    merge_base = git("merge-base", candidate_sha, sha)
    if ref == main_ref:
        classification, justification = "BASELINE", "main reference"
    elif ref == candidate_ref or is_ancestor(sha, candidate_sha):
        classification, justification = "INTEGRATED", "branch head is a candidate ancestor"
    elif branch_tree == candidate_tree:
        classification, justification = "EQUIVALENT", "exact Git tree SHA matches candidate"
    else:
        classification = "BLOCKED"
        justification = "structural comparison only; unique behavior requires manual review"

    # --cherry-pick compares patch IDs, not full behavior. It deliberately omits
    # merge commits; use these counts as triage evidence, not a final verdict.
    unique_commits = git("log", "--cherry-pick", "--right-only", "--no-merges",
                         "--format=%H", f"{candidate_sha}...{sha}")
    branch_files = git("diff", "--name-only", merge_base, sha)
    differing_files = git("diff", "--name-only", sha, candidate_sha)
    files_changed = branch_files.splitlines() if branch_files else []
    differing = differing_files.splitlines() if differing_files else []
    return {
        "branch": ref, "head_sha": sha, "tree_sha": branch_tree,
        "main_behind": main_behind, "main_ahead": main_ahead,
        "candidate_behind": cand_behind, "candidate_ahead": cand_ahead,
        "merge_base_sha": merge_base,
        "patch_unique_nonmerge_commits": len(unique_commits.splitlines()) if unique_commits else 0,
        "branch_changed_file_count": len(files_changed),
        "candidate_differing_file_count": len(differing),
        "branch_changed_files": files_changed,
        "candidate_differing_files": differing,
        "classification": classification, "justification": justification,
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--remote", default="origin")
    p.add_argument("--main", default="origin/main")
    p.add_argument("--candidate", default="origin/integration/starchem-all-ready-20261117")
    p.add_argument("--out-dir", default="build/branch-audit")
    p.add_argument("--no-fetch", action="store_true", help="Use existing remote refs, without fetching")
    args = p.parse_args()

    git("rev-parse", "--show-toplevel")
    if not args.no_fetch:
        subprocess.run(["git", "fetch", args.remote, "--prune"], check=True)
    main_sha = git("rev-parse", "--verify", args.main)
    candidate_sha = git("rev-parse", "--verify", args.candidate)
    candidate_tree = git("rev-parse", f"{candidate_sha}^{{tree}}")
    branches = remote_branches(args.remote)
    if not branches:
        raise RuntimeError(f"no remote branch refs found under {args.remote}")

    rows = []
    for i, (ref, sha) in enumerate(branches, 1):
        row = inspect_branch(ref, sha, main_sha, candidate_sha, candidate_tree,
                             args.main, args.candidate)
        rows.append(row)
        print(f"[{i}/{len(branches)}] {ref}: {row['classification']}", file=sys.stderr)

    directory = Path(args.out_dir)
    directory.mkdir(parents=True, exist_ok=True)
    payload = {"main_sha": main_sha, "candidate_sha": candidate_sha,
               "branch_count": len(rows), "totals": dict(Counter(r["classification"] for r in rows)),
               "caveat": "BLOCKED refs require semantic inspection; patch-count zero is not equivalence.",
               "branches": rows}
    (directory / "remote-branch-evidence.json").write_text(
        json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    columns = ["branch", "head_sha", "tree_sha", "main_behind", "main_ahead",
               "candidate_behind", "candidate_ahead", "merge_base_sha",
               "patch_unique_nonmerge_commits", "branch_changed_file_count",
               "candidate_differing_file_count", "classification", "justification"]
    with (directory / "remote-branch-evidence.csv").open("w", encoding="utf-8", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=columns, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({k: payload[k] for k in ("main_sha", "candidate_sha", "branch_count", "totals")}, indent=2))
    print("Semantic review and functional testing are still required. No branches were changed.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(1)
