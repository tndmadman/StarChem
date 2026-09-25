package com.tndmadman.rts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fail-fast JSON-backed rules for celestial gameplay and planetary extraction. */
final class CelestialGameplayConfig {
    static final CelestialGameplayConfig INSTANCE = load();

    final double scanSeconds;
    final double analyzeSeconds;
    final double holdSeconds;
    final double extractAmount;
    final double maxTotalBonus;

    final DepositRules deposits;
    final ScanRules scanning;
    final AnchorRules anchoring;
    final ExtractionRules extraction;
    final MoonModifier moonModifier;

    private final Map<String, BodyProfile> bodyProfiles;
    private final Map<CelestialInstallationType, List<String>> installationRoleKeywords;
    private final Map<CelestialBonusKind, String> bonusRequirements;

    private CelestialGameplayConfig(
            double scanSeconds,
            double analyzeSeconds,
            double holdSeconds,
            double extractAmount,
            double maxTotalBonus,
            DepositRules deposits,
            ScanRules scanning,
            AnchorRules anchoring,
            ExtractionRules extraction,
            MoonModifier moonModifier,
            Map<String, BodyProfile> bodyProfiles,
            Map<CelestialInstallationType, List<String>> installationRoleKeywords,
            Map<CelestialBonusKind, String> bonusRequirements) {
        this.scanSeconds = scanSeconds;
        this.analyzeSeconds = analyzeSeconds;
        this.holdSeconds = holdSeconds;
        this.extractAmount = extractAmount;
        this.maxTotalBonus = maxTotalBonus;
        this.deposits = deposits;
        this.scanning = scanning;
        this.anchoring = anchoring;
        this.extraction = extraction;
        this.moonModifier = moonModifier;
        this.bodyProfiles = Map.copyOf(bodyProfiles);
        this.installationRoleKeywords = Map.copyOf(installationRoleKeywords);
        this.bonusRequirements = Map.copyOf(bonusRequirements);
    }

    BodyProfile bodyProfile(String visualClass) {
        String key = visualClass == null || visualClass.isBlank()
                ? "ROCKY" : visualClass.toUpperCase(Locale.ROOT);
        BodyProfile profile = bodyProfiles.get(key);
        if (profile == null) profile = bodyProfiles.get("ROCKY");
        if (profile == null) throw new IllegalStateException("celestial config is missing ROCKY body profile");
        return profile;
    }

    CelestialInstallationType installationType(String stationTypeId) {
        String id = stationTypeId == null ? "" : stationTypeId.toLowerCase(Locale.ROOT);
        for (Map.Entry<CelestialInstallationType, List<String>> entry : installationRoleKeywords.entrySet()) {
            for (String keyword : entry.getValue()) {
                if (!keyword.isBlank() && id.contains(keyword)) return entry.getKey();
            }
        }
        return CelestialInstallationType.EXTRACTOR;
    }

    boolean installationSupports(List<CelestialInstallationType> installations, CelestialBonusKind kind) {
        if (installations == null || installations.isEmpty() || kind == null) return false;
        String requirement = bonusRequirements.get(kind);
        if (requirement == null) throw new IllegalStateException("No celestial bonus requirement configured for " + kind);
        if ("ANY".equals(requirement)) return true;
        CelestialInstallationType required;
        try {
            required = CelestialInstallationType.valueOf(requirement);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid celestial installation requirement '" + requirement + "' for " + kind, ex);
        }
        return installations.contains(required);
    }

    private static CelestialGameplayConfig load() {
        try {
            Path manifestPath = Path.of("config/starchem.json");
            Map<String,Object> manifest = object(MiniJson.parse(Files.readString(manifestPath)));
            Map<String,Object> files = section(manifest, "files");
            String configuredPath = string(files, "celestialGameplay");
            Path path = Path.of(configuredPath);
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("missing celestial gameplay config: " + path);
            }
            Map<String,Object> root = object(MiniJson.parse(Files.readString(path)));

            Map<String,Object> objectives = section(root, "objectives");
            double scanSeconds = positive(objectives, "scanSeconds");
            double analyzeSeconds = positive(objectives, "analyzeSeconds");
            if (analyzeSeconds < scanSeconds) {
                throw new IllegalStateException("objectives.analyzeSeconds must be >= objectives.scanSeconds");
            }
            double holdSeconds = positive(objectives, "holdSeconds");
            double extractAmount = positive(objectives, "extractAmount");
            double maxTotalBonus = nonNegative(section(root, "bonuses"), "maxTotal");

            Map<String,Object> d = section(root, "deposits");
            DepositRules deposits = new DepositRules(
                    positive(d, "baseAmount"),
                    nonNegative(d, "amountPerMaterialSlot"),
                    positive(d, "harvestRate"),
                    positive(d, "nodeRadius"),
                    nonNegative(d, "orbitPadding"),
                    positive(d, "orbitRadiusScale"),
                    nonNegative(d, "orbitSlotSpacing"),
                    nonNegative(d, "orbitBaseSpeed"),
                    nonNegative(d, "orbitSpeedPerSlot"),
                    nonNegative(d, "reattachTolerance"),
                    nonNegative(d, "fallbackOrbitSpeed"));

            Map<String,Object> s = section(root, "scanning");
            ScanRules scanning = new ScanRules(
                    nonNegative(s, "unitRangePadding"),
                    nonNegative(s, "stationRangePadding"));

            Map<String,Object> a = section(root, "anchoring");
            AnchorRules anchoring = new AnchorRules(
                    positive(a, "captureMinDistance"),
                    nonNegative(a, "captureRadiusPadding"),
                    nonNegative(a, "orbitPadding"),
                    nonNegative(a, "stationSeparationPadding"),
                    nonNegative(a, "orbitSpeedBase"),
                    positive(a, "orbitSpeedReferenceRadius"),
                    positive(a, "orbitSpeedMinimumRadius"));

            Map<String,Object> e = section(root, "extraction");
            ExtractionRules extraction = new ExtractionRules(
                    string(e, "stationTypeId"),
                    positiveInt(e, "fragmentsPerDeposit"),
                    positive(e, "chargeSeconds"),
                    nonNegative(e, "extractorFireCooldownSeconds"),
                    nonNegative(e, "refireCooldownSeconds"),
                    positive(e, "impactEffectSeconds"),
                    nonNegative(e, "fractureOrbitSlotSpacing"),
                    nonNegative(e, "fractureMinimumOrbitPadding"),
                    finite(e, "evenRingOffset"),
                    finite(e, "oddRingOffset"),
                    nonNegative(e, "radialScatterPerPair"),
                    nonNegative(e, "angleJitterRadians"),
                    nonNegative(e, "fragmentSpeedBaseScale"),
                    nonNegative(e, "fragmentSpeedStepScale"),
                    fraction(e, "stressStartProgress"),
                    positiveInt(e, "stressRayCount"),
                    nonNegative(e, "stressInnerRadiusScale"),
                    nonNegative(e, "stressOuterRadiusBaseScale"),
                    nonNegative(e, "stressOuterRadiusGrowthScale"),
                    nonNegative(e, "impactRadiusStartScale"),
                    nonNegative(e, "impactRadiusGrowthScale"));

            Map<String,Object> moon = section(root, "moonModifier");
            MoonModifier moonModifier = new MoonModifier(
                    stringList(moon.get("traits")),
                    integer(moon, "slotDelta"),
                    positiveInt(moon, "minimumSlots"),
                    bonusMap(section(moon, "bonuses")));

            Map<String,BodyProfile> profiles = new LinkedHashMap<>();
            for (Map.Entry<String,Object> entry : section(root, "bodyProfiles").entrySet()) {
                Map<String,Object> body = object(entry.getValue());
                profiles.put(entry.getKey().toUpperCase(Locale.ROOT), new BodyProfile(
                        positiveInt(body, "slots"),
                        stringList(body.get("traits")),
                        stringList(body.get("hazards")),
                        bonusMap(section(body, "bonuses"))));
            }
            if (!profiles.containsKey("ROCKY")) {
                throw new IllegalStateException("bodyProfiles.ROCKY is required");
            }

            EnumMap<CelestialInstallationType,List<String>> installationRoles =
                    new EnumMap<>(CelestialInstallationType.class);
            for (Map.Entry<String,Object> entry : section(root, "installationRoles").entrySet()) {
                CelestialInstallationType type = CelestialInstallationType.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                installationRoles.put(type, stringList(entry.getValue()));
            }

            EnumMap<CelestialBonusKind,String> bonusRequirements = new EnumMap<>(CelestialBonusKind.class);
            for (Map.Entry<String,Object> entry : section(root, "bonusRequirements").entrySet()) {
                CelestialBonusKind kind = CelestialBonusKind.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
                String requirement = String.valueOf(entry.getValue()).trim().toUpperCase(Locale.ROOT);
                if (!"ANY".equals(requirement)) CelestialInstallationType.valueOf(requirement);
                bonusRequirements.put(kind, requirement);
            }
            for (CelestialBonusKind kind : CelestialBonusKind.values()) {
                if (!bonusRequirements.containsKey(kind)) {
                    throw new IllegalStateException("bonusRequirements." + kind + " is required");
                }
            }

            return new CelestialGameplayConfig(
                    scanSeconds, analyzeSeconds, holdSeconds, extractAmount, maxTotalBonus,
                    deposits, scanning, anchoring, extraction, moonModifier,
                    profiles, installationRoles, bonusRequirements);
        } catch (RuntimeException ex) {
            if (ex instanceof IllegalStateException) throw ex;
            throw new IllegalStateException("Could not load celestial gameplay config: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not load celestial gameplay config: " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> map)) throw new IllegalStateException("expected JSON object");
        Map<String,Object> out = new LinkedHashMap<>();
        for (Map.Entry<?,?> entry : map.entrySet()) out.put(String.valueOf(entry.getKey()), entry.getValue());
        return out;
    }

    private static Map<String,Object> section(Map<String,Object> source, String key) {
        if (!source.containsKey(key)) throw new IllegalStateException("missing celestial config section: " + key);
        return object(source.get(key));
    }

    private static String string(Map<String,Object> source, String key) {
        Object value = source.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isBlank()) throw new IllegalStateException("celestial config value " + key + " must not be blank");
        return text;
    }

    private static double finite(Map<String,Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalStateException("celestial config value " + key + " must be finite");
        }
        return number.doubleValue();
    }

    private static double positive(Map<String,Object> source, String key) {
        double value = finite(source, key);
        if (value <= 0) throw new IllegalStateException("celestial config value " + key + " must be > 0");
        return value;
    }

    private static double nonNegative(Map<String,Object> source, String key) {
        double value = finite(source, key);
        if (value < 0) throw new IllegalStateException("celestial config value " + key + " must be >= 0");
        return value;
    }

    private static double fraction(Map<String,Object> source, String key) {
        double value = finite(source, key);
        if (value < 0 || value >= 1) {
            throw new IllegalStateException("celestial config value " + key + " must be in [0,1)");
        }
        return value;
    }

    private static int integer(Map<String,Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.intValue()) {
            throw new IllegalStateException("celestial config value " + key + " must be an integer");
        }
        return number.intValue();
    }

    private static int positiveInt(Map<String,Object> source, String key) {
        int value = integer(source, key);
        if (value <= 0) throw new IllegalStateException("celestial config value " + key + " must be > 0");
        return value;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("expected JSON array of strings");
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String text = item == null ? "" : String.valueOf(item).trim();
            if (text.isBlank()) throw new IllegalStateException("celestial config string list contains a blank entry");
            out.add(text);
        }
        return List.copyOf(out);
    }

    private static Map<CelestialBonusKind,Double> bonusMap(Map<String,Object> source) {
        EnumMap<CelestialBonusKind,Double> out = new EnumMap<>(CelestialBonusKind.class);
        for (Map.Entry<String,Object> entry : source.entrySet()) {
            CelestialBonusKind kind = CelestialBonusKind.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
            if (!(entry.getValue() instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0) {
                throw new IllegalStateException("invalid celestial bonus for " + entry.getKey());
            }
            out.put(kind, number.doubleValue());
        }
        return Map.copyOf(out);
    }

    record DepositRules(
            double baseAmount,
            double amountPerMaterialSlot,
            double harvestRate,
            double nodeRadius,
            double orbitPadding,
            double orbitRadiusScale,
            double orbitSlotSpacing,
            double orbitBaseSpeed,
            double orbitSpeedPerSlot,
            double reattachTolerance,
            double fallbackOrbitSpeed) { }

    record ScanRules(double unitRangePadding, double stationRangePadding) { }

    record AnchorRules(
            double captureMinDistance,
            double captureRadiusPadding,
            double orbitPadding,
            double stationSeparationPadding,
            double orbitSpeedBase,
            double orbitSpeedReferenceRadius,
            double orbitSpeedMinimumRadius) { }

    record ExtractionRules(
            String stationTypeId,
            int fragmentsPerDeposit,
            double chargeSeconds,
            double extractorFireCooldownSeconds,
            double refireCooldownSeconds,
            double impactEffectSeconds,
            double fractureOrbitSlotSpacing,
            double fractureMinimumOrbitPadding,
            double evenRingOffset,
            double oddRingOffset,
            double radialScatterPerPair,
            double angleJitterRadians,
            double fragmentSpeedBaseScale,
            double fragmentSpeedStepScale,
            double stressStartProgress,
            int stressRayCount,
            double stressInnerRadiusScale,
            double stressOuterRadiusBaseScale,
            double stressOuterRadiusGrowthScale,
            double impactRadiusStartScale,
            double impactRadiusGrowthScale) { }

    record MoonModifier(
            List<String> traits,
            int slotDelta,
            int minimumSlots,
            Map<CelestialBonusKind,Double> bonuses) { }

    record BodyProfile(
            int slots,
            List<String> traits,
            List<String> hazards,
            Map<CelestialBonusKind,Double> bonuses) { }
}
