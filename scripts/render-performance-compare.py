#!/usr/bin/env python3
"""Compare repeated, paired render benchmarks while keeping the p95 limit unchanged."""

import argparse
import csv
import math
from pathlib import Path
from statistics import median
import sys


def parse_report(filename: Path) -> dict[tuple[str, str], float]:
    with filename.open(newline="", encoding="utf-8") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None or not {"scene", "zoom", "p95_ms"}.issubset(reader.fieldnames):
            raise ValueError(f"{filename}: missing required scene/zoom/p95_ms columns")
        values: dict[tuple[str, str], float] = {}
        for row in reader:
            key = (row["scene"], row["zoom"])
            if not all(key) or key in values:
                raise ValueError(f"{filename}: blank or duplicated benchmark key {key}")
            value = float(row["p95_ms"])
            if not math.isfinite(value) or value <= 0:
                raise ValueError(f"{filename}: invalid p95 for {key}: {value}")
            values[key] = value
    if not values:
        raise ValueError(f"{filename}: empty benchmark report")
    return values


def evaluate(base_files: list[Path], head_files: list[Path], limit_percent: float) -> int:
    if len(base_files) != len(head_files) or len(base_files) < 3:
        raise ValueError("At least three paired baseline and candidate runs are required")
    if not math.isfinite(limit_percent) or not 0 <= limit_percent <= 100:
        raise ValueError("Invalid max regression limit")

    base_runs = [parse_report(path) for path in base_files]
    head_runs = [parse_report(path) for path in head_files]
    keys = set(base_runs[0])
    for name, run in [("baseline", r) for r in base_runs] + [("candidate", r) for r in head_runs]:
        if set(run) != keys:
            raise ValueError(f"{name} scenarios differ from baseline "
                             f"(missing: {sorted(keys - set(run))}, extra: {sorted(set(run) - keys)})")

    failed = []
    print(f"Comparing {len(base_runs)} baseline and {len(head_runs)} candidate runs across "
          f"{len(keys)} scenarios; maximum regression {limit_percent:.1f}%")
    print("Scene | Zoom | Base median p95 ms (min..max) | Candidate median p95 ms (min..max) | Delta")
    for key in sorted(keys):
        base = [run[key] for run in base_runs]
        head = [run[key] for run in head_runs]
        previous, current = median(base), median(head)
        delta = (current / previous - 1) * 100
        status = "FAIL" if delta > limit_percent else "PASS"
        print(f"{key[0]} | {key[1]} | {previous:.3f} ({min(base):.3f}..{max(base):.3f}) | "
              f"{current:.3f} ({min(head):.3f}..{max(head):.3f}) | {delta:+.1f}% {status}")
        if delta > limit_percent:
            failed.append((key, delta))
    if failed:
        print(f"{len(failed)} render scenarios exceed the {limit_percent:.1f}% regression limit "
              "after repeated paired measurements", file=sys.stderr)
        return 1
    print(f"All {len(keys)} render scenarios meet the unchanged "
          f"{limit_percent:.1f}% limit after paired measurements")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, nargs="+", required=True)
    parser.add_argument("--head", type=Path, nargs="+", required=True)
    parser.add_argument("--max-regression", type=float, default=20.0)
    args = parser.parse_args()
    try:
        return evaluate(args.base, args.head, args.max_regression)
    except (OSError, ValueError) as exc:
        print(f"Render regression comparison failed: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
