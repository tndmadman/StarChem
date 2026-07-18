#!/usr/bin/env bash
set -euo pipefail
python3 tools/phase4_cleanup.py
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
git diff --cached --check
git commit -m "Remove temporary Phase 4 tooling"
git push origin HEAD:agent/fix-corsair-remote-view
