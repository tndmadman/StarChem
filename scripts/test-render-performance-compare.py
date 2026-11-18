#!/usr/bin/env python3
"""Check that the paired render comparison rejects incomplete or invalid evidence."""

import contextlib
import csv
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest


MODULE_PATH = Path(__file__).with_name("render-performance-compare.py")
SPEC = importlib.util.spec_from_file_location("render_performance_compare", MODULE_PATH)
compare = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compare)


class RenderComparisonContractTest(unittest.TestCase):
    def write_report(self, root, name, missing=None, multiplier=1.0):
        path = root / name
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.writer(handle)
            writer.writerow(["scene", "zoom", "p95_ms"])
            for scene, zoom in sorted(compare.EXPECTED_KEYS):
                if (scene, zoom) != missing:
                    writer.writerow([scene, zoom, str(10.0 * multiplier)])
        return path

    def test_all_33_scenarios_pass_with_three_pairs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = [self.write_report(root, f"base-{i}.csv") for i in range(3)]
            head = [self.write_report(root, f"head-{i}.csv", multiplier=1.1) for i in range(3)]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(compare.evaluate(base, head, 20), 0)

    def test_a_missing_scenario_in_every_run_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = ("background-default", "0.45")
            base = [self.write_report(root, f"base-{i}.csv", missing=missing) for i in range(3)]
            head = [self.write_report(root, f"head-{i}.csv", missing=missing) for i in range(3)]
            with self.assertRaisesRegex(ValueError, "complete 33-scenario"):
                compare.evaluate(base, head, 20)

    def test_existing_twenty_percent_threshold_is_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = [self.write_report(root, f"base-{i}.csv") for i in range(3)]
            head = [self.write_report(root, f"head-{i}.csv", multiplier=1.21) for i in range(3)]
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(compare.evaluate(base, head, 20), 1)

    def test_fewer_than_three_pairs_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = [self.write_report(root, f"base-{i}.csv") for i in range(2)]
            head = [self.write_report(root, f"head-{i}.csv") for i in range(2)]
            with self.assertRaisesRegex(ValueError, "At least three"):
                compare.evaluate(base, head, 20)


if __name__ == "__main__":
    unittest.main()
