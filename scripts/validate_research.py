#!/usr/bin/env python3
"""Strict cross-config validation for the branching research system (issue #382)."""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config"


def die(message: str) -> None:
    raise ValueError(message)


def load(path: Path):
    def unique_object(pairs):
        out = {}
        for key, value in pairs:
            if key in out:
                die(f"{path}: duplicate JSON key {key!r}")
            out[key] = value
        return out

    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        die(f"Could not load {path.relative_to(ROOT)}: {exc}")


def authored_ids(path: Path, section: str) -> set[str]:
    doc = load(path)
    value = doc.get(section, {})
    if not isinstance(value, dict):
        die(f"{path.relative_to(ROOT)}: {section} must be an object")
    return set(value)


def main() -> int:
    manifest = load(CONFIG / "starchem.json")
    files = manifest.get("files", {})
    if not isinstance(files, dict):
        die("config/starchem.json: files must be an object")

    research_path = ROOT / files.get("research", "config/research.json")
    research_doc = load(research_path)
    topics = research_doc.get("researchTopics", {})
    if not isinstance(topics, dict):
        die("researchTopics must be an object")
    if len(topics) < 20:
        die(f"researchTopics must contain at least 20 topics; found {len(topics)}")

    station_path = ROOT / files.get("stations", "config/stations.json")
    stations = authored_ids(station_path, "stationTypes")
    modules = authored_ids(ROOT / files.get("modules", "config/modules.json"), "shipModules")
    weapons = authored_ids(ROOT / files.get("weapons", "config/weapons.json"), "weaponTypes")
    materials = authored_ids(ROOT / files.get("materials", "config/materials.json"), "materials")

    ships: set[str] = set()
    for rel in files.get("ships", []):
        ships |= authored_ids(ROOT / rel, "shipTypes")

    craftables: set[str] = set()
    for rel in files.get("craftables", []):
        craftables |= authored_ids(ROOT / rel, "craftableItems")

    target_sets = {
        "ships": ships,
        "stations": stations,
        "stationPackages": stations,
        "modules": modules,
        "weapons": weapons,
        "craftables": craftables,
    }

    legacy_ids = {"advanced_industry", "combat_doctrine", "battlefleet_engineering", "supercapital_architecture"}
    missing_legacy = legacy_ids - set(topics)
    if missing_legacy:
        die("legacy research IDs were removed: " + ", ".join(sorted(missing_legacy)))

    branches: set[str] = set()
    doctrine_groups: dict[str, list[str]] = defaultdict(list)
    edges: dict[str, list[str]] = {}

    for topic_id, raw in topics.items():
        if not isinstance(raw, dict):
            die(f"research topic {topic_id}: definition must be an object")
        branch = str(raw.get("branch", "")).strip()
        if not branch:
            die(f"research topic {topic_id}: branch is required")
        branches.add(branch)

        station_types = raw.get("stationTypes")
        if not isinstance(station_types, list) or not station_types:
            die(f"research topic {topic_id}: stationTypes must be a non-empty array")
        for station_id in station_types:
            if station_id not in stations:
                die(f"research topic {topic_id}: unknown stationTypes ID {station_id}")

        requirements = raw.get("requires", [])
        if not isinstance(requirements, list):
            die(f"research topic {topic_id}: requires must be an array")
        edges[topic_id] = list(requirements)
        for required in requirements:
            if required == topic_id:
                die(f"research topic {topic_id}: topic cannot require itself")
            if required not in topics:
                die(f"research topic {topic_id}: unknown prerequisite {required}")

        seconds = raw.get("timeSeconds", 0)
        if not isinstance(seconds, (int, float)) or seconds <= 0:
            die(f"research topic {topic_id}: timeSeconds must be positive")

        costs = raw.get("requiredResources", {})
        if not isinstance(costs, dict):
            die(f"research topic {topic_id}: requiredResources must be an object")
        for material_id, amount in costs.items():
            if material_id not in materials:
                die(f"research topic {topic_id}: unknown material {material_id}")
            if not isinstance(amount, (int, float)) or amount <= 0:
                die(f"research topic {topic_id}: invalid amount for {material_id}")

        unlocks = raw.get("unlocks", {})
        if not isinstance(unlocks, dict) or not unlocks:
            die(f"research topic {topic_id}: must unlock at least one authoritative target")
        for category, targets in unlocks.items():
            known = target_sets.get(category)
            if known is None:
                die(f"research topic {topic_id}: unsupported/non-authoritative unlock category {category}")
            if not isinstance(targets, list) or not targets:
                die(f"research topic {topic_id}: unlock category {category} must be a non-empty array")
            for target in targets:
                if not isinstance(target, str) or target not in known:
                    die(f"research topic {topic_id}: unknown {category} unlock ID {target}")

        doctrine = str(raw.get("doctrineGroup", "")).strip()
        if doctrine:
            doctrine_groups[doctrine].append(topic_id)

    if len(branches) < 6:
        die(f"research tree must span at least 6 branches; found {len(branches)}")
    if not 2 <= len(doctrine_groups) <= 4:
        die(f"expected 2-4 doctrine groups; found {len(doctrine_groups)}")
    for group, choices in doctrine_groups.items():
        if len(choices) != 2:
            die(f"doctrine group {group} must contain exactly two choices; found {choices}")

    state: dict[str, int] = {}
    stack: list[str] = []

    def visit(topic_id: str) -> None:
        marker = state.get(topic_id, 0)
        if marker == 2:
            return
        if marker == 1:
            cycle = " -> ".join(stack + [topic_id])
            die(f"research dependency cycle: {cycle}")
        state[topic_id] = 1
        stack.append(topic_id)
        for required in edges[topic_id]:
            visit(required)
        stack.pop()
        state[topic_id] = 2

    for topic_id in topics:
        visit(topic_id)

    npc_doc = load(ROOT / files.get("npcs", "config/npcs.json"))
    faction_paths = 0
    for faction in npc_doc.get("factions", []):
        ids = faction.get("researchTopicIds", [])
        if not ids:
            continue
        faction_paths += 1
        seen: set[str] = set()
        selected_doctrines: dict[str, str] = {}
        for topic_id in ids:
            if topic_id not in topics:
                die(f"NPC {faction.get('id')}: unknown research topic {topic_id}")
            missing = [required for required in edges[topic_id] if required not in seen]
            if missing:
                die(f"NPC {faction.get('id')}: {topic_id} appears before prerequisites {missing}")
            doctrine = str(topics[topic_id].get("doctrineGroup", "")).strip()
            if doctrine:
                previous = selected_doctrines.get(doctrine)
                if previous:
                    die(f"NPC {faction.get('id')}: conflicting doctrine path {previous} / {topic_id}")
                selected_doctrines[doctrine] = topic_id
            seen.add(topic_id)
    if faction_paths < 1:
        die("at least one FACTION NPC must have a configured research path")

    # Guard the authoritative integration points and stable save/network representation against regression.
    build_source = (ROOT / "src/main/java/com/tndmadman/rts/BuildSystem.java").read_text(encoding="utf-8")
    for token in (
        "ResearchUnlockKind.SHIP", "ResearchUnlockKind.STATION_PACKAGE", "ResearchUnlockKind.STATION",
        "ResearchUnlockKind.MODULE", "ResearchUnlockKind.WEAPON", "ResearchUnlockKind.CRAFTABLE",
    ):
        if token not in build_source:
            die(f"BuildSystem is missing authoritative research gate {token}")
    research_system = (ROOT / "src/main/java/com/tndmadman/rts/ResearchSystem.java").read_text(encoding="utf-8")
    if "ResearchPolicy.blockedResearchReason" not in research_system:
        die("ResearchSystem no longer enforces central research authorization")
    world_source = (ROOT / "src/main/java/com/tndmadman/rts/World.java").read_text(encoding="utf-8")
    if world_source.count("completedResearch") < 3:
        die("World no longer appears to persist stable completed research IDs")
    summary_source = (ROOT / "src/main/java/com/tndmadman/rts/StrategicSummary.java").read_text(encoding="utf-8")
    if "StrategicResearchRow" not in summary_source or "ResearchRules.all()" not in summary_source:
        die("StrategicSummary no longer exposes research state to Empire Overview/multiplayer summaries")

    print(
        f"Research validation passed: {len(topics)} topics, {len(branches)} branches, "
        f"{len(doctrine_groups)} doctrine groups, {sum(len(v) for v in doctrine_groups.values())} doctrine choices."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValueError as exc:
        print(f"RESEARCH VALIDATION FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1)
