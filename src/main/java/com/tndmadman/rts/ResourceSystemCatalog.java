package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ResourceSystemCatalog {
    private static final List<Entry> ENTRIES = buildResources();
    private static final List<SystemEntry> SYSTEMS = buildSystems();

    private ResourceSystemCatalog() { }

    static List<Entry> entries() { return ENTRIES; }
    static List<SystemEntry> systems() { return SYSTEMS; }
    static int systemTemplateCount() { return SYSTEMS.size(); }

    static Entry entry(Material material) {
        for (Entry entry : ENTRIES) if (entry.material() == material) return entry;
        throw new IllegalArgumentException("Unknown catalog material: " + material);
    }

    static List<Entry> filterEntries(String query) {
        String needle = normalize(query);
        if (needle.isBlank()) return ENTRIES;
        List<Entry> out = new ArrayList<>();
        for (Entry entry : ENTRIES) if (entry.searchText().contains(needle)) out.add(entry);
        return List.copyOf(out);
    }

    static List<SystemEntry> filterSystems(String query) {
        String needle = normalize(query);
        if (needle.isBlank()) return SYSTEMS;
        List<SystemEntry> out = new ArrayList<>();
        for (SystemEntry system : SYSTEMS) if (system.searchText().contains(needle)) out.add(system);
        return List.copyOf(out);
    }

    private static List<Entry> buildResources() {
        EnumMap<Material, LinkedHashMap<String, AvailabilityBuilder>> availability = new EnumMap<>(Material.class);
        for (Material material : Material.values()) availability.put(material, new LinkedHashMap<>());

        for (StarSystemDefinition system : StarSystems.options()) {
            for (ResourceBelt belt : system.resourceBelts()) {
                List<Material> materials = belt.materials.isEmpty() ? List.of(Material.IRON) : belt.materials;
                for (Material material : new LinkedHashSet<>(materials)) {
                    LinkedHashMap<String, AvailabilityBuilder> systems = availability.get(material);
                    if (systems == null) continue;
                    systems.computeIfAbsent(system.id(), ignored -> new AvailabilityBuilder(system)).nodeKinds.add(belt.kind);
                }
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (Material material : Material.values()) {
            List<SystemAvailability> systems = new ArrayList<>();
            for (AvailabilityBuilder builder : availability.get(material).values()) systems.add(builder.build());
            entries.add(new Entry(material, systems));
        }
        return List.copyOf(entries);
    }

    private static List<SystemEntry> buildSystems() {
        List<SystemEntry> out = new ArrayList<>();
        for (StarSystemDefinition system : StarSystems.options()) {
            List<SpawnBand> spawns = new ArrayList<>();
            for (ResourceBelt belt : system.resourceBelts()) {
                List<Material> materials = belt.materials.isEmpty()
                        ? List.of(Material.IRON)
                        : List.copyOf(new LinkedHashSet<>(belt.materials));
                spawns.add(new SpawnBand(belt.name, belt.kind, materials, belt.orbit, belt.width, belt.arc,
                        belt.count, belt.amount, belt.harvestRate, belt.radius));
            }

            Map<String, String> bodyNames = new LinkedHashMap<>();
            for (CelestialBodyDefinition body : system.bodies()) bodyNames.put(body.id(), body.name());
            List<CelestialOrbit> bodies = new ArrayList<>();
            for (CelestialBodyDefinition body : system.bodies()) {
                String parentName = body.parentId() == null ? "" : bodyNames.getOrDefault(body.parentId(), body.parentId());
                bodies.add(new CelestialOrbit(body.id(), body.name(), parentName, body.orbitRadius(), body.radius(), body.orbitSpeed()));
            }

            out.add(new SystemEntry(system.id(), system.name(), system.role(), system.width(), system.height(),
                    system.tags(), system.modifiers(), spawns, bodies));
        }
        return List.copyOf(out);
    }

    record Entry(Material material, List<SystemAvailability> systems) {
        Entry {
            if (material == null) throw new IllegalArgumentException("Catalog entry material is required.");
            systems = systems == null ? List.of() : List.copyOf(systems);
        }

        boolean naturallyAvailable() { return !systems.isEmpty(); }

        String sourceLabel() {
            if (material.raw) return naturallyAvailable() ? "Natural resource" : "Raw resource unavailable";
            if (material.family == MaterialFamily.SALVAGE) return "Salvage material";
            return "Manufactured material";
        }

        String summary() {
            return title(material.family.name()) + " | " + title(material.tier.name()) + " | " + sourceLabel();
        }

        String displayText() {
            StringBuilder out = new StringBuilder();
            out.append(material.label).append('\n');
            out.append("ID: ").append(material.name()).append('\n');
            out.append("Family: ").append(title(material.family.name())).append('\n');
            out.append("Rarity: ").append(title(material.tier.name())).append('\n');
            out.append("Source: ").append(sourceLabel()).append("\n\n");
            out.append("AVAILABLE IN SYSTEM TEMPLATES\n");
            if (systems.isEmpty()) {
                if (material.family == MaterialFamily.SALVAGE) {
                    out.append("Obtained from salvage; it is not placed in natural resource belts.\n");
                } else {
                    out.append("Manufactured from other resources; it is not placed in natural resource belts.\n");
                }
                return out.toString();
            }
            for (SystemAvailability system : systems) {
                out.append("\n- ").append(system.systemName()).append(" [").append(title(system.role())).append("]\n");
                out.append("  ID: ").append(system.systemId()).append('\n');
                out.append("  Node types: ").append(nodeKinds(system.nodeKinds())).append('\n');
            }
            return out.toString();
        }

        String searchText() {
            StringBuilder out = new StringBuilder();
            out.append(material.name()).append(' ').append(material.label).append(' ')
                    .append(material.family.name()).append(' ').append(material.tier.name()).append(' ')
                    .append(sourceLabel()).append(' ');
            for (SystemAvailability system : systems) {
                out.append(system.systemId()).append(' ').append(system.systemName()).append(' ')
                        .append(system.role()).append(' ').append(nodeKinds(system.nodeKinds())).append(' ');
            }
            return normalize(out.toString());
        }
    }

    record SystemAvailability(String systemId, String systemName, String role, Set<NodeKind> nodeKinds) {
        SystemAvailability {
            systemId = systemId == null ? "" : systemId;
            systemName = systemName == null || systemName.isBlank() ? systemId : systemName;
            role = role == null || role.isBlank() ? "standard" : role;
            nodeKinds = nodeKinds == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(nodeKinds));
        }
    }

    record SystemEntry(String id, String name, String role, int width, int height, Set<String> tags,
                       SystemModifiers modifiers, List<SpawnBand> spawns, List<CelestialOrbit> bodies) {
        SystemEntry {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            role = role == null || role.isBlank() ? "standard" : role;
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            modifiers = modifiers == null ? SystemModifiers.STANDARD : modifiers;
            spawns = spawns == null ? List.of() : List.copyOf(spawns);
            bodies = bodies == null ? List.of() : List.copyOf(bodies);
        }

        String summary() {
            Set<Material> materials = new LinkedHashSet<>();
            for (SpawnBand spawn : spawns) materials.addAll(spawn.materials());
            return title(role) + " | " + spawns.size() + " spawn bands | " + materials.size() + " natural materials";
        }

        String displayText() {
            StringBuilder out = new StringBuilder();
            out.append(name).append(" [").append(title(role)).append("]\n");
            out.append("ID: ").append(id).append('\n');
            out.append("Map size: ").append(width).append(" x ").append(height).append(" units\n");
            if (!tags.isEmpty()) out.append("Tags: ").append(String.join(", ", tags)).append('\n');
            out.append("Modifiers: mining ").append(multiplier(modifiers.miningYield()))
                    .append(" | respawn ").append(multiplier(modifiers.resourceRespawn()))
                    .append(" | sensors ").append(multiplier(modifiers.sensorRange()))
                    .append(" | shields ").append(multiplier(modifiers.shieldRegen()))
                    .append(" | movement ").append(multiplier(modifiers.movementSpeed()))
                    .append(" | weapon range ").append(multiplier(modifiers.weaponRange()));
            if (modifiers.environmentalDamagePerSecond() > 0) {
                out.append(" | environmental damage ").append(number(modifiers.environmentalDamagePerSecond())).append("/sec");
            }
            out.append("\n\nNATURAL RESOURCE SPAWNS\n");
            if (spawns.isEmpty()) {
                out.append("No natural resource belts are configured for this system.\n");
            } else {
                for (int i = 0; i < spawns.size(); i++) {
                    SpawnBand spawn = spawns.get(i);
                    out.append("\n").append(i + 1).append(". ").append(spawn.name()).append('\n');
                    out.append("   Materials: ").append(materials(spawn.materials())).append('\n');
                    out.append("   Node type: ").append(title(spawn.kind().name())).append('\n');
                    out.append("   Orbit around primary: ").append(number(spawn.orbit())).append(" units")
                            .append(" (spread +/- ").append(number(spawn.width())).append(")\n");
                    out.append("   Arc coverage: ").append(number(Math.toDegrees(spawn.arc()))).append(" degrees")
                            .append(" | Configured nodes: ").append(spawn.count()).append('\n');
                    out.append("   Average amount: ").append(number(spawn.amount()))
                            .append(" | Harvest rate: ").append(number(spawn.harvestRate())).append("/sec")
                            .append(" | Base node radius: ").append(number(spawn.radius())).append('\n');
                }
            }

            if (!bodies.isEmpty()) {
                out.append("\nCELESTIAL ORBITS\n");
                for (CelestialOrbit body : bodies) {
                    out.append("\n- ").append(body.name());
                    if (body.parentName().isBlank()) out.append(" — primary body at system center");
                    else out.append(" — orbits ").append(body.parentName()).append(" at ")
                            .append(number(body.orbitRadius())).append(" units");
                    out.append(" | body radius ").append(number(body.radius()));
                    if (body.orbitSpeed() != 0) out.append(" | speed ").append(number(body.orbitSpeed()));
                    out.append('\n');
                }
            }
            return out.toString();
        }

        String searchText() {
            StringBuilder out = new StringBuilder();
            out.append(id).append(' ').append(name).append(' ').append(role).append(' ')
                    .append(String.join(" ", tags)).append(' ');
            for (SpawnBand spawn : spawns) {
                out.append(spawn.name()).append(' ').append(spawn.kind().name()).append(' ')
                        .append(materials(spawn.materials())).append(' ').append(number(spawn.orbit())).append(' ');
            }
            for (CelestialOrbit body : bodies) {
                out.append(body.id()).append(' ').append(body.name()).append(' ').append(body.parentName()).append(' ')
                        .append(number(body.orbitRadius())).append(' ');
            }
            return normalize(out.toString());
        }
    }

    record SpawnBand(String name, NodeKind kind, List<Material> materials, double orbit, double width,
                     double arc, int count, double amount, double harvestRate, double radius) {
        SpawnBand {
            name = name == null || name.isBlank() ? "Resource Belt" : name;
            kind = kind == null ? NodeKind.SILICATE_ROCK : kind;
            materials = materials == null ? List.of() : List.copyOf(materials);
        }
    }

    record CelestialOrbit(String id, String name, String parentName, double orbitRadius, double radius,
                          double orbitSpeed) {
        CelestialOrbit {
            id = id == null ? "" : id;
            name = name == null || name.isBlank() ? id : name;
            parentName = parentName == null ? "" : parentName;
        }
    }

    private static final class AvailabilityBuilder {
        private final StarSystemDefinition system;
        private final EnumSet<NodeKind> nodeKinds = EnumSet.noneOf(NodeKind.class);

        private AvailabilityBuilder(StarSystemDefinition system) { this.system = system; }

        private SystemAvailability build() {
            return new SystemAvailability(system.id(), system.name(), system.role(), nodeKinds);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) return "Standard";
        String[] parts = value.toLowerCase(Locale.ROOT).split("[_\\s-]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }

    private static String materials(List<Material> values) {
        if (values == null || values.isEmpty()) return "None";
        List<String> labels = new ArrayList<>();
        for (Material material : values) labels.add(material.label);
        return String.join(", ", labels);
    }

    private static String nodeKinds(Set<NodeKind> values) {
        if (values == null || values.isEmpty()) return "Unspecified node type";
        List<String> labels = new ArrayList<>();
        for (NodeKind kind : values) labels.add(title(kind.name()));
        return String.join(", ", labels);
    }

    private static String multiplier(double value) { return "x" + number(value); }

    private static String number(double value) {
        if (!Double.isFinite(value)) return "0";
        if (Math.abs(value - Math.rint(value)) < 0.0001) return Long.toString(Math.round(value));
        String text = String.format(Locale.ROOT, "%.2f", value);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}