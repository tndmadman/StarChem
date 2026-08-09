package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Persistent, bounded standing-production policy controller for issue #294. */
final class ProductionPolicySystem {
    static final String COMMAND_CREATE = "CREATE";
    static final String COMMAND_UPDATE = "UPDATE";
    static final String COMMAND_TOGGLE = "TOGGLE";
    static final String COMMAND_DELETE = "DELETE";
    static final String COMMAND_MOVE_UP = "MOVE_UP";
    static final String COMMAND_MOVE_DOWN = "MOVE_DOWN";
    static final String COMMAND_TEMPLATE_SAVE = "TEMPLATE_SAVE";
    static final String COMMAND_TEMPLATE_APPLY = "TEMPLATE_APPLY";
    static final String COMMAND_TEMPLATE_DELETE = "TEMPLATE_DELETE";

    static final int MAX_POLICIES_PER_PLAYER = 128;
    static final int MAX_POLICIES_PER_STATION = 32;
    static final int MAX_TEMPLATES_PER_PLAYER = 32;
    static final int MAX_TEMPLATE_ENTRIES = 32;
    static final int MAX_OUTSTANDING_PER_POLICY = 16;
    static final int MAX_BATCH_SIZE = 32;
    static final int MAX_COMMAND_CHARS = 4096;

    private static final int MAX_ENQUEUES_PER_EVALUATION = 16;
    private static final double UPDATE_INTERVAL = 0.50;
    private static final double EPSILON = 0.05;
    private static final double MAX_TARGET = 1_000_000;
    private static final int MAX_REPEAT_LIMIT = 100_000;
    private static final String POLICY_MARKER = "Production policies: ";
    private static final String TEMPLATE_MARKER = "Production templates: ";
    private static final Map<World, RuntimeState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ProductionPolicySystem() { }

    enum PolicyType { MAINTAIN_STOCK, MAINTAIN_FLEET, REPEAT }

    enum PolicyStatus {
        SATISFIED,
        PRODUCING,
        WAITING_FOR_RESOURCES,
        BLOCKED_RESEARCH,
        NO_COMPATIBLE_STATION,
        RESERVE_PROTECTED,
        PAUSED,
        ORPHANED
    }

    record PolicyView(String id, PolicyType type, ProductionJobKind kind, String itemId,
                      String loadoutId, double targetAmount, int batchSize, int priority,
                      int maxOutstandingJobs, int repeatLimit, int completedBatches,
                      boolean enabled, PolicyStatus status, String reason, List<String> jobIds) { }

    record TemplateView(String id, String name, int entryCount) { }

    static synchronized boolean applyCommand(World world, String playerId, String baseId,
                                             String commandValue, String payload) {
        if (world == null || !validToken(playerId, 64) || !validToken(baseId, 128)
                || commandValue == null) return false;
        Base base = world.bases.get(baseId);
        if (base == null || base.hp <= 0 || !playerId.equals(base.playerId)
                || StationControls.nonProduction(base.typeId)) return false;
        String command = commandValue.trim().toUpperCase(Locale.ROOT);
        String data = payload == null ? "" : payload.trim();
        if (data.length() > MAX_COMMAND_CHARS) return false;
        RuntimeState state = state(world);
        boolean changed = switch (command) {
            case COMMAND_CREATE -> create(world, state, playerId, base, data);
            case COMMAND_UPDATE -> updateDefinition(world, state, playerId, base, data);
            case COMMAND_TOGGLE -> toggle(state, playerId, base, data);
            case COMMAND_DELETE -> delete(state, playerId, base, data);
            case COMMAND_MOVE_UP -> move(state, playerId, base, data, -1);
            case COMMAND_MOVE_DOWN -> move(state, playerId, base, data, 1);
            case COMMAND_TEMPLATE_SAVE -> saveTemplate(world, state, playerId, base, data);
            case COMMAND_TEMPLATE_APPLY -> applyTemplate(world, state, playerId, base, data);
            case COMMAND_TEMPLATE_DELETE -> deleteTemplate(state, playerId, data);
            default -> false;
        };
        if (changed) refreshCurrentSystem(world, state);
        return changed;
    }

    static synchronized void update(World world, double dt) {
        if (world == null || !Double.isFinite(dt) || dt < 0) return;
        RuntimeState state = STATES.get(world);
        if (state == null || state.policies.isEmpty()) return;
        String systemId = clean(world.activeSystemId());
        if (systemId.isBlank()) return;
        reconcileFinishedLinks(world, state, systemId);
        double elapsed = state.systemTimers.getOrDefault(systemId, 0.0) + Math.min(1.0, dt);
        if (elapsed + 0.000001 < UPDATE_INTERVAL) {
            state.systemTimers.put(systemId, elapsed);
            refreshCurrentSystem(world, state);
            return;
        }
        state.systemTimers.put(systemId, 0.0);

        List<ProductionPolicy> ordered = new ArrayList<>();
        for (ProductionPolicy policy : state.policies.values()) {
            if (systemId.equals(policy.systemId)) ordered.add(policy);
        }
        if (ordered.isEmpty()) {
            refreshCurrentSystem(world, state);
            return;
        }
        ordered.sort(Comparator.comparingInt((ProductionPolicy policy) -> policy.priority).reversed()
                .thenComparing(policy -> policy.id));
        SupplyLedger ledger = SupplyLedger.capture(world);
        int enqueueBudget = MAX_ENQUEUES_PER_EVALUATION;
        for (ProductionPolicy policy : ordered) {
            enqueueBudget -= evaluate(world, state, policy, ledger, enqueueBudget);
            if (enqueueBudget < 0) enqueueBudget = 0;
        }
        refreshCurrentSystem(world, state);
    }

    static synchronized void onManualJobCancelled(World world, Base base, ProductionJob job) {
        if (world == null || base == null || job == null) return;
        RuntimeState state = STATES.get(world);
        if (state == null) return;
        JobKey key = new JobKey(clean(world.activeSystemId()), base.id, job.id);
        String policyId = state.jobPolicies.remove(key);
        if (policyId == null) return;
        ProductionPolicy policy = state.policies.get(policyId);
        if (policy != null) {
            policy.enabled = false;
            policy.status = PolicyStatus.PAUSED;
            policy.reason = "paused after manual cancellation";
        }
        refreshCurrentSystem(world, state);
    }

    static synchronized void transferJob(World world, Base base, String oldJobId, String newJobId) {
        if (world == null || base == null || oldJobId == null || newJobId == null
                || oldJobId.isBlank() || newJobId.isBlank()) return;
        RuntimeState state = STATES.get(world);
        if (state == null) return;
        String systemId = clean(world.activeSystemId());
        JobKey oldKey = new JobKey(systemId, base.id, oldJobId);
        String policyId = state.jobPolicies.remove(oldKey);
        if (policyId != null) state.jobPolicies.put(new JobKey(systemId, base.id, newJobId), policyId);
    }

    static synchronized String jobLabel(World world, Base base, String jobId) {
        if (world == null || base == null || jobId == null || jobId.isBlank()) return "";
        RuntimeState state = STATES.get(world);
        if (state != null) {
            String policyId = state.jobPolicies.get(new JobKey(clean(world.activeSystemId()), base.id, jobId));
            if (policyId != null) return "AUTO " + policyId;
        }
        for (PolicyView view : parseStatusViews(base.logisticsStatus)) {
            if (view.jobIds().contains(jobId)) return "AUTO " + view.id();
        }
        return "";
    }

    static synchronized List<PolicyView> viewsForBase(World world, Base base) {
        if (base == null) return List.of();
        RuntimeState state = world == null ? null : STATES.get(world);
        if (state == null) return parseStatusViews(base.logisticsStatus);
        List<PolicyView> out = new ArrayList<>();
        String systemId = clean(world.activeSystemId());
        for (ProductionPolicy policy : state.policies.values()) {
            if (systemId.equals(policy.systemId) && base.id.equals(policy.stationId)) {
                out.add(view(state, policy));
            }
        }
        out.sort(Comparator.comparingInt(PolicyView::priority).reversed().thenComparing(PolicyView::id));
        return List.copyOf(out);
    }

    static synchronized List<TemplateView> templateViews(World world, Base base) {
        if (base == null) return List.of();
        RuntimeState state = world == null ? null : STATES.get(world);
        if (state == null) return parseTemplateViews(base.logisticsStatus);
        List<TemplateView> out = new ArrayList<>();
        for (ProductionTemplate template : state.templates.values()) {
            if (base.playerId.equals(template.ownerId)) {
                out.add(new TemplateView(template.id, template.name, template.entries.size()));
            }
        }
        out.sort(Comparator.comparing(TemplateView::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TemplateView::id));
        return List.copyOf(out);
    }

    static synchronized Map<String,Object> capture(World world) {
        RuntimeState state = STATES.get(world);
        if (state == null) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("nextPolicyId", state.nextPolicyId);
        out.put("nextTemplateId", state.nextTemplateId);
        List<Object> policies = new ArrayList<>();
        for (ProductionPolicy policy : state.policies.values()) policies.add(capturePolicy(policy));
        out.put("policies", policies);
        List<Object> templates = new ArrayList<>();
        for (ProductionTemplate template : state.templates.values()) templates.add(captureTemplate(template));
        out.put("templates", templates);
        List<Object> links = new ArrayList<>();
        for (Map.Entry<JobKey,String> entry : state.jobPolicies.entrySet()) {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("systemId", entry.getKey().systemId());
            row.put("baseId", entry.getKey().baseId());
            row.put("jobId", entry.getKey().jobId());
            row.put("policyId", entry.getValue());
            links.add(row);
        }
        out.put("jobLinks", links);
        return out;
    }

    static synchronized void restore(World world, Object saved) {
        if (world == null) return;
        Map<String,Object> data = ServerSaveStore.object(saved);
        RuntimeState state = new RuntimeState();
        state.nextPolicyId = Math.max(1, ServerSaveStore.longValue(data, "nextPolicyId", 1));
        state.nextTemplateId = Math.max(1, ServerSaveStore.longValue(data, "nextTemplateId", 1));
        Map<String,Integer> ownerCounts = new LinkedHashMap<>();
        Map<String,Integer> stationCounts = new LinkedHashMap<>();
        for (Object item : ServerSaveStore.list(data.get("policies"))) {
            ProductionPolicy policy = restorePolicy(ServerSaveStore.object(item));
            if (policy == null || state.policies.containsKey(policy.id)) continue;
            int owned = ownerCounts.getOrDefault(policy.ownerId, 0);
            String stationKey = policy.ownerId + '|' + policy.systemId + '|' + policy.stationId;
            int atStation = stationCounts.getOrDefault(stationKey, 0);
            if (owned >= MAX_POLICIES_PER_PLAYER || atStation >= MAX_POLICIES_PER_STATION) continue;
            ownerCounts.put(policy.ownerId, owned + 1);
            stationCounts.put(stationKey, atStation + 1);
            state.policies.put(policy.id, policy);
        }
        Map<String,Integer> templateCounts = new LinkedHashMap<>();
        for (Object item : ServerSaveStore.list(data.get("templates"))) {
            ProductionTemplate template = restoreTemplate(ServerSaveStore.object(item));
            if (template == null || state.templates.containsKey(template.id)) continue;
            int count = templateCounts.getOrDefault(template.ownerId, 0);
            if (count >= MAX_TEMPLATES_PER_PLAYER) continue;
            templateCounts.put(template.ownerId, count + 1);
            state.templates.put(template.id, template);
        }
        for (Object item : ServerSaveStore.list(data.get("jobLinks"))) {
            Map<String,Object> row = ServerSaveStore.object(item);
            String systemId = ServerSaveStore.string(row, "systemId", "");
            String baseId = ServerSaveStore.string(row, "baseId", "");
            String jobId = ServerSaveStore.string(row, "jobId", "");
            String policyId = ServerSaveStore.string(row, "policyId", "");
            if (!state.policies.containsKey(policyId) || !validToken(systemId, 128)
                    || !validToken(baseId, 128) || !validToken(jobId, 64)) continue;
            state.jobPolicies.put(new JobKey(systemId, baseId, jobId), policyId);
        }
        if (state.policies.isEmpty() && state.templates.isEmpty()) STATES.remove(world);
        else STATES.put(world, state);
        refreshCurrentSystem(world, state);
    }

    static synchronized void clear(World world) {
        if (world != null) STATES.remove(world);
    }

    static synchronized void refreshCurrentSystem(World world) {
        RuntimeState state = world == null ? null : STATES.get(world);
        if (state != null) refreshCurrentSystem(world, state);
    }

    static String encodeSpec(String policyId, PolicyType type, ProductionJobKind kind, String itemId,
                             String loadoutId, double targetAmount, int batchSize, int priority,
                             int maxOutstandingJobs, int repeatLimit,
                             Map<Material,Double> stationReserve, Map<Material,Double> networkReserve) {
        return "v1~" + token(policyId) + '~' + type.name() + '~' + kind.name() + '~' + token(itemId)
                + '~' + token(loadoutId) + '~' + targetAmount + '~' + batchSize + '~' + priority + '~'
                + maxOutstandingJobs + '~' + repeatLimit + '~' + reserveText(stationReserve) + '~'
                + reserveText(networkReserve);
    }

    private static boolean create(World world, RuntimeState state, String playerId, Base base, String encoded) {
        PolicySpec spec = parseSpec(encoded);
        if (spec == null || !spec.policyId.isBlank()) return false;
        if (countPlayerPolicies(state, playerId) >= MAX_POLICIES_PER_PLAYER
                || countStationPolicies(state, playerId, clean(world.activeSystemId()), base.id)
                >= MAX_POLICIES_PER_STATION) {
            world.status = "Production policy limit reached.";
            return false;
        }
        String error = validateSpec(world, playerId, base, spec);
        if (!error.isBlank()) {
            world.status = error;
            return false;
        }
        ProductionPolicy policy = fromSpec("PP" + state.nextPolicyId++, playerId,
                clean(world.activeSystemId()), base.id, spec);
        state.policies.put(policy.id, policy);
        world.status = "Created production policy " + policy.id + ".";
        return true;
    }

    private static boolean updateDefinition(World world, RuntimeState state, String playerId,
                                            Base base, String encoded) {
        PolicySpec spec = parseSpec(encoded);
        if (spec == null || spec.policyId.isBlank()) return false;
        ProductionPolicy existing = state.policies.get(spec.policyId);
        if (!owns(existing, playerId, clean(world.activeSystemId()), base.id)) return false;
        String error = validateSpec(world, playerId, base, spec);
        if (!error.isBlank()) {
            world.status = error;
            return false;
        }
        existing.type = spec.type;
        existing.kind = spec.kind;
        existing.itemId = spec.itemId;
        existing.loadoutId = spec.loadoutId;
        existing.targetAmount = spec.targetAmount;
        existing.batchSize = spec.batchSize;
        existing.priority = spec.priority;
        existing.maxOutstandingJobs = spec.maxOutstandingJobs;
        existing.repeatLimit = spec.repeatLimit;
        existing.stationReserve.clear();
        existing.stationReserve.putAll(spec.stationReserve);
        existing.networkReserve.clear();
        existing.networkReserve.putAll(spec.networkReserve);
        existing.reason = "";
        world.status = "Updated production policy " + existing.id + ".";
        return true;
    }

    private static boolean toggle(RuntimeState state, String playerId, Base base, String payload) {
        String[] parts = payload.split("~", -1);
        if (parts.length != 2) return false;
        ProductionPolicy policy = state.policies.get(clean(parts[0]));
        if (!owns(policy, playerId, cleanSystem(base), base.id)) return false;
        boolean enabled = "1".equals(parts[1]) || "true".equalsIgnoreCase(parts[1])
                || "resume".equalsIgnoreCase(parts[1]);
        policy.enabled = enabled;
        policy.status = enabled ? PolicyStatus.WAITING_FOR_RESOURCES : PolicyStatus.PAUSED;
        policy.reason = enabled ? "" : "paused by player";
        return true;
    }

    private static boolean delete(RuntimeState state, String playerId, Base base, String policyId) {
        ProductionPolicy policy = state.policies.get(clean(policyId));
        if (!owns(policy, playerId, cleanSystem(base), base.id)) return false;
        state.policies.remove(policy.id);
        state.jobPolicies.entrySet().removeIf(entry -> policy.id.equals(entry.getValue()));
        return true;
    }

    private static boolean move(RuntimeState state, String playerId, Base base, String policyId, int delta) {
        ProductionPolicy policy = state.policies.get(clean(policyId));
        if (policy == null || !playerId.equals(policy.ownerId) || !base.id.equals(policy.stationId)) return false;
        List<ProductionPolicy> ordered = stationPolicies(state, policy.ownerId, policy.systemId, policy.stationId);
        int index = ordered.indexOf(policy);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= ordered.size()) return false;
        Collections.swap(ordered, index, target);
        int next = 100;
        for (ProductionPolicy item : ordered) {
            item.priority = Math.max(0, next);
            next -= Math.max(1, 100 / Math.max(1, ordered.size()));
        }
        return true;
    }

    private static boolean saveTemplate(World world, RuntimeState state, String playerId,
                                        Base base, String requestedName) {
        if (countPlayerTemplates(state, playerId) >= MAX_TEMPLATES_PER_PLAYER) {
            world.status = "Production template limit reached.";
            return false;
        }
        String name = cleanName(requestedName);
        if (name.isBlank()) return false;
        List<ProductionPolicy> policies = stationPolicies(state, playerId, clean(world.activeSystemId()), base.id);
        if (policies.isEmpty() || policies.size() > MAX_TEMPLATE_ENTRIES) {
            world.status = "This station has no production policies to save.";
            return false;
        }
        List<PolicySpec> entries = new ArrayList<>();
        for (ProductionPolicy policy : policies) entries.add(specOf(policy, ""));
        ProductionTemplate template = new ProductionTemplate("PT" + state.nextTemplateId++, playerId,
                name, List.copyOf(entries));
        state.templates.put(template.id, template);
        world.status = "Saved production template " + name + ".";
        return true;
    }

    private static boolean applyTemplate(World world, RuntimeState state, String playerId,
                                         Base base, String templateId) {
        ProductionTemplate template = state.templates.get(clean(templateId));
        if (template == null || !playerId.equals(template.ownerId) || template.entries.isEmpty()) return false;
        if (template.entries.size() > MAX_TEMPLATE_ENTRIES
                || countPlayerPolicies(state, playerId) + template.entries.size() > MAX_POLICIES_PER_PLAYER
                || countStationPolicies(state, playerId, clean(world.activeSystemId()), base.id)
                + template.entries.size() > MAX_POLICIES_PER_STATION) {
            world.status = "Applying that template would exceed the production policy limit.";
            return false;
        }
        for (PolicySpec spec : template.entries) {
            String error = validateSpec(world, playerId, base, spec);
            if (!error.isBlank()) {
                world.status = "Template cannot be applied: " + error;
                return false;
            }
        }
        for (PolicySpec spec : template.entries) {
            ProductionPolicy policy = fromSpec("PP" + state.nextPolicyId++, playerId,
                    clean(world.activeSystemId()), base.id, spec);
            state.policies.put(policy.id, policy);
        }
        world.status = "Applied production template " + template.name + ".";
        return true;
    }

    private static boolean deleteTemplate(RuntimeState state, String playerId, String templateId) {
        ProductionTemplate template = state.templates.get(clean(templateId));
        if (template == null || !playerId.equals(template.ownerId)) return false;
        state.templates.remove(template.id);
        return true;
    }

    private static int evaluate(World world, RuntimeState state, ProductionPolicy policy,
                                SupplyLedger ledger, int enqueueBudget) {
        Base station = world.bases.get(policy.stationId);
        if (station == null || station.hp <= 0 || !policy.ownerId.equals(station.playerId)) {
            policy.status = PolicyStatus.ORPHANED;
            policy.reason = "assigned station is unavailable";
            return 0;
        }
        if (!policy.enabled) {
            policy.status = PolicyStatus.PAUSED;
            if (policy.reason.isBlank()) policy.reason = "paused";
            return 0;
        }
        if (!compatible(station, policy.kind, policy.itemId)) {
            policy.status = PolicyStatus.NO_COMPATIBLE_STATION;
            policy.reason = "station no longer supports this item";
            return 0;
        }
        String research = missingResearch(world, policy, station);
        if (!research.isBlank() && !world.devFreeBuildFor(policy.ownerId)) {
            policy.status = PolicyStatus.BLOCKED_RESEARCH;
            policy.reason = research;
            return 0;
        }

        int outstanding = linkedJobCount(state, policy.id);
        int capacity = Math.max(0, policy.maxOutstandingJobs - outstanding);
        int desiredJobs;
        if (policy.type == PolicyType.MAINTAIN_STOCK) {
            double supply = ledger.stockSupply(policy, station);
            double deficit = policy.targetAmount - supply;
            if (deficit <= EPSILON) {
                policy.status = PolicyStatus.SATISFIED;
                policy.reason = "stock target satisfied (" + compact(supply) + '/' + compact(policy.targetAmount) + ')';
                return 0;
            }
            double output = outputPerJob(policy);
            desiredJobs = Math.max(1, (int)Math.ceil(deficit / Math.max(EPSILON, output)));
        } else if (policy.type == PolicyType.MAINTAIN_FLEET) {
            double supply = ledger.fleetSupply(policy.ownerId, policy.itemId);
            double deficit = policy.targetAmount - supply;
            if (deficit <= EPSILON) {
                policy.status = PolicyStatus.SATISFIED;
                policy.reason = "fleet target satisfied (" + compact(supply) + '/' + compact(policy.targetAmount) + ')';
                return 0;
            }
            desiredJobs = Math.max(1, (int)Math.ceil(deficit));
        } else {
            if (policy.repeatLimit > 0 && policy.completedBatches >= policy.repeatLimit) {
                policy.status = PolicyStatus.SATISFIED;
                policy.reason = "repeat limit reached";
                return 0;
            }
            if (outstanding > 0) {
                policy.status = jobsWaiting(world, state, policy) ? PolicyStatus.WAITING_FOR_RESOURCES : PolicyStatus.PRODUCING;
                policy.reason = "repeat job active";
                return 0;
            }
            int remaining = policy.repeatLimit <= 0 ? policy.batchSize
                    : Math.max(0, policy.repeatLimit - policy.completedBatches);
            desiredJobs = Math.min(policy.batchSize, remaining);
        }

        int jobs = Math.min(Math.min(desiredJobs, policy.batchSize), capacity);
        if (jobs <= 0 || enqueueBudget <= 0) {
            policy.status = jobsWaiting(world, state, policy) ? PolicyStatus.WAITING_FOR_RESOURCES : PolicyStatus.PRODUCING;
            policy.reason = outstanding > 0 ? "waiting for existing policy work" : "outstanding-job limit reached";
            return 0;
        }
        jobs = Math.min(jobs, enqueueBudget);
        int queued = 0;
        for (int i = 0; i < jobs; i++) {
            QueueResult result = queueOne(world, state, policy, station);
            if (!result.queued) {
                policy.status = result.status;
                policy.reason = result.reason;
                break;
            }
            queued++;
            ledger.noteQueued(policy, station);
        }
        if (queued > 0) {
            policy.status = jobsWaiting(world, state, policy) ? PolicyStatus.WAITING_FOR_RESOURCES : PolicyStatus.PRODUCING;
            policy.reason = queued == 1 ? "queued 1 policy job" : "queued " + queued + " policy jobs";
        }
        return queued;
    }

    private static QueueResult queueOne(World world, RuntimeState state, ProductionPolicy policy, Base station) {
        List<Cost> cost = policyCost(world, policy);
        boolean free = world.devFreeBuildFor(policy.ownerId);
        boolean reservedPolicy = !policy.stationReserve.isEmpty() || !policy.networkReserve.isEmpty();
        if (!free && reservedPolicy) {
            ReserveDecision decision = reserveDecision(world, policy, station, cost);
            if (!decision.allowed) return new QueueResult(false, decision.status, decision.reason);
        }

        Set<String> before = new HashSet<>();
        for (ProductionJob job : station.productionQueue) before.add(job.id);
        boolean accepted;
        if (policy.kind == ProductionJobKind.SHIP) {
            ShipType ship = Rules.findShip(policy.itemId);
            ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, policy.itemId, policy.loadoutId);
            if (ship == null || loadout == null) return new QueueResult(false, PolicyStatus.NO_COMPATIBLE_STATION, "invalid ship/loadout");
            accepted = reservedPolicy
                    ? ProductionSystem.enqueueShip(world, station, ship, loadout, free)
                    : world.buildShip(station.id, loadout.id());
        } else if (policy.kind == ProductionJobKind.CRAFTABLE) {
            CraftableItem item = CraftingRules.item(policy.itemId);
            if (item == null) return new QueueResult(false, PolicyStatus.NO_COMPATIBLE_STATION, "invalid craftable");
            accepted = reservedPolicy
                    ? ProductionSystem.enqueueCraftable(world, station, item, free)
                    : world.craftItem(station.id, item.id);
        } else {
            return new QueueResult(false, PolicyStatus.NO_COMPATIBLE_STATION, "unsupported policy production kind");
        }
        if (!accepted) {
            String reason = world.status == null || world.status.isBlank() ? "production request rejected" : world.status;
            PolicyStatus status = reason.toLowerCase(Locale.ROOT).contains("research")
                    ? PolicyStatus.BLOCKED_RESEARCH : PolicyStatus.WAITING_FOR_RESOURCES;
            return new QueueResult(false, status, boundedReason(reason));
        }
        ProductionJob created = null;
        for (ProductionJob job : station.productionQueue) {
            if (!before.contains(job.id)) created = job;
        }
        if (created == null) return new QueueResult(false, PolicyStatus.WAITING_FOR_RESOURCES,
                "production request created no queue job");
        state.jobPolicies.put(new JobKey(clean(world.activeSystemId()), station.id, created.id), policy.id);
        return new QueueResult(true,
                ProductionSystem.waitingForResources(created) ? PolicyStatus.WAITING_FOR_RESOURCES : PolicyStatus.PRODUCING,
                ProductionSystem.waitingForResources(created) ? "waiting for resources" : "producing");
    }

    private static ReserveDecision reserveDecision(World world, ProductionPolicy policy, Base station, List<Cost> cost) {
        if (cost.isEmpty()) return ReserveDecision.permit();
        for (Cost need : cost) {
            double local = station.inventory.getOrDefault(need.material(), 0.0);
            if (local + EPSILON < need.amount()) {
                return new ReserveDecision(false, PolicyStatus.WAITING_FOR_RESOURCES,
                        "needs " + compact(need.amount()) + ' ' + need.material().label + " in the assigned station");
            }
            double stationFloor = policy.stationReserve.getOrDefault(need.material(), 0.0);
            if (local - need.amount() + EPSILON < stationFloor) {
                return new ReserveDecision(false, PolicyStatus.RESERVE_PROTECTED,
                        need.material().label + " station reserve protected at " + compact(stationFloor));
            }
            double networkFloor = policy.networkReserve.getOrDefault(need.material(), 0.0);
            if (networkFloor > EPSILON) {
                double network = 0;
                for (Base base : world.bases.values()) {
                    if (base.hp > 0 && policy.ownerId.equals(base.playerId)) {
                        network += base.inventory.getOrDefault(need.material(), 0.0);
                    }
                }
                if (network - need.amount() + EPSILON < networkFloor) {
                    return new ReserveDecision(false, PolicyStatus.RESERVE_PROTECTED,
                            need.material().label + " network reserve protected at " + compact(networkFloor));
                }
            }
        }
        return ReserveDecision.permit();
    }

    private static String missingResearch(World world, ProductionPolicy policy, Base station) {
        if (policy.kind == ProductionJobKind.SHIP) {
            ShipType ship = Rules.findShip(policy.itemId);
            ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, policy.itemId, policy.loadoutId);
            if (ship == null || loadout == null) return "unknown ship or loadout";
            if (!ResearchRules.shipUnlocked(world, policy.ownerId, ship.id)) {
                ResearchTopic topic = ResearchRules.firstTopicUnlockingShip(ship.id);
                return topic == null ? "ship research required" : topic.name + " required";
            }
            if (!WeaponRules.unlocked(world, policy.ownerId, loadout)) {
                return WeaponRules.missingResearchLabel(world, policy.ownerId, loadout) + " required";
            }
            return "";
        }
        CraftableItem item = CraftingRules.item(policy.itemId);
        if (item == null) return "unknown craftable";
        return item.unlockedFor(world, policy.ownerId) ? "" : item.missingResearchLabel(world, policy.ownerId) + " required";
    }

    private static boolean compatible(Base base, ProductionJobKind kind, String itemId) {
        if (base == null || kind == null || itemId == null) return false;
        return switch (kind) {
            case SHIP -> base.type().buildableShips.contains(itemId);
            case CRAFTABLE -> {
                CraftableItem item = CraftingRules.item(itemId);
                yield item != null && item.canCraftAt(base.typeId);
            }
            default -> false;
        };
    }

    private static List<Cost> policyCost(World world, ProductionPolicy policy) {
        if (policy.kind == ProductionJobKind.SHIP) {
            ShipType ship = Rules.findShip(policy.itemId);
            ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, policy.itemId, policy.loadoutId);
            return ship == null || loadout == null ? List.of() : WeaponRules.buildCost(ship, loadout);
        }
        CraftableItem item = CraftingRules.item(policy.itemId);
        return item == null ? List.of() : item.requiredResources;
    }

    private static double outputPerJob(ProductionPolicy policy) {
        if (policy.kind == ProductionJobKind.SHIP) return 1;
        CraftableItem item = CraftingRules.item(policy.itemId);
        return item == null ? 0 : item.outputAmount;
    }

    private static boolean jobsWaiting(World world, RuntimeState state, ProductionPolicy policy) {
        if (world == null) return false;
        for (Map.Entry<JobKey,String> entry : state.jobPolicies.entrySet()) {
            if (!policy.id.equals(entry.getValue()) || !policy.systemId.equals(entry.getKey().systemId())) continue;
            Base base = world.bases.get(entry.getKey().baseId());
            ProductionJob job = base == null ? null : ProductionSystem.findJob(base, entry.getKey().jobId());
            if (ProductionSystem.waitingForResources(job)) return true;
        }
        return false;
    }

    private static void reconcileFinishedLinks(World world, RuntimeState state, String systemId) {
        List<JobKey> remove = new ArrayList<>();
        for (Map.Entry<JobKey,String> entry : state.jobPolicies.entrySet()) {
            JobKey key = entry.getKey();
            if (!systemId.equals(key.systemId())) continue;
            Base base = world.bases.get(key.baseId());
            if (base == null) {
                remove.add(key);
                continue;
            }
            if (ProductionSystem.findJob(base, key.jobId()) != null) continue;
            ProductionPolicy policy = state.policies.get(entry.getValue());
            if (policy != null && policy.type == PolicyType.REPEAT) policy.completedBatches++;
            remove.add(key);
        }
        for (JobKey key : remove) state.jobPolicies.remove(key);
    }

    private static int linkedJobCount(RuntimeState state, String policyId) {
        int count = 0;
        for (String value : state.jobPolicies.values()) if (policyId.equals(value)) count++;
        return count;
    }

    private static String validateSpec(World world, String playerId, Base base, PolicySpec spec) {
        if (spec == null || spec.type == null || spec.kind == null || spec.itemId.isBlank()) return "Invalid production policy.";
        if (spec.kind != ProductionJobKind.SHIP && spec.kind != ProductionJobKind.CRAFTABLE) {
            return "Production policies currently support ships and manufactured items.";
        }
        if (spec.kind == ProductionJobKind.SHIP && spec.type == PolicyType.MAINTAIN_STOCK) {
            return "Ship policies use Maintain fleet or Repeat.";
        }
        if (spec.kind == ProductionJobKind.CRAFTABLE && spec.type == PolicyType.MAINTAIN_FLEET) {
            return "Manufactured-item policies use Maintain stock or Repeat.";
        }
        if (!compatible(base, spec.kind, spec.itemId)) return base.type().name + " cannot produce " + spec.itemId + ".";
        if (spec.kind == ProductionJobKind.SHIP) {
            ShipLoadoutDefinition loadout = WeaponRules.resolveForHull(world, spec.itemId, spec.loadoutId);
            if (loadout == null || !spec.itemId.equals(loadout.hullId())) return "Unknown or mismatched ship loadout.";
        }
        if (!Double.isFinite(spec.targetAmount) || spec.targetAmount < 0 || spec.targetAmount > MAX_TARGET
                || spec.type != PolicyType.REPEAT && spec.targetAmount <= 0) return "Policy target is out of range.";
        if (spec.batchSize < 1 || spec.batchSize > MAX_BATCH_SIZE
                || spec.priority < 0 || spec.priority > 100
                || spec.maxOutstandingJobs < 1 || spec.maxOutstandingJobs > MAX_OUTSTANDING_PER_POLICY
                || spec.repeatLimit < 0 || spec.repeatLimit > MAX_REPEAT_LIMIT) return "Policy numeric settings are out of range.";
        if (!validReserveMap(spec.stationReserve) || !validReserveMap(spec.networkReserve)) return "Policy reserve settings are invalid.";
        return "";
    }

    private static boolean validReserveMap(EnumMap<Material,Double> reserves) {
        if (reserves == null || reserves.size() > Material.values().length) return false;
        for (double value : reserves.values()) if (!Double.isFinite(value) || value < 0 || value > MAX_TARGET) return false;
        return true;
    }

    private static PolicySpec parseSpec(String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length() > MAX_COMMAND_CHARS) return null;
        String[] parts = encoded.split("~", -1);
        if (parts.length != 13 || !"v1".equals(parts[0])) return null;
        try {
            String policyId = clean(parts[1]);
            PolicyType type = PolicyType.valueOf(parts[2]);
            ProductionJobKind kind = ProductionJobKind.valueOf(parts[3]);
            String itemId = clean(parts[4]);
            String loadoutId = clean(parts[5]);
            double target = Double.parseDouble(parts[6]);
            int batch = Integer.parseInt(parts[7]);
            int priority = Integer.parseInt(parts[8]);
            int maxOutstanding = Integer.parseInt(parts[9]);
            int repeatLimit = Integer.parseInt(parts[10]);
            EnumMap<Material,Double> stationReserve = parseReserve(parts[11]);
            EnumMap<Material,Double> networkReserve = parseReserve(parts[12]);
            if (stationReserve == null || networkReserve == null) return null;
            return new PolicySpec(policyId, type, kind, itemId, loadoutId, target, batch, priority,
                    maxOutstanding, repeatLimit, stationReserve, networkReserve);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static EnumMap<Material,Double> parseReserve(String text) {
        EnumMap<Material,Double> out = new EnumMap<>(Material.class);
        if (text == null || text.isBlank() || "-".equals(text)) return out;
        for (String token : text.split(",")) {
            String[] pair = token.split(":", 2);
            if (pair.length != 2) return null;
            try {
                Material material = Material.valueOf(pair[0].trim().toUpperCase(Locale.ROOT));
                double amount = Double.parseDouble(pair[1]);
                if (!Double.isFinite(amount) || amount < 0 || amount > MAX_TARGET) return null;
                if (amount > EPSILON) out.put(material, amount);
            } catch (RuntimeException ex) {
                return null;
            }
        }
        return out;
    }

    private static String reserveText(Map<Material,Double> reserves) {
        if (reserves == null || reserves.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        for (Material material : Material.values()) {
            double amount = reserves.getOrDefault(material, 0.0);
            if (amount > EPSILON) parts.add(material.name() + ':' + amount);
        }
        return parts.isEmpty() ? "-" : String.join(",", parts);
    }

    private static ProductionPolicy fromSpec(String id, String playerId, String systemId,
                                             String stationId, PolicySpec spec) {
        ProductionPolicy policy = new ProductionPolicy(id, playerId, systemId, stationId,
                spec.type, spec.kind, spec.itemId, spec.loadoutId, spec.targetAmount,
                spec.batchSize, spec.priority, spec.maxOutstandingJobs, spec.repeatLimit);
        policy.stationReserve.putAll(spec.stationReserve);
        policy.networkReserve.putAll(spec.networkReserve);
        return policy;
    }

    private static PolicySpec specOf(ProductionPolicy policy, String policyId) {
        return new PolicySpec(policyId, policy.type, policy.kind, policy.itemId, policy.loadoutId,
                policy.targetAmount, policy.batchSize, policy.priority, policy.maxOutstandingJobs,
                policy.repeatLimit, new EnumMap<>(policy.stationReserve), new EnumMap<>(policy.networkReserve));
    }

    private static Map<String,Object> capturePolicy(ProductionPolicy policy) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", policy.id);
        row.put("ownerId", policy.ownerId);
        row.put("systemId", policy.systemId);
        row.put("stationId", policy.stationId);
        row.put("type", policy.type.name());
        row.put("kind", policy.kind.name());
        row.put("itemId", policy.itemId);
        row.put("loadoutId", policy.loadoutId);
        row.put("targetAmount", policy.targetAmount);
        row.put("batchSize", policy.batchSize);
        row.put("priority", policy.priority);
        row.put("maxOutstandingJobs", policy.maxOutstandingJobs);
        row.put("repeatLimit", policy.repeatLimit);
        row.put("completedBatches", policy.completedBatches);
        row.put("enabled", policy.enabled);
        row.put("status", policy.status.name());
        row.put("reason", boundedReason(policy.reason));
        row.put("stationReserve", ServerSaveStore.materialMap(policy.stationReserve));
        row.put("networkReserve", ServerSaveStore.materialMap(policy.networkReserve));
        return row;
    }

    private static ProductionPolicy restorePolicy(Map<String,Object> row) {
        String id = ServerSaveStore.string(row, "id", "");
        String ownerId = ServerSaveStore.string(row, "ownerId", "");
        String systemId = ServerSaveStore.string(row, "systemId", "");
        String stationId = ServerSaveStore.string(row, "stationId", "");
        PolicyType type = ServerSaveStore.enumValue(PolicyType.class, row.get("type"), null);
        ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, row.get("kind"), null);
        String itemId = ServerSaveStore.string(row, "itemId", "");
        if (!validToken(id, 64) || !validToken(ownerId, 64) || !validToken(systemId, 128)
                || !validToken(stationId, 128) || type == null || kind == null || !knownItem(kind, itemId)) return null;
        ProductionPolicy policy = new ProductionPolicy(id, ownerId, systemId, stationId, type, kind, itemId,
                ServerSaveStore.string(row, "loadoutId", ""),
                boundedNumber(ServerSaveStore.doubleValue(row, "targetAmount", 1), 0, MAX_TARGET),
                boundedInt(ServerSaveStore.intValue(row, "batchSize", 1), 1, MAX_BATCH_SIZE),
                boundedInt(ServerSaveStore.intValue(row, "priority", 50), 0, 100),
                boundedInt(ServerSaveStore.intValue(row, "maxOutstandingJobs", 1), 1, MAX_OUTSTANDING_PER_POLICY),
                boundedInt(ServerSaveStore.intValue(row, "repeatLimit", 0), 0, MAX_REPEAT_LIMIT));
        policy.completedBatches = boundedInt(ServerSaveStore.intValue(row, "completedBatches", 0), 0, MAX_REPEAT_LIMIT);
        policy.enabled = ServerSaveStore.boolValue(row, "enabled", true);
        policy.status = ServerSaveStore.enumValue(PolicyStatus.class, row.get("status"), PolicyStatus.PAUSED);
        policy.reason = boundedReason(ServerSaveStore.string(row, "reason", ""));
        policy.stationReserve.putAll(ServerSaveStore.restoreMaterialMap(row.get("stationReserve")));
        policy.networkReserve.putAll(ServerSaveStore.restoreMaterialMap(row.get("networkReserve")));
        if (!validReserveMap(policy.stationReserve) || !validReserveMap(policy.networkReserve)) return null;
        return policy;
    }

    private static Map<String,Object> captureTemplate(ProductionTemplate template) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", template.id);
        row.put("ownerId", template.ownerId);
        row.put("name", template.name);
        List<Object> entries = new ArrayList<>();
        for (PolicySpec spec : template.entries) {
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("type", spec.type.name());
            item.put("kind", spec.kind.name());
            item.put("itemId", spec.itemId);
            item.put("loadoutId", spec.loadoutId);
            item.put("targetAmount", spec.targetAmount);
            item.put("batchSize", spec.batchSize);
            item.put("priority", spec.priority);
            item.put("maxOutstandingJobs", spec.maxOutstandingJobs);
            item.put("repeatLimit", spec.repeatLimit);
            item.put("stationReserve", ServerSaveStore.materialMap(spec.stationReserve));
            item.put("networkReserve", ServerSaveStore.materialMap(spec.networkReserve));
            entries.add(item);
        }
        row.put("entries", entries);
        return row;
    }

    private static ProductionTemplate restoreTemplate(Map<String,Object> row) {
        String id = ServerSaveStore.string(row, "id", "");
        String ownerId = ServerSaveStore.string(row, "ownerId", "");
        String name = cleanName(ServerSaveStore.string(row, "name", ""));
        if (!validToken(id, 64) || !validToken(ownerId, 64) || name.isBlank()) return null;
        List<Object> savedEntries = ServerSaveStore.list(row.get("entries"));
        if (savedEntries.isEmpty() || savedEntries.size() > MAX_TEMPLATE_ENTRIES) return null;
        List<PolicySpec> entries = new ArrayList<>();
        for (Object item : savedEntries) {
            Map<String,Object> data = ServerSaveStore.object(item);
            PolicyType type = ServerSaveStore.enumValue(PolicyType.class, data.get("type"), null);
            ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, data.get("kind"), null);
            String itemId = ServerSaveStore.string(data, "itemId", "");
            if (type == null || kind == null || !knownItem(kind, itemId)) return null;
            EnumMap<Material,Double> stationReserve = new EnumMap<>(Material.class);
            stationReserve.putAll(ServerSaveStore.restoreMaterialMap(data.get("stationReserve")));
            EnumMap<Material,Double> networkReserve = new EnumMap<>(Material.class);
            networkReserve.putAll(ServerSaveStore.restoreMaterialMap(data.get("networkReserve")));
            PolicySpec spec = new PolicySpec("", type, kind, itemId,
                    ServerSaveStore.string(data, "loadoutId", ""),
                    boundedNumber(ServerSaveStore.doubleValue(data, "targetAmount", 1), 0, MAX_TARGET),
                    boundedInt(ServerSaveStore.intValue(data, "batchSize", 1), 1, MAX_BATCH_SIZE),
                    boundedInt(ServerSaveStore.intValue(data, "priority", 50), 0, 100),
                    boundedInt(ServerSaveStore.intValue(data, "maxOutstandingJobs", 1), 1, MAX_OUTSTANDING_PER_POLICY),
                    boundedInt(ServerSaveStore.intValue(data, "repeatLimit", 0), 0, MAX_REPEAT_LIMIT),
                    stationReserve, networkReserve);
            if (!validReserveMap(stationReserve) || !validReserveMap(networkReserve)) return null;
            entries.add(spec);
        }
        return new ProductionTemplate(id, ownerId, name, List.copyOf(entries));
    }

    private static boolean knownItem(ProductionJobKind kind, String itemId) {
        if (kind == ProductionJobKind.SHIP) return Rules.findShip(itemId) != null;
        if (kind == ProductionJobKind.CRAFTABLE) return CraftingRules.item(itemId) != null;
        return false;
    }

    private static Map<String,Object> captureSpec(PolicySpec spec) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("type", spec.type.name());
        return row;
    }

    private static PolicyView view(RuntimeState state, ProductionPolicy policy) {
        List<String> jobs = new ArrayList<>();
        for (Map.Entry<JobKey,String> entry : state.jobPolicies.entrySet()) {
            if (policy.id.equals(entry.getValue()) && policy.systemId.equals(entry.getKey().systemId())
                    && policy.stationId.equals(entry.getKey().baseId())) jobs.add(entry.getKey().jobId());
        }
        jobs.sort(String::compareTo);
        return new PolicyView(policy.id, policy.type, policy.kind, policy.itemId, policy.loadoutId,
                policy.targetAmount, policy.batchSize, policy.priority, policy.maxOutstandingJobs,
                policy.repeatLimit, policy.completedBatches, policy.enabled, policy.status,
                policy.reason, List.copyOf(jobs));
    }

    private static void refreshCurrentSystem(World world, RuntimeState state) {
        if (world == null || state == null) return;
        String systemId = clean(world.activeSystemId());
        for (Base base : world.bases.values()) {
            String existing = stripOwnStatus(base.logisticsStatus);
            List<ProductionPolicy> policies = stationPolicies(state, base.playerId, systemId, base.id);
            List<ProductionTemplate> templates = playerTemplates(state, base.playerId);
            StringBuilder extra = new StringBuilder();
            if (!policies.isEmpty()) {
                extra.append(POLICY_MARKER);
                for (int i = 0; i < policies.size(); i++) {
                    if (i > 0) extra.append(';');
                    extra.append(statusRow(state, policies.get(i)));
                }
            }
            if (!templates.isEmpty()) {
                if (!extra.isEmpty()) extra.append(" | ");
                extra.append(TEMPLATE_MARKER);
                for (int i = 0; i < templates.size(); i++) {
                    if (i > 0) extra.append(';');
                    ProductionTemplate template = templates.get(i);
                    extra.append(token(template.id)).append('~').append(token(template.name))
                            .append('~').append(template.entries.size());
                }
            }
            base.logisticsStatus = existing.isBlank() ? extra.toString()
                    : extra.isEmpty() ? existing : existing + " | " + extra;
        }
    }

    private static String statusRow(RuntimeState state, ProductionPolicy policy) {
        PolicyView view = view(state, policy);
        return token(view.id()) + '~' + view.type().name() + '~' + view.kind().name() + '~'
                + token(view.itemId()) + '~' + token(view.loadoutId()) + '~' + view.targetAmount() + '~'
                + view.batchSize() + '~' + view.priority() + '~' + view.maxOutstandingJobs() + '~'
                + view.repeatLimit() + '~' + view.completedBatches() + '~' + (view.enabled() ? '1' : '0') + '~'
                + view.status().name() + '~' + token(view.reason()) + '~' + String.join(",", view.jobIds());
    }

    private static List<PolicyView> parseStatusViews(String status) {
        String section = section(status, POLICY_MARKER);
        if (section.isBlank()) return List.of();
        List<PolicyView> out = new ArrayList<>();
        for (String row : section.split(";")) {
            String[] c = row.split("~", -1);
            if (c.length != 15) continue;
            try {
                List<String> jobs = new ArrayList<>();
                if (!c[14].isBlank() && !"-".equals(c[14])) {
                    for (String job : c[14].split(",")) if (validToken(job, 64)) jobs.add(job);
                }
                out.add(new PolicyView(c[0], PolicyType.valueOf(c[1]), ProductionJobKind.valueOf(c[2]),
                        untoken(c[3]), untoken(c[4]), Double.parseDouble(c[5]), Integer.parseInt(c[6]),
                        Integer.parseInt(c[7]), Integer.parseInt(c[8]), Integer.parseInt(c[9]),
                        Integer.parseInt(c[10]), "1".equals(c[11]), PolicyStatus.valueOf(c[12]),
                        untoken(c[13]), List.copyOf(jobs)));
            } catch (RuntimeException ignored) { }
        }
        out.sort(Comparator.comparingInt(PolicyView::priority).reversed().thenComparing(PolicyView::id));
        return List.copyOf(out);
    }

    private static List<TemplateView> parseTemplateViews(String status) {
        String section = section(status, TEMPLATE_MARKER);
        if (section.isBlank()) return List.of();
        List<TemplateView> out = new ArrayList<>();
        for (String row : section.split(";")) {
            String[] c = row.split("~", -1);
            if (c.length != 3) continue;
            try { out.add(new TemplateView(c[0], untoken(c[1]), Integer.parseInt(c[2]))); }
            catch (RuntimeException ignored) { }
        }
        out.sort(Comparator.comparing(TemplateView::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static String section(String status, String marker) {
        if (status == null || status.isBlank()) return "";
        int start = status.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = status.indexOf(" | ", start);
        return (end < 0 ? status.substring(start) : status.substring(start, end)).trim();
    }

    private static String stripOwnStatus(String status) {
        String value = status == null ? "" : status;
        int a = value.indexOf(POLICY_MARKER);
        int b = value.indexOf(TEMPLATE_MARKER);
        int cut = a < 0 ? b : b < 0 ? a : Math.min(a, b);
        if (cut < 0) return value.trim();
        String head = value.substring(0, cut).trim();
        while (head.endsWith("|") || head.endsWith(";")) head = head.substring(0, head.length() - 1).trim();
        return head;
    }

    private static List<ProductionPolicy> stationPolicies(RuntimeState state, String ownerId,
                                                          String systemId, String stationId) {
        List<ProductionPolicy> out = new ArrayList<>();
        for (ProductionPolicy policy : state.policies.values()) {
            if (ownerId.equals(policy.ownerId) && systemId.equals(policy.systemId)
                    && stationId.equals(policy.stationId)) out.add(policy);
        }
        out.sort(Comparator.comparingInt((ProductionPolicy policy) -> policy.priority).reversed()
                .thenComparing(policy -> policy.id));
        return out;
    }

    private static List<ProductionTemplate> playerTemplates(RuntimeState state, String ownerId) {
        List<ProductionTemplate> out = new ArrayList<>();
        for (ProductionTemplate template : state.templates.values()) if (ownerId.equals(template.ownerId)) out.add(template);
        out.sort(Comparator.comparing((ProductionTemplate template) -> template.name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(template -> template.id));
        return out;
    }

    private static int countPlayerPolicies(RuntimeState state, String ownerId) {
        int count = 0;
        for (ProductionPolicy policy : state.policies.values()) if (ownerId.equals(policy.ownerId)) count++;
        return count;
    }

    private static int countStationPolicies(RuntimeState state, String ownerId, String systemId, String stationId) {
        return stationPolicies(state, ownerId, systemId, stationId).size();
    }

    private static int countPlayerTemplates(RuntimeState state, String ownerId) {
        int count = 0;
        for (ProductionTemplate template : state.templates.values()) if (ownerId.equals(template.ownerId)) count++;
        return count;
    }

    private static boolean owns(ProductionPolicy policy, String ownerId, String systemId, String baseId) {
        return policy != null && ownerId.equals(policy.ownerId) && systemId.equals(policy.systemId)
                && baseId.equals(policy.stationId);
    }

    private static RuntimeState state(World world) {
        return STATES.computeIfAbsent(world, ignored -> new RuntimeState());
    }

    private static String cleanSystem(Base base) {
        World world = PlayerRegistry.activeWorld();
        return world == null ? "" : clean(world.activeSystemId());
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String cleanName(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isBlank()) return "";
        if (text.length() > 64) text = text.substring(0, 64);
        return token(text);
    }

    private static String token(String value) {
        if (value == null || value.isBlank()) return "-";
        StringBuilder out = new StringBuilder(Math.min(value.length(), 256));
        for (int i = 0; i < value.length() && out.length() < 256; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '~' || c == ';' || c == '|' || c == ',' || c == '^') out.append('_');
            else out.append(c);
        }
        return out.toString();
    }

    private static String untoken(String value) { return value == null || "-".equals(value) ? "" : value; }

    private static boolean validToken(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c == '~' || c == ';' || c == '|' || c == ',' || c == '^') return false;
        }
        return true;
    }

    private static double boundedNumber(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    private static int boundedInt(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

    private static String boundedReason(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 256 ? text : text.substring(0, 256);
    }

    private static String compact(double value) {
        double rounded = Math.rint(value * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 0.001) return Long.toString(Math.round(rounded));
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    private static final class RuntimeState {
        long nextPolicyId = 1;
        long nextTemplateId = 1;
        final Map<String,ProductionPolicy> policies = new LinkedHashMap<>();
        final Map<String,ProductionTemplate> templates = new LinkedHashMap<>();
        final Map<JobKey,String> jobPolicies = new LinkedHashMap<>();
        final Map<String,Double> systemTimers = new LinkedHashMap<>();
    }

    private static final class ProductionPolicy {
        final String id;
        final String ownerId;
        final String systemId;
        final String stationId;
        PolicyType type;
        ProductionJobKind kind;
        String itemId;
        String loadoutId;
        double targetAmount;
        int batchSize;
        int priority;
        int maxOutstandingJobs;
        int repeatLimit;
        int completedBatches;
        boolean enabled = true;
        PolicyStatus status = PolicyStatus.WAITING_FOR_RESOURCES;
        String reason = "";
        final EnumMap<Material,Double> stationReserve = new EnumMap<>(Material.class);
        final EnumMap<Material,Double> networkReserve = new EnumMap<>(Material.class);

        ProductionPolicy(String id, String ownerId, String systemId, String stationId,
                         PolicyType type, ProductionJobKind kind, String itemId, String loadoutId,
                         double targetAmount, int batchSize, int priority, int maxOutstandingJobs,
                         int repeatLimit) {
            this.id = id;
            this.ownerId = ownerId;
            this.systemId = systemId;
            this.stationId = stationId;
            this.type = type;
            this.kind = kind;
            this.itemId = itemId;
            this.loadoutId = loadoutId == null ? "" : loadoutId;
            this.targetAmount = targetAmount;
            this.batchSize = batchSize;
            this.priority = priority;
            this.maxOutstandingJobs = maxOutstandingJobs;
            this.repeatLimit = repeatLimit;
        }
    }

    private record ProductionTemplate(String id, String ownerId, String name, List<PolicySpec> entries) { }

    private record PolicySpec(String policyId, PolicyType type, ProductionJobKind kind, String itemId,
                              String loadoutId, double targetAmount, int batchSize, int priority,
                              int maxOutstandingJobs, int repeatLimit,
                              EnumMap<Material,Double> stationReserve,
                              EnumMap<Material,Double> networkReserve) { }

    private record JobKey(String systemId, String baseId, String jobId) { }

    private record QueueResult(boolean queued, PolicyStatus status, String reason) { }

    private record ReserveDecision(boolean allowed, PolicyStatus status, String reason) {
        static ReserveDecision permit() { return new ReserveDecision(true, PolicyStatus.PRODUCING, ""); }
    }

    private static final class SupplyLedger {
        private final Map<String,Integer> livingShips = new LinkedHashMap<>();
        private final Map<String,Integer> queuedShips = new LinkedHashMap<>();
        private final Map<String,Double> queuedMaterials = new LinkedHashMap<>();
        private final Map<String,Double> inboundMaterials = new LinkedHashMap<>();

        static SupplyLedger capture(World world) {
            SupplyLedger ledger = new SupplyLedger();
            Map<String,Destination> routes = routeDestinations(world);
            Map<String,Object> galaxy = world.captureServerSaveGalaxy();
            for (Object systemItem : ServerSaveStore.list(galaxy.get("systems"))) {
                Map<String,Object> system = ServerSaveStore.object(systemItem);
                String systemId = ServerSaveStore.string(system, "systemId", "");
                for (Object unitItem : ServerSaveStore.list(system.get("units"))) {
                    Map<String,Object> unit = ServerSaveStore.object(unitItem);
                    String ownerId = ServerSaveStore.string(unit, "playerId", "");
                    String shipId = ServerSaveStore.string(unit, "shipTypeId", "");
                    if (ServerSaveStore.doubleValue(unit, "hp", 0) > 0 && !ownerId.isBlank() && !shipId.isBlank()) {
                        ledger.livingShips.merge(ownerId + '|' + shipId, 1, Integer::sum);
                    }
                    String targetBase = ServerSaveStore.string(unit, "logisticsTargetBaseId", "");
                    String request = ServerSaveStore.string(unit, "logisticsRequestId", "");
                    if (targetBase.isBlank() || request.startsWith("LR")) continue;
                    Destination destination;
                    if (request.startsWith("ROUTE:")) destination = routes.get(request.substring("ROUTE:".length()));
                    else destination = new Destination(ownerId, systemId, targetBase);
                    if (destination == null || !ownerId.equals(destination.ownerId)) continue;
                    EnumMap<Material,Double> cargo = ServerSaveStore.restoreMaterialMap(unit.get("inventory"));
                    for (Map.Entry<Material,Double> entry : cargo.entrySet()) {
                        if (entry.getValue() != null && entry.getValue() > EPSILON) {
                            ledger.inboundMaterials.merge(destination.key(entry.getKey()), entry.getValue(), Double::sum);
                        }
                    }
                }
                for (Object baseItem : ServerSaveStore.list(system.get("bases"))) {
                    Map<String,Object> base = ServerSaveStore.object(baseItem);
                    String ownerId = ServerSaveStore.string(base, "playerId", "");
                    String baseId = ServerSaveStore.string(base, "id", "");
                    if (ServerSaveStore.doubleValue(base, "hp", 0) <= 0) continue;
                    for (Object jobItem : ServerSaveStore.list(base.get("productionQueue"))) {
                        Map<String,Object> job = ServerSaveStore.object(jobItem);
                        ProductionJobKind kind = ServerSaveStore.enumValue(ProductionJobKind.class, job.get("kind"), null);
                        String itemId = ServerSaveStore.string(job, "itemId", "");
                        if (kind == ProductionJobKind.SHIP && !itemId.isBlank()) {
                            ledger.queuedShips.merge(ownerId + '|' + itemId, 1, Integer::sum);
                        } else if (kind == ProductionJobKind.CRAFTABLE) {
                            CraftableItem item = CraftingRules.item(itemId);
                            if (item != null) {
                                ledger.queuedMaterials.merge(stockKey(ownerId, systemId, baseId, item.outputMaterial),
                                        item.outputAmount, Double::sum);
                            }
                        }
                    }
                }
            }
            return ledger;
        }

        double stockSupply(ProductionPolicy policy, Base station) {
            CraftableItem item = CraftingRules.item(policy.itemId);
            if (item == null) return 0;
            String key = stockKey(policy.ownerId, policy.systemId, station.id, item.outputMaterial);
            return station.inventory.getOrDefault(item.outputMaterial, 0.0)
                    + queuedMaterials.getOrDefault(key, 0.0)
                    + inboundMaterials.getOrDefault(key, 0.0);
        }

        double fleetSupply(String ownerId, String shipId) {
            String key = ownerId + '|' + shipId;
            return livingShips.getOrDefault(key, 0) + queuedShips.getOrDefault(key, 0);
        }

        void noteQueued(ProductionPolicy policy, Base station) {
            if (policy.kind == ProductionJobKind.SHIP) {
                queuedShips.merge(policy.ownerId + '|' + policy.itemId, 1, Integer::sum);
            } else {
                CraftableItem item = CraftingRules.item(policy.itemId);
                if (item != null) queuedMaterials.merge(stockKey(policy.ownerId, policy.systemId, station.id,
                        item.outputMaterial), item.outputAmount, Double::sum);
            }
        }

        private static Map<String,Destination> routeDestinations(World world) {
            Map<String,Destination> out = new LinkedHashMap<>();
            Map<String,Object> saved = LogisticsRouteSystem.capture(world);
            for (Object item : ServerSaveStore.list(saved.get("routes"))) {
                Map<String,Object> row = ServerSaveStore.object(item);
                String id = ServerSaveStore.string(row, "id", "");
                String owner = ServerSaveStore.string(row, "ownerId", "");
                String system = ServerSaveStore.string(row, "destinationSystemId", "");
                String base = ServerSaveStore.string(row, "destinationBaseId", "");
                if (!id.isBlank() && !owner.isBlank() && !system.isBlank() && !base.isBlank()) {
                    out.put(id, new Destination(owner, system, base));
                }
            }
            return out;
        }

        private static String stockKey(String owner, String system, String base, Material material) {
            return owner + '|' + system + '|' + base + '|' + material.name();
        }
    }

    private record Destination(String ownerId, String systemId, String baseId) {
        String key(Material material) { return SupplyLedger.stockKey(ownerId, systemId, baseId, material); }
    }
}