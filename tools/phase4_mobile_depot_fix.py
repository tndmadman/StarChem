from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected one match, found {count}')
    return text.replace(old, new, 1)


system_path = Path('src/main/java/com/tndmadman/rts/NpcMobileDepotSystem.java')
system = system_path.read_text(encoding='utf-8')
old_fallback = '''    private static List<Anchor> fallbackAnchors(World world, NpcFaction faction, int count) {
        Base centerBase = null;
        for (Base base : world.bases.values()) {
            if (faction.id().equals(base.playerId) && base.hp > 0) {
                centerBase = base;
                break;
            }
        }
        double cx = centerBase == null ? world.width * 0.5 : centerBase.x;
        double cy = centerBase == null ? world.height * 0.5 : centerBase.y;
        double radius = count == 1 ? 540.0 : Math.max(700.0, MIN_DEPOT_SPACING);
        List<Anchor> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = deterministicAngle(world, faction)
                    + i * Math.PI * 2.0 / count;
            out.add(new Anchor(cx + Math.cos(angle) * radius,
                    cy + Math.sin(angle) * radius));
        }
        clampAll(world, out);
        separate(world, faction, out);
        return List.copyOf(out);
    }
'''
new_fallback = '''    private static List<Anchor> fallbackAnchors(World world, NpcFaction faction, int count) {
        Base centerBase = null;
        for (Base base : world.bases.values()) {
            if (faction.id().equals(base.playerId) && base.hp > 0) {
                centerBase = base;
                break;
            }
        }
        double cx = centerBase == null ? world.width * 0.5 : centerBase.x;
        double cy = centerBase == null ? world.height * 0.5 : centerBase.y;
        double startAngle = deterministicAngle(world, faction);
        double firstRadius = count == 1 ? 540.0 : MIN_DEPOT_SPACING;
        List<Anchor> out = new ArrayList<>();
        out.add(clamp(world, new Anchor(cx + Math.cos(startAngle) * firstRadius,
                cy + Math.sin(startAngle) * firstRadius)));
        if (count == 1) return List.copyOf(out);

        List<Anchor> candidates = fallbackCandidates(world, cx, cy, startAngle, count);
        candidates.removeIf(candidate -> nearestDistance(candidate.x, candidate.y, out) < 1.0);
        while (out.size() < count && !candidates.isEmpty()) {
            Anchor best = null;
            double bestSpacing = -1.0;
            double bestCenterDistance = Double.MAX_VALUE;
            boolean bestMeetsSpacing = false;
            for (Anchor candidate : candidates) {
                double spacing = nearestDistance(candidate.x, candidate.y, out);
                double centerDistance = Calc.distance(cx, cy, candidate.x, candidate.y);
                boolean meetsSpacing = spacing + 0.001 >= MIN_DEPOT_SPACING;
                if (best == null
                        || meetsSpacing && !bestMeetsSpacing
                        || meetsSpacing == bestMeetsSpacing
                        && (meetsSpacing
                        ? centerDistance < bestCenterDistance - 0.001
                        : spacing > bestSpacing + 0.001)
                        || meetsSpacing == bestMeetsSpacing
                        && Math.abs(meetsSpacing ? centerDistance - bestCenterDistance
                        : spacing - bestSpacing) <= 0.001
                        && fallbackOrder(candidate, world) < fallbackOrder(best, world)) {
                    best = candidate;
                    bestSpacing = spacing;
                    bestCenterDistance = centerDistance;
                    bestMeetsSpacing = meetsSpacing;
                }
            }
            if (best == null) break;
            out.add(best);
            candidates.remove(best);
        }

        separate(world, faction, out);
        return List.copyOf(out);
    }

    private static List<Anchor> fallbackCandidates(World world, double cx, double cy,
                                                    double startAngle, int count) {
        List<Anchor> candidates = new ArrayList<>();
        int rings = Math.max(2, (int)Math.ceil(Math.sqrt(count)) + 1);
        int samples = Math.max(24, count * 12);
        for (int ring = 1; ring <= rings; ring++) {
            double radius = MIN_DEPOT_SPACING * ring;
            for (int i = 0; i < samples; i++) {
                double angle = startAngle + i * Math.PI * 2.0 / samples;
                addFallbackCandidate(candidates, clamp(world,
                        new Anchor(cx + Math.cos(angle) * radius,
                                cy + Math.sin(angle) * radius)));
            }
        }
        double maxX = world.width - MAP_MARGIN;
        double maxY = world.height - MAP_MARGIN;
        addFallbackCandidate(candidates, new Anchor(MAP_MARGIN, MAP_MARGIN));
        addFallbackCandidate(candidates, new Anchor(maxX, MAP_MARGIN));
        addFallbackCandidate(candidates, new Anchor(MAP_MARGIN, maxY));
        addFallbackCandidate(candidates, new Anchor(maxX, maxY));
        addFallbackCandidate(candidates, new Anchor(world.width * 0.5, MAP_MARGIN));
        addFallbackCandidate(candidates, new Anchor(world.width * 0.5, maxY));
        addFallbackCandidate(candidates, new Anchor(MAP_MARGIN, world.height * 0.5));
        addFallbackCandidate(candidates, new Anchor(maxX, world.height * 0.5));
        return candidates;
    }

    private static void addFallbackCandidate(List<Anchor> candidates, Anchor candidate) {
        for (Anchor existing : candidates) {
            if (Calc.distance(existing.x, existing.y, candidate.x, candidate.y) < 1.0) return;
        }
        candidates.add(candidate);
    }

    private static long fallbackOrder(Anchor anchor, World world) {
        long x = Math.round(anchor.x * 10.0);
        long y = Math.round(anchor.y * 10.0);
        return x * Math.max(1L, Math.round(world.height * 10.0) + 1L) + y;
    }
'''
system = replace_once(system, old_fallback, new_fallback, 'fallback anchors')
system_path.write_text(system, encoding='utf-8')

validator_path = Path('src/main/java/com/tndmadman/rts/NpcMobileDepotValidator.java')
validator = validator_path.read_text(encoding='utf-8')
old_edge = '''    private static void validateFallbackAnchorsNearMapEdge() {
        Fixture fixture = fixture("Mobile Depot Edge Spacing");
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        Base edgeOutpost = new Base(fixture.outpost.id, faction.id(), "outpost", 205, 205);
        world.bases.put(edgeOutpost.id, edgeOutpost);

        Unit first = unit(world, faction, 96_251, "freighter", 260, 260);
        Unit second = unit(world, faction, 96_252, "freighter", 300, 300);
        Unit third = unit(world, faction, 96_253, "freighter", 340, 340);
        NpcMobileDepotSystem.update(world, faction);

        for (Unit depot : new Unit[]{first, second, third}) {
            require(depot.targetX >= 190.0 - EPSILON
                            && depot.targetY >= 190.0 - EPSILON
                            && depot.targetX <= world.width - 190.0 + EPSILON
                            && depot.targetY <= world.height - 190.0 + EPSILON,
                    "edge fallback assigned an out-of-bounds depot anchor");
        }
        require(Calc.distance(first.targetX, first.targetY,
                        second.targetX, second.targetY) >= 700.0
                        && Calc.distance(first.targetX, first.targetY,
                        third.targetX, third.targetY) >= 700.0
                        && Calc.distance(second.targetX, second.targetY,
                        third.targetX, third.targetY) >= 700.0,
                "map-edge clamping collapsed fallback depot anchors together");
    }
'''
new_edge = '''    private static void validateFallbackAnchorsNearMapEdge() {
        for (long seed = 41_000; seed < 41_064; seed++) {
            validateFallbackAnchorsNearMapEdge(seed);
        }
    }

    private static void validateFallbackAnchorsNearMapEdge(long seed) {
        Fixture fixture = fixture("Mobile Depot Edge Spacing " + seed);
        World world = fixture.world;
        NpcFaction faction = fixture.faction;
        world.useSystemSeed(seed);
        world.units.clear();
        world.bases.clear();
        world.resources.clear();
        Base edgeOutpost = new Base(fixture.outpost.id, faction.id(), "outpost", 205, 205);
        world.bases.put(edgeOutpost.id, edgeOutpost);

        Unit first = unit(world, faction, 96_251, "freighter", 260, 260);
        Unit second = unit(world, faction, 96_252, "freighter", 300, 300);
        Unit third = unit(world, faction, 96_253, "freighter", 340, 340);
        NpcMobileDepotSystem.update(world, faction);

        for (Unit depot : new Unit[]{first, second, third}) {
            require(depot.targetX >= 190.0 - EPSILON
                            && depot.targetY >= 190.0 - EPSILON
                            && depot.targetX <= world.width - 190.0 + EPSILON
                            && depot.targetY <= world.height - 190.0 + EPSILON,
                    "edge fallback assigned an out-of-bounds depot anchor for seed " + seed);
        }
        require(Calc.distance(first.targetX, first.targetY,
                        second.targetX, second.targetY) >= 700.0
                        && Calc.distance(first.targetX, first.targetY,
                        third.targetX, third.targetY) >= 700.0
                        && Calc.distance(second.targetX, second.targetY,
                        third.targetX, third.targetY) >= 700.0,
                "map-edge clamping collapsed fallback depot anchors together for seed " + seed
                        + " | first=(" + first.targetX + ',' + first.targetY + ')'
                        + " | second=(" + second.targetX + ',' + second.targetY + ')'
                        + " | third=(" + third.targetX + ',' + third.targetY + ')');
    }
'''
validator = replace_once(validator, old_edge, new_edge, 'edge regression')
validator_path.write_text(validator, encoding='utf-8')

print('Phase 4 mobile-depot geometry repair applied.')
