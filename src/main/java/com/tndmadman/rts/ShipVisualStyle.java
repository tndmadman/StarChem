package com.tndmadman.rts;

import java.awt.Color;

/** Cosmetic-only physical ship materials, deterministic surface variation and ownership accents. */
final class ShipVisualStyle {
    static final int VARIANT_COUNT = 4;

    private static final Palette STRUCTURAL_STEEL_PALETTE = new Palette(
            new Color(105, 116, 128), new Color(70, 81, 92), new Color(31, 39, 48),
            new Color(88, 99, 111), new Color(24, 30, 37), new Color(46, 55, 65, 210),
            new Color(145, 158, 170), new Color(83, 98, 107),
            new Color(24, 47, 60, 220), new Color(103, 204, 246, 210),
            new Color(219, 173, 68, 225));
    private static final Palette CERAMIC_ARMOR_PALETTE = new Palette(
            new Color(177, 180, 178), new Color(142, 147, 148), new Color(70, 76, 79),
            new Color(162, 166, 165), new Color(35, 42, 47), new Color(76, 82, 84, 195),
            new Color(203, 206, 201), new Color(76, 91, 96),
            new Color(20, 42, 53, 225), new Color(98, 208, 250, 215),
            new Color(220, 165, 65, 220));
    private static final Palette INDUSTRIAL_PLATING_PALETTE = new Palette(
            new Color(122, 131, 139), new Color(79, 89, 98), new Color(46, 54, 62),
            new Color(103, 113, 121), new Color(32, 39, 45), new Color(55, 64, 72, 215),
            new Color(153, 162, 170), new Color(78, 88, 94),
            new Color(22, 44, 54, 215), new Color(100, 205, 240, 205),
            new Color(229, 174, 56, 225));

    private ShipVisualStyle() { }

    enum HullMaterial {
        STRUCTURAL_STEEL,
        CERAMIC_ARMOR,
        INDUSTRIAL_PLATING
    }

    record Style(
            HullMaterial material,
            Color hullLight,
            Color hullBase,
            Color hullDark,
            Color armor,
            Color recess,
            Color seam,
            Color edge,
            Color radiator,
            Color window,
            Color engineGlow,
            Color warning,
            Color accent,
            int variantIndex,
            int structuralSeed,
            int variationSeed) { }

    static Style resolve(ShipType type, ShipVisualDefinition visual, Color playerColor, int requestedVariant) {
        int variant = Math.floorMod(requestedVariant, VARIANT_COUNT);
        HullMaterial material = materialFor(type, visual);
        Palette palette = palette(material);
        Color owner = playerColor == null ? new Color(136, 136, 136) : playerColor;
        Color accent = withAlpha(mix(owner, Color.WHITE, 0.08), 220);
        int typeSeed = type == null ? 0 : type.seed;
        return new Style(
                material,
                palette.hullLight,
                palette.hullBase,
                palette.hullDark,
                palette.armor,
                palette.recess,
                palette.seam,
                palette.edge,
                palette.radiator,
                palette.window,
                palette.engineGlow,
                palette.warning,
                accent,
                variant,
                mixBits(typeSeed ^ 0x53A9B4D1),
                mixBits(typeSeed ^ (variant + 1) * 0x6D2B79F5));
    }

    static Style resolve(ShipType type, Color playerColor, int requestedVariant) {
        return resolve(type, ShipVisualCatalog.forType(type), playerColor, requestedVariant);
    }

    static int variantIndex(Unit unit) {
        if (unit == null) return 0;
        int typeHash = unit.shipTypeId == null ? 0 : unit.shipTypeId.hashCode();
        int mixed = mixBits(unit.unitId * 0x45D9F3B ^ typeHash * 0x27D4EB2D);
        return Math.floorMod(mixed, VARIANT_COUNT);
    }

    private static HullMaterial materialFor(ShipType type, ShipVisualDefinition visual) {
        if (type == null) return HullMaterial.STRUCTURAL_STEEL;
        if (visual != null) {
            if (visual.hasFeature(ShipVisualDefinition.Feature.MINING_GEAR)
                    || visual.hasFeature(ShipVisualDefinition.Feature.GAS_GEAR)
                    || visual.hasFeature(ShipVisualDefinition.Feature.CARGO_MODULES)
                    || visual.hasFeature(ShipVisualDefinition.Feature.CONSTRUCTION_GEAR)
                    || visual.hasFeature(ShipVisualDefinition.Feature.SALVAGE_GEAR)) {
                return HullMaterial.INDUSTRIAL_PLATING;
            }
            if (visual.hasFeature(ShipVisualDefinition.Feature.SIEGE_WEAPON)
                    || visual.hasFeature(ShipVisualDefinition.Feature.CAPITAL)) {
                return HullMaterial.CERAMIC_ARMOR;
            }
            // Light combat hulls deliberately keep exposed structural steel. This ensures the
            // reusable material language is represented in production rather than existing only
            // as an unreachable palette after every armed ship was classified as ceramic armor.
            if (visual.mountCount(ShipVisualDefinition.MountKind.HARDPOINT) > 0) {
                return visual.complexityRank() <= 2
                        ? HullMaterial.STRUCTURAL_STEEL : HullMaterial.CERAMIC_ARMOR;
            }
        }
        String id = type.id == null ? "" : type.id.toLowerCase();
        if (type.baseBuilder || !type.harvestKinds.isEmpty() || type.cargoCapacity >= 250
                || id.contains("miner") || id.contains("harvest") || id.contains("hauler")
                || id.contains("freighter") || id.contains("salvag") || id.contains("builder")
                || id.contains("deployer")) {
            return HullMaterial.INDUSTRIAL_PLATING;
        }
        if (type.weaponHardpoints > 0) return HullMaterial.CERAMIC_ARMOR;
        return HullMaterial.STRUCTURAL_STEEL;
    }

    private static Palette palette(HullMaterial material) {
        return switch (material) {
            case STRUCTURAL_STEEL -> STRUCTURAL_STEEL_PALETTE;
            case CERAMIC_ARMOR -> CERAMIC_ARMOR_PALETTE;
            case INDUSTRIAL_PLATING -> INDUSTRIAL_PLATING_PALETTE;
        };
    }

    static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamp(alpha));
    }

    static Color mix(Color a, Color b, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        int r = (int)Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int)Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int)Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        int al = (int)Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t);
        return new Color(clamp(r), clamp(g), clamp(bl), clamp(al));
    }

    private static int mixBits(int value) {
        value = ((value >>> 16) ^ value) * 0x45D9F3B;
        value = ((value >>> 16) ^ value) * 0x45D9F3B;
        return (value >>> 16) ^ value;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record Palette(
            Color hullLight,
            Color hullBase,
            Color hullDark,
            Color armor,
            Color recess,
            Color seam,
            Color edge,
            Color radiator,
            Color window,
            Color engineGlow,
            Color warning) { }
}
