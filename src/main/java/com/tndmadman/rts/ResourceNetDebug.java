package com.tndmadman.rts;

import java.util.*;

final class ResourceNetDebug {
    private static final int SAMPLE_LIMIT = 4;
    private static final long SNAPSHOT_MS = 1000;
    private static final long WORLD_MS = 2000;
    private static final boolean ENABLED = readEnabled();
    private static final Map<String, Long> NEXT_LOG = new HashMap<>();
    private static final Set<String> ONCE = new LinkedHashSet<>();

    private ResourceNetDebug() { }

    static boolean enabled() {
        return ENABLED;
    }

    static void log(String area, String message) {
        if (!ENABLED) return;
        System.out.println("[RESDBG][" + area + "][" + Thread.currentThread().getName() + "] " + message);
    }

    static void resourceSchema(int columns) {
        if (!ENABLED || !once("schema:" + columns)) return;
        log("SCHEMA", "resource row columns=" + columns + " (18 means orbit fields are present)");
    }

    static void snapshotBuilt(World world, String mode, Collection<ResourceState> states) {
        if (!ENABLED) return;
        int size = states == null ? 0 : states.size();
        String key = "build:" + System.identityHashCode(world) + ":" + mode;
        boolean important = mode.contains("full") || size > 4;
        if (!important && !shouldLog(key, SNAPSHOT_MS)) return;
        log("SNAP-BUILD", "mode=" + mode + " out=" + stateSummary(states) + " " + worldSummary(world));
    }

    static void sendSnapshot(String kind, String playerId, Snapshot snapshot, World world) {
        if (!ENABLED) return;
        String key = "send:" + kind + ":" + playerId;
        boolean important = "INITIAL".equals(kind) || fullish(snapshot, world);
        if (!important && !shouldLog(key, SNAPSHOT_MS)) return;
        log("HOST-SEND", "kind=" + kind + " player=" + playerId + " " + snapshotSummary(snapshot) + " " + worldSummary(world));
    }

    static void clientReceive(String kind, Snapshot snapshot, long lastSequence, boolean viewSnapshotMode) {
        if (!ENABLED) return;
        String key = "recv:" + kind;
        boolean important = kind.contains("FULL") || !snapshot.resources().isEmpty();
        if (!important && !shouldLog(key, SNAPSHOT_MS)) return;
        log("CLIENT-RECV", "kind=" + kind + " prevSeq=" + lastSequence + " viewMode=" + viewSnapshotMode + " " + snapshotSummary(snapshot));
    }

    static void staleSnapshot(String kind, Snapshot snapshot, long lastSequence) {
        log("CLIENT-DROP", "stale kind=" + kind + " seq=" + snapshot.sequence() + " lastSeq=" + lastSequence + " " + snapshotSummary(snapshot));
    }

    static void worldApplyStart(World world, Snapshot snapshot, boolean allowNoLocalAssets, boolean fullResourceView, boolean hasLocalAssets) {
        if (!ENABLED) return;
        if (snapshot.resources().isEmpty() && !shouldLog("apply-empty:" + System.identityHashCode(world), SNAPSHOT_MS)) return;
        log("WORLD-APPLY", "start allowView=" + allowNoLocalAssets + " fullResources=" + fullResourceView + " hasLocalAssets=" + hasLocalAssets + " "
                + snapshotSummary(snapshot) + " before=" + worldSummary(world));
    }

    static void worldApplyEnd(World world, Snapshot snapshot) {
        if (!ENABLED || snapshot.resources().isEmpty()) return;
        log("WORLD-APPLY", "end seq=" + snapshot.sequence() + " after=" + worldSummary(world));
    }

    static void viewReset(World world, String snapSystem, long seed, double time) {
        log("VIEW-RESET", "snapshotSystem=" + snapSystem + " seed=" + seed + " time=" + fmt(time) + " before=" + worldSummary(world));
    }

    static void ignoredSnapshot(World world, Snapshot snapshot, String reason) {
        log("WORLD-DROP", reason + " " + snapshotSummary(snapshot) + " " + worldSummary(world));
    }

    static void resourceApplyPath(World world, Snapshot snapshot, String path) {
        if (!ENABLED || snapshot.resources().isEmpty()) return;
        log("RES-PATH", path + " seq=" + snapshot.sequence() + " snap=" + stateSummary(snapshot.resources()) + " before=" + worldSummary(world));
    }

    static void resourceViewStart(World world, boolean replace, Collection<ResourceState> states) {
        if (!ENABLED) return;
        log("VIEW-RES", (replace ? "replace" : "merge") + " before=" + worldSummary(world) + " incoming=" + stateSummary(states));
    }

    static void resourceViewEnd(World world, boolean replace) {
        if (!ENABLED) return;
        log("VIEW-RES", (replace ? "replace" : "merge") + " after=" + worldSummary(world));
    }

    static void netResourceCorrection(String reason, ResourceState state, String before, ResourceNode after, double drift) {
        log("NET-RES", "correct reason=" + reason + " drift=" + fmt(drift) + " before=" + before + " snap=" + stateShort(state) + " after=" + nodeShort(after));
    }

    static void netResourceSummary(World world, int seen, int missing, int corrected, int amountOnly) {
        if (!ENABLED || !shouldLog("net-summary:" + System.identityHashCode(world), SNAPSHOT_MS)) return;
        log("NET-RES", "summary seen=" + seen + " missing=" + missing + " corrected=" + corrected + " amountOnly=" + amountOnly + " " + worldSummary(world));
    }

    static void orbitRecomputed(ResourceNode node, ResourceState state, double drift) {
        if (!ENABLED || drift < 1.0 || !shouldLog("orbit:" + node.id, SNAPSHOT_MS)) return;
        log("ORBIT", "orbit recompute moved id=" + node.id + " drift=" + fmt(drift) + " snap=" + stateShort(state) + " node=" + nodeShort(node));
    }

    static void worldTick(World world, double dt) {
        if (!ENABLED || !shouldLog("world:" + System.identityHashCode(world) + ":" + world.activeSystemId(), WORLD_MS)) return;
        log("WORLD", "tick dt=" + fmt(dt) + " " + worldSummary(world));
    }

    static void select(World world, double x, double y, ResourceNode node) {
        if (!ENABLED) return;
        log("SELECT", "click=" + point(x, y) + " hit=" + nodeShort(node) + " " + worldSummary(world));
    }

    static void autoHarvest(World world, ResourceNode node, List<Unit> units) {
        if (!ENABLED) return;
        StringBuilder selected = new StringBuilder();
        for (int i = 0; i < units.size() && i < SAMPLE_LIMIT; i++) {
            if (!selected.isEmpty()) selected.append(" | ");
            selected.append(unitShort(units.get(i)));
        }
        log("ORDER-LOCAL", "autoHarvest target=" + nodeShort(node) + " selected=" + units.size() + " [" + selected + "] " + worldSummary(world));
    }

    static void clientWorkSend(World world, HarvestCommand command) {
        if (!ENABLED) return;
        Unit unit = world.units.get(Unit.key(command.playerId(), command.unitId()));
        ResourceNode node = world.findResource(command.resourceId());
        log("ORDER-SEND", "WORK " + command.playerId() + ":" + command.unitId() + " res=" + command.resourceId()
                + " unit=" + unitShort(unit) + " node=" + nodeShort(node) + " " + worldSummary(world));
    }

    static void hostWorkOrder(World world, HarvestCommand command, Unit unit, ResourceNode node) {
        if (!ENABLED) return;
        log("ORDER-HOST", "WORK " + command.playerId() + ":" + command.unitId() + " res=" + command.resourceId()
                + " unit=" + unitShort(unit) + " node=" + nodeShort(node) + " " + worldSummary(world));
    }

    static void workState(World world, Unit unit, ResourceNode node, String state) {
        if (!ENABLED) return;
        int resourceId = node == null ? unit.automationResourceId : node.id;
        String key = "work:" + unit.key() + ":" + resourceId + ":" + state;
        if (!shouldLog(key, SNAPSHOT_MS)) return;
        log("WORK", state + " unit=" + unitShort(unit) + " node=" + nodeShort(node) + " " + worldSummary(world));
    }

    static String nodeShort(ResourceNode node) {
        if (node == null) return "null";
        return "#" + node.id + " " + (node.active ? "active" : "inactive")
                + " " + node.material + " amt=" + fmt(node.amount) + "/" + fmt(node.maxAmount)
                + " xy=" + point(node.x, node.y) + " orbit=" + orbit(node.orbiting, node.orbitCenterX, node.orbitCenterY, node.orbitRadius, node.orbitAngle, node.orbitSpeed);
    }

    static String stateShort(ResourceState state) {
        if (state == null) return "null";
        return "#" + state.id() + " " + (state.active() ? "active" : "inactive")
                + " " + state.material() + " amt=" + fmt(state.amount()) + "/" + fmt(state.maxAmount())
                + " xy=" + point(state.x(), state.y()) + " orbit=" + orbit(state.orbiting(), state.orbitCenterX(), state.orbitCenterY(), state.orbitRadius(), state.orbitAngle(), state.orbitSpeed());
    }

    private static String snapshotSummary(Snapshot snapshot) {
        return "seq=" + snapshot.sequence() + " sys=" + snapshot.systemId() + " t=" + fmt(snapshot.systemTime())
                + " players=" + snapshot.players().size() + " units=" + snapshot.units().size()
                + " resources=" + stateSummary(snapshot.resources()) + " bases=" + snapshot.bases().size()
                + " stocks=" + snapshot.stocks().size();
    }

    private static String worldSummary(World world) {
        if (world == null) return "world=null";
        int active = 0, dupes = 0;
        Set<Integer> ids = new HashSet<>();
        StringBuilder sample = new StringBuilder();
        for (ResourceNode node : world.resources) {
            if (node.active) active++;
            if (!ids.add(node.id)) dupes++;
            if (sample.length() < 1 || countSamples(sample) < SAMPLE_LIMIT) {
                if (!sample.isEmpty()) sample.append(" | ");
                sample.append(nodeShort(node));
            }
        }
        return "worldSys=" + world.activeSystemId() + " t=" + fmt(world.systemTime()) + " res=" + world.resources.size()
                + " active=" + active + " dupIds=" + dupes + " selected=" + world.selectedResourceId + " sample=[" + sample + "]";
    }

    private static String stateSummary(Collection<ResourceState> states) {
        if (states == null) return "null";
        int active = 0, sampleCount = 0;
        StringBuilder sample = new StringBuilder();
        for (ResourceState state : states) {
            if (state.active()) active++;
            if (sampleCount++ < SAMPLE_LIMIT) {
                if (!sample.isEmpty()) sample.append(" | ");
                sample.append(stateShort(state));
            }
        }
        return states.size() + " active=" + active + " sample=[" + sample + "]";
    }

    private static String unitShort(Unit unit) {
        if (unit == null) return "null";
        return unit.key() + " task=" + unit.task + " res=" + unit.automationResourceId
                + " xy=" + point(unit.x, unit.y) + " target=" + point(unit.targetX, unit.targetY);
    }

    private static boolean fullish(Snapshot snapshot, World world) {
        if (snapshot.resources().isEmpty() || world == null || world.resources.isEmpty()) return false;
        return snapshot.resources().size() >= Math.max(4, world.resources.size() / 2);
    }

    private static String orbit(boolean orbiting, double cx, double cy, double radius, double angle, double speed) {
        if (!orbiting) return "off";
        return "on center=" + point(cx, cy) + " r=" + fmt(radius) + " a=" + fmt(angle) + " v=" + fmt(speed);
    }

    private static int countSamples(StringBuilder sample) {
        if (sample.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < sample.length(); i++) if (sample.charAt(i) == '|') count++;
        return count;
    }

    private static String point(double x, double y) {
        return "(" + fmt(x) + "," + fmt(y) + ")";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static synchronized boolean shouldLog(String key, long intervalMs) {
        long now = System.currentTimeMillis();
        Long next = NEXT_LOG.get(key);
        if (next != null && now < next) return false;
        NEXT_LOG.put(key, now + intervalMs);
        return true;
    }

    private static synchronized boolean once(String key) {
        return ONCE.add(key);
    }

    private static boolean readEnabled() {
        String value = System.getProperty("starchem.debug.resources");
        if (value == null || value.isBlank()) value = System.getenv("STARCHEM_DEBUG_RESOURCES");
        if (value == null || value.isBlank()) return true;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("0") && !normalized.equals("false") && !normalized.equals("off") && !normalized.equals("no");
    }
}
