package com.tndmadman.rts;

final class SideADev {
    private SideADev() { }

    static boolean handle(PeerServerSide s, String[] p, ConnectionId connectionId) {
        if (p.length == 0) return false;
        switch (p[0]) {
            case "DEVFREE" -> {
                if (p.length >= 3 && s.devAllowed(connectionId, p[1])) s.applyDevFreeCrafting(p[1], flag(p[2]));
                return true;
            }
            case "DEVHANGAR" -> {
                if (p.length >= 5 && s.devAllowed(connectionId, p[1])) {
                    Material material = material(p[3]);
                    double amount = amount(p[4]);
                    if (material != null && amount > 0) s.applyDevHangarResource(p[1], p[2], material, amount);
                }
                return true;
            }
            case "DEVAI" -> {
                if (p.length >= 3 && s.devAllowed(connectionId, p[1])) s.applyDevAiCommand(p[1], p[2]);
                return true;
            }
            default -> { return false; }
        }
    }

    private static Material material(String value) {
        try { return Material.valueOf(value); }
        catch (Exception ignored) { return null; }
    }

    private static double amount(String value) {
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) && parsed > 0 ? parsed : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean flag(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "DEV".equalsIgnoreCase(value) || "YES".equalsIgnoreCase(value);
    }
}
