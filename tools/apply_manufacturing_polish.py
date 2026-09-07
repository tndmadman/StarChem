from pathlib import Path

OVERLAY = Path("src/main/java/com/tndmadman/rts/ManufacturingOverlay.java")
PANEL = Path("src/main/java/com/tndmadman/rts/GamePanel.java")

overlay = OVERLAY.read_text(encoding="utf-8")
start_marker = "    private void refreshResources() {"
end_marker = "    private void refreshQueues() {"
start = overlay.index(start_marker)
end = overlay.index(end_marker, start)

replacement = r'''    private void refreshResources() {
        resourceRows.removeAll();
        ProductionChoice choice = choiceList.getSelectedValue();
        if (choice == null) {
            addInventoryMessage("Select an output to inspect its material locations.");
            resourceSummary.setText("Recipe-aware inventory • owned stations");
            finishResourceRefresh();
            return;
        }

        LinkedHashMap<Material, Double> requirements = new LinkedHashMap<>();
        for (Cost cost : choice.cost) {
            if (cost == null || cost.material() == null || cost.amount() <= 0) continue;
            requirements.merge(cost.material(), cost.amount(), Double::sum);
        }
        if (requirements.isEmpty()) {
            addInventoryMessage("This order has no material inputs to locate.");
            resourceSummary.setText(choice.name + " • no recipe inventory required");
            finishResourceRefresh();
            return;
        }

        List<Material> materials = new ArrayList<>(requirements.keySet());
        materials.sort(Comparator.comparing(material -> material.label, String.CASE_INSENSITIVE_ORDER));
        EnumMap<Material, Double> located = new EnumMap<>(Material.class);
        String owner = playerId();
        String activeId = world.activeSystemId();
        String activeName = world.systemName();
        List<WorldSystemState> states = world.policySystemStates();
        for (WorldSystemState state : states) {
            if (state != null && activeId.equals(state.id) && state.definition != null) {
                activeName = state.definition.name();
                break;
            }
        }

        int systems = 0;
        int stations = appendRecipeSystem(activeId, activeName, world.bases.values(),
                materials, owner, true, located);
        if (stations > 0) systems++;

        for (WorldSystemState state : states) {
            if (state == null || state.id == null || state.id.equals(activeId)) continue;
            String name = state.definition == null ? state.id : state.definition.name();
            int found = appendRecipeSystem(state.id, name, state.bases.values(),
                    materials, owner, false, located);
            if (found > 0) {
                systems++;
                stations += found;
            }
        }

        int shortages = 0;
        for (Map.Entry<Material, Double> requirement : requirements.entrySet()) {
            double available = located.getOrDefault(requirement.getKey(), 0.0);
            if (available + 0.0001 < requirement.getValue()) {
                shortages++;
                addInventoryShortfall(requirement.getKey(), available, requirement.getValue());
            }
        }

        if (systems == 0) {
            addInventoryMessage("Required materials are not stored in any known owned station.");
        }
        resourceRows.add(Box.createVerticalGlue());
        resourceSummary.setText(materials.size() + " recipe materials • " + systems + " system"
                + (systems == 1 ? "" : "s") + " • " + stations + " station"
                + (stations == 1 ? "" : "s")
                + (shortages == 0 ? " • stock located" : " • " + shortages + " short"));
        finishResourceRefresh();
    }

    private int appendRecipeSystem(String systemId, String systemName, Iterable<Base> candidates,
                                   List<Material> materials, String owner, boolean current,
                                   EnumMap<Material, Double> located) {
        List<Base> stations = new ArrayList<>();
        for (Base base : candidates) {
            if (base == null || base.hp <= 0 || !owner.equals(base.playerId)) continue;
            boolean hasRecipeStock = false;
            for (Material material : materials) {
                if (base.inventory.getOrDefault(material, 0.0) > 0.0001) {
                    hasRecipeStock = true;
                    break;
                }
            }
            if (hasRecipeStock) stations.add(base);
        }
        if (stations.isEmpty()) return 0;
        stations.sort(Comparator.comparing((Base base) -> base.type().name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(base -> base.id));

        JPanel systemRow = new JPanel(new BorderLayout());
        systemRow.setOpaque(false);
        systemRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
        systemRow.setBorder(BorderFactory.createEmptyBorder(5, 2, 2, 2));
        String safeName = systemName == null || systemName.isBlank() ? systemId : systemName;
        JLabel system = smallLabel("▼  " + safeName.toUpperCase(Locale.ROOT)
                + (current ? "  • CURRENT" : "  • " + systemId), current ? BRONZE : TEXT);
        system.setFont(system.getFont().deriveFont(Font.BOLD, 11f));
        systemRow.add(system, BorderLayout.WEST);
        resourceRows.add(systemRow);

        for (Base base : stations) {
            JPanel stationRow = new JPanel(new BorderLayout());
            stationRow.setOpaque(false);
            stationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 23));
            stationRow.setBorder(BorderFactory.createEmptyBorder(2, 17, 1, 2));
            JLabel station = smallLabel("└─ " + base.type().name + " " + base.id, TEXT);
            station.setFont(station.getFont().deriveFont(Font.BOLD, 10.5f));
            stationRow.add(station, BorderLayout.WEST);
            resourceRows.add(stationRow);

            for (Material material : materials) {
                double amount = base.inventory.getOrDefault(material, 0.0);
                if (amount <= 0.0001) continue;
                located.merge(material, amount, Double::sum);
                JPanel materialRow = new JPanel(new BorderLayout(6, 0));
                materialRow.setOpaque(false);
                materialRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 25));
                materialRow.setBorder(BorderFactory.createEmptyBorder(1, 38, 1, 3));
                JLabel name = smallLabel("↳  " + material.label, MUTED);
                name.setIcon(CatalogVisuals.materialIcon(material, 16));
                name.setIconTextGap(5);
                JLabel amountLabel = smallLabel(whole(amount), BRONZE);
                materialRow.add(name, BorderLayout.WEST);
                materialRow.add(amountLabel, BorderLayout.EAST);
                resourceRows.add(materialRow);
            }
        }
        resourceRows.add(Box.createVerticalStrut(4));
        return stations.size();
    }

    private void addInventoryShortfall(Material material, double available, double required) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 27));
        row.setBorder(BorderFactory.createEmptyBorder(4, 3, 2, 3));
        JLabel name = smallLabel("⚠  " + material.label + " shortfall", WARNING);
        name.setIcon(CatalogVisuals.materialIcon(material, 16));
        name.setIconTextGap(5);
        JLabel amount = smallLabel(whole(available) + " / " + whole(required), WARNING);
        row.add(name, BorderLayout.WEST);
        row.add(amount, BorderLayout.EAST);
        resourceRows.add(row);
    }

    private void addInventoryMessage(String text) {
        JLabel empty = smallLabel(text, MUTED);
        empty.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        empty.setAlignmentX(Component.LEFT_ALIGNMENT);
        resourceRows.add(empty);
    }

    private void finishResourceRefresh() {
        resourceRows.revalidate();
        resourceRows.repaint();
    }

'''
overlay = overlay[:start] + replacement + overlay[end:]

selection_old = """        populateCosts(choice);
        populateStations(choice);
        updateAutomation(choice);
        revalidate();"""
selection_new = """        populateCosts(choice);
        populateStations(choice);
        updateAutomation(choice);
        refreshResources();
        revalidate();"""
if selection_old not in overlay:
    raise SystemExit("Could not find selected-choice refresh hook")
overlay = overlay.replace(selection_old, selection_new, 1)
OVERLAY.write_text(overlay, encoding="utf-8")

panel = PANEL.read_text(encoding="utf-8")
old = '        g2.drawString(settings.hudCommandLine() + " | Mode: " + commandModeLabel(), 28, 124);'
new = '        g2.drawString(settings.hudCommandLine() + " | Manufacturing: F9 | Mode: " + commandModeLabel(), 28, 124);'
if old not in panel:
    raise SystemExit("Could not find HUD command hint line")
panel = panel.replace(old, new, 1)
PANEL.write_text(panel, encoding="utf-8")
