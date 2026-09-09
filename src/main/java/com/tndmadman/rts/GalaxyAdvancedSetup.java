package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

/** Match-setup editor for the generator controls that do not belong in the compact preset surface. */
final class GalaxyAdvancedSetup {
    private GalaxyAdvancedSetup() { }

    static GalaxyGenerationSettings edit(Component parent, GalaxyGenerationSettings current) {
        GalaxyGenerationSettings settings = current == null
                ? GalaxyMatchSetup.procedural(GalaxySizePreset.MEDIUM, GalaxyTopologyStyle.MIXED)
                : current;

        JSpinner connectivity = decimal(settings.permanentConnectivityDensity(), 0.0, 1.0, 0.05);
        JSpinner frontier = decimal(settings.frontierFrequency(), 0.0, 1.0, 0.05);
        JSpinner richness = decimal(settings.resourceRichness(), 0.25, 4.0, 0.25);
        JSpinner rare = decimal(settings.rareResourceFrequency(), 0.0, 1.0, 0.05);
        JSpinner hazards = decimal(settings.hazardFrequency(), 0.0, 1.0, 0.05);
        JSpinner npcDensity = decimal(settings.npcDensity(), 0.0, 1.0, 0.05);
        JSpinner separation = new JSpinner(new SpinnerNumberModel(settings.startingSeparation(), 1, 12, 1));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        add(form, row++, "Permanent connectivity", connectivity,
                "Higher values add more permanent routes after the connected backbone is built.");
        add(form, row++, "Frontier / dead ends", frontier,
                "Biases sparse-frontier maps toward more leaf systems.");
        add(form, row++, "Resource richness", richness,
                "Weights authored resource-rich systems during composition.");
        add(form, row++, "Rare-resource frequency", rare,
                "Weights authored rare/high-value resource systems.");
        add(form, row++, "Hazard frequency", hazards,
                "Weights authored hazardous systems.");
        add(form, row++, "NPC / faction density", npcDensity,
                "Weights authored faction/NPC systems during composition.");
        add(form, row, "Starting separation", separation,
                "Minimum permanent-link graph distance between planned player start regions.");

        int result = JOptionPane.showConfirmDialog(parent, form, "Advanced Galaxy Generation",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;

        return new GalaxyGenerationSettings(true, settings.targetSystemCount(), settings.topologyStyle(),
                number(connectivity), number(frontier), number(richness), number(rare), number(hazards),
                number(npcDensity), ((Number) separation.getValue()).intValue(), settings.templateWeights());
    }

    private static JSpinner decimal(double value, double min, double max, double step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0.00");
        spinner.setEditor(editor);
        return spinner;
    }

    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static void add(JPanel panel, int row, String labelText, JComponent control, String helpText) {
        GridBagConstraints label = new GridBagConstraints();
        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.NORTHWEST;
        label.insets = new Insets(4, 0, 4, 12);
        panel.add(new JLabel(labelText), label);

        JPanel value = new JPanel(new BorderLayout(0, 2));
        value.add(control, BorderLayout.NORTH);
        JLabel help = new JLabel("<html><span style='font-size:9px'>" + helpText + "</span></html>");
        value.add(help, BorderLayout.CENTER);

        GridBagConstraints field = new GridBagConstraints();
        field.gridx = 1;
        field.gridy = row;
        field.weightx = 1;
        field.fill = GridBagConstraints.HORIZONTAL;
        field.insets = new Insets(4, 0, 4, 0);
        panel.add(value, field);
    }
}
