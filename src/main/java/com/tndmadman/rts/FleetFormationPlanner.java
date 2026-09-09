package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure formation planning for fleet commands. Geometry is expressed in local
 * forward/right coordinates and only converted to world coordinates at the edge.
 */
final class FleetFormationPlanner {
    private static final double BASE_SPACING = 54.0;
    private static final double MAX_PACE = 1200.0;

    enum Role { FRONTLINE, SCREEN, LINE, SUPPORT }

    record Target(double x, double y) { }

    record Member(String key, double x, double y, double speed, double durability,
                  boolean armed, boolean supportPreferred) {
        Member {
            key = key == null ? "" : key;
            x = Double.isFinite(x) ? x : 0;
            y = Double.isFinite(y) ? y : 0;
            speed = Double.isFinite(speed) && speed > 0 ? speed : 0;
            durability = Double.isFinite(durability) && durability > 0 ? durability : 0;
        }
    }

    record Plan(Map<String, Target> targets, double pace, double forwardX, double forwardY, String anchorKey) {
        Plan {
            targets = Map.copyOf(targets == null ? Map.of() : targets);
            pace = Double.isFinite(pace) && pace > 0 ? Math.min(MAX_PACE, pace) : 0;
            if (!Double.isFinite(forwardX) || !Double.isFinite(forwardY)
                    || Math.hypot(forwardX, forwardY) < 1.0e-9) {
                forwardX = 0;
                forwardY = -1;
            }
            anchorKey = anchorKey == null ? "" : anchorKey;
        }

        Target target(String key) { return targets.get(key); }
    }

    private record Slot(int index, double lateral, double longitudinal) { }
    private record Assignment(Member member, Role role, Slot slot) { }

    private FleetFormationPlanner() { }

    static double cohesionTolerance() {
        return BASE_SPACING * 2.0;
    }

    static Plan plan(World world, List<String> unitKeys, FleetFormation formation,
                     double anchorX, double anchorY) {
        return plan(world, unitKeys, formation, anchorX, anchorY, 0, 0);
    }

    static Plan plan(World world, List<String> unitKeys, FleetFormation formation,
                     double anchorX, double anchorY, double forwardX, double forwardY) {
        if (world == null || unitKeys == null || unitKeys.isEmpty()) return empty();
        List<Member> members = members(world, unitKeys);
        Plan base = plan(members, formation, anchorX, anchorY, forwardX, forwardY);
        return clamp(world, base);
    }

    static Plan escortPlan(World world, List<String> unitKeys, Unit protectedUnit) {
        if (world == null || protectedUnit == null || unitKeys == null || unitKeys.isEmpty()) return empty();
        List<Member> members = members(world, unitKeys);
        if (members.isEmpty()) return empty();
        double fx = Math.cos(protectedUnit.heading);
        double fy = Math.sin(protectedUnit.heading);
        if (!Double.isFinite(fx) || !Double.isFinite(fy) || Math.hypot(fx, fy) < 0.5) {
            fx = 0;
            fy = -1;
        }
        double radius = Math.max(118.0, 92.0 + protectedUnit.type().size.scale * 34.0);
        List<Slot> slots = escortSlots(members.size(), radius);
        Plan base = buildPlan(members, slots, protectedUnit.x, protectedUnit.y, fx, fy, null, false);
        return clamp(world, base);
    }

    static Plan plan(List<Member> input, FleetFormation formation,
                     double anchorX, double anchorY, double requestedForwardX, double requestedForwardY) {
        List<Member> members = normalizedMembers(input);
        if (members.isEmpty()) return empty();

        double centroidX = members.stream().mapToDouble(Member::x).average().orElse(0);
        double centroidY = members.stream().mapToDouble(Member::y).average().orElse(0);
        if (!Double.isFinite(anchorX)) anchorX = centroidX;
        if (!Double.isFinite(anchorY)) anchorY = centroidY;

        double[] forward = normalizedForward(requestedForwardX, requestedForwardY,
                anchorX - centroidX, anchorY - centroidY);
        FleetFormation resolved = formation == null ? FleetFormation.GRID : formation;
        List<Slot> slots = slots(resolved, members.size(), BASE_SPACING);
        return buildPlan(members, slots, anchorX, anchorY, forward[0], forward[1], resolved, true);
    }

    private static List<Member> members(World world, List<String> unitKeys) {
        List<Member> members = new ArrayList<>();
        for (String key : unitKeys) {
            Unit unit = world.units.get(key);
            if (unit == null || unit.hp <= 0) continue;
            ShipType type = unit.type();
            boolean armed = type.weaponHardpoints > 0;
            boolean support = !armed || type.baseBuilder || type.tractorBeamCount > 0;
            double effectiveSpeed = type.speed * ShipModuleRules.speedMultiplier(unit);
            members.add(new Member(unit.key(), unit.x, unit.y, effectiveSpeed,
                    type.maxHp + type.maxShield, armed, support));
        }
        return normalizedMembers(members);
    }

    private static List<Member> normalizedMembers(List<Member> input) {
        if (input == null || input.isEmpty()) return List.of();
        List<Member> sorted = input.stream()
                .filter(member -> member != null && !member.key().isBlank())
                .sorted(Comparator.comparing(Member::key))
                .toList();
        Map<String, Member> unique = new LinkedHashMap<>();
        for (Member member : sorted) unique.putIfAbsent(member.key(), member);
        return List.copyOf(unique.values());
    }

    private static Plan clamp(World world, Plan base) {
        if (base.targets().isEmpty()) return base;
        Map<String, Target> clamped = new LinkedHashMap<>();
        for (Map.Entry<String, Target> entry : base.targets().entrySet()) {
            Target target = entry.getValue();
            clamped.put(entry.getKey(), new Target(
                    Calc.clamp(target.x(), 0, world.width),
                    Calc.clamp(target.y(), 0, world.height)));
        }
        return new Plan(clamped, base.pace(), base.forwardX(), base.forwardY(), base.anchorKey());
    }

    private static Plan buildPlan(List<Member> members, List<Slot> slots,
                                  double anchorX, double anchorY, double fx, double fy,
                                  FleetFormation formation, boolean roleLineBias) {
        double[] forward = normalizedForward(fx, fy, 0, -1);
        fx = forward[0];
        fy = forward[1];
        double rx = -fy;
        double ry = fx;

        Map<Member, Role> roles = roles(members);
        List<Assignment> assignments = assign(members, roles, slots);
        Map<String, Target> targets = new LinkedHashMap<>();
        for (Assignment assignment : assignments) {
            double longitudinal = assignment.slot().longitudinal();
            if (roleLineBias && formation == FleetFormation.LINE && members.size() >= 4) {
                if (assignment.role() == Role.FRONTLINE) longitudinal += BASE_SPACING * 0.28;
                if (assignment.role() == Role.SUPPORT) longitudinal -= BASE_SPACING * 0.32;
            }
            double wx = anchorX + rx * assignment.slot().lateral() + fx * longitudinal;
            double wy = anchorY + ry * assignment.slot().lateral() + fy * longitudinal;
            targets.put(assignment.member().key(), new Target(wx, wy));
        }
        return new Plan(targets, pace(members), fx, fy, anchorMember(members));
    }

    private static Plan empty() {
        return new Plan(Map.of(), 0, 0, -1, "");
    }

    private static double[] normalizedForward(double x, double y, double fallbackX, double fallbackY) {
        double length = Math.hypot(x, y);
        if (!Double.isFinite(length) || length < 1.0e-9) {
            x = fallbackX;
            y = fallbackY;
            length = Math.hypot(x, y);
        }
        if (!Double.isFinite(length) || length < 1.0e-9) return new double[]{0, -1};
        return new double[]{x / length, y / length};
    }

    private static List<Slot> slots(FleetFormation formation, int count, double spacing) {
        List<Slot> raw = new ArrayList<>(count);
        switch (formation) {
            case LINE -> {
                for (int i = 0; i < count; i++) raw.add(new Slot(i, (i - (count - 1) / 2.0) * spacing, 0));
            }
            case COLUMN -> {
                for (int i = 0; i < count; i++) raw.add(new Slot(i, 0, ((count - 1) / 2.0 - i) * spacing));
            }
            case WEDGE -> {
                for (int i = 0; i < count; i++) {
                    if (i == 0) raw.add(new Slot(i, 0, spacing * 0.8));
                    else {
                        int rank = (i + 1) / 2;
                        int side = (i & 1) == 1 ? -1 : 1;
                        raw.add(new Slot(i, side * rank * spacing * 0.9, -rank * spacing * 0.72));
                    }
                }
            }
            case GRID -> {
                int cols = (int)Math.ceil(Math.sqrt(count));
                int rows = (int)Math.ceil(count / (double)cols);
                for (int i = 0; i < count; i++) {
                    int col = i % cols;
                    int row = i / cols;
                    raw.add(new Slot(i,
                            (col - (cols - 1) / 2.0) * spacing,
                            ((rows - 1) / 2.0 - row) * spacing));
                }
            }
        }
        return centered(raw);
    }

    private static List<Slot> escortSlots(int count, double radius) {
        List<Slot> raw = new ArrayList<>(count);
        if (count == 1) {
            raw.add(new Slot(0, radius, 0));
            return raw;
        }
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2.0 * i / count;
            raw.add(new Slot(i, Math.sin(angle) * radius, Math.cos(angle) * radius));
        }
        return raw;
    }

    private static List<Slot> centered(List<Slot> raw) {
        if (raw.isEmpty()) return List.of();
        double meanLateral = raw.stream().mapToDouble(Slot::lateral).average().orElse(0);
        double meanLongitudinal = raw.stream().mapToDouble(Slot::longitudinal).average().orElse(0);
        List<Slot> centered = new ArrayList<>(raw.size());
        for (Slot slot : raw) {
            centered.add(new Slot(slot.index(), slot.lateral() - meanLateral,
                    slot.longitudinal() - meanLongitudinal));
        }
        return centered;
    }

    private static Map<Member, Role> roles(List<Member> members) {
        double medianSpeed = median(members.stream().map(Member::speed).toList());
        double medianDurability = median(members.stream().map(Member::durability).toList());
        Map<Member, Role> out = new LinkedHashMap<>();
        for (Member member : members) {
            Role role;
            if (member.supportPreferred()) role = Role.SUPPORT;
            else if (member.armed() && member.durability() >= medianDurability * 1.10
                    && member.speed() <= medianSpeed * 1.20) role = Role.FRONTLINE;
            else if (member.armed() && member.speed() >= medianSpeed * 1.10
                    && member.durability() <= medianDurability * 1.30) role = Role.SCREEN;
            else role = Role.LINE;
            out.put(member, role);
        }
        return out;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().filter(Double::isFinite).sorted().toList();
        if (sorted.isEmpty()) return 0;
        int mid = sorted.size() / 2;
        return (sorted.size() & 1) == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) * 0.5;
    }

    private static List<Assignment> assign(List<Member> members, Map<Member, Role> roles, List<Slot> slots) {
        Map<Role, List<Member>> grouped = new EnumMap<>(Role.class);
        for (Role role : Role.values()) grouped.put(role, new ArrayList<>());
        for (Member member : members) grouped.get(roles.get(member)).add(member);
        grouped.get(Role.FRONTLINE).sort(Comparator.comparingDouble(Member::durability).reversed().thenComparing(Member::key));
        grouped.get(Role.SCREEN).sort(Comparator.comparingDouble(Member::speed).reversed().thenComparing(Member::key));
        grouped.get(Role.SUPPORT).sort(Comparator.comparing(Member::key));
        grouped.get(Role.LINE).sort(Comparator.comparing(Member::key));

        List<Slot> remaining = new ArrayList<>(slots);
        List<Assignment> out = new ArrayList<>(members.size());
        Role[] allocationOrder = {Role.SUPPORT, Role.FRONTLINE, Role.SCREEN, Role.LINE};
        for (Role role : allocationOrder) {
            List<Member> roleMembers = grouped.get(role);
            if (roleMembers.isEmpty()) continue;
            remaining.sort(Comparator.comparingDouble((Slot slot) -> slotScore(role, slot))
                    .thenComparingInt(Slot::index));
            int take = Math.min(roleMembers.size(), remaining.size());
            List<Slot> chosen = new ArrayList<>(remaining.subList(0, take));
            remaining = new ArrayList<>(remaining.subList(take, remaining.size()));
            for (int i = 0; i < take; i++) out.add(new Assignment(roleMembers.get(i), role, chosen.get(i)));
        }
        out.sort(Comparator.comparing(assignment -> assignment.member().key()));
        return out;
    }

    private static double slotScore(Role role, Slot slot) {
        return switch (role) {
            case SUPPORT -> slot.longitudinal() * 10 + Math.abs(slot.lateral()) * 0.01;
            case FRONTLINE -> -slot.longitudinal() * 10 + Math.abs(slot.lateral()) * 0.01;
            case SCREEN -> -Math.abs(slot.lateral()) * 10 + Math.abs(slot.longitudinal()) * 0.10;
            case LINE -> Math.abs(slot.lateral()) * 0.10 + Math.abs(slot.longitudinal()) * 0.10;
        };
    }

    private static String anchorMember(List<Member> members) {
        return members.stream()
                .filter(member -> member.speed() > 0)
                .min(Comparator.comparingDouble(Member::speed)
                        .thenComparing(Comparator.comparingDouble(Member::durability).reversed())
                        .thenComparing(Member::key))
                .map(Member::key)
                .orElse(members.isEmpty() ? "" : members.get(0).key());
    }

    private static double pace(List<Member> members) {
        if (members.size() <= 1) return 0;
        double slowest = Double.POSITIVE_INFINITY;
        for (Member member : members) if (member.speed() > 0) slowest = Math.min(slowest, member.speed());
        if (!Double.isFinite(slowest)) return 0;
        return Math.min(MAX_PACE, slowest);
    }
}
