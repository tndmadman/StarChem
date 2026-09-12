package com.tndmadman.rts;

import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.StringJoiner;
import java.util.WeakHashMap;

/** Client-side selection/intel surface for celestial gameplay. */
final class CelestialGameplayOverlay {
    private static final Map<World, String> SELECTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<World, DialogState> DIALOGS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean listenerInstalled;
    private static Field gamePanelWorldField;

    private CelestialGameplayOverlay() { }

    static void ensureInstalled(World world) {
        if (world == null || GraphicsEnvironment.isHeadless() || listenerInstalled) return;
        synchronized (CelestialGameplayOverlay.class) {
            if (listenerInstalled) return;
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    CelestialGameplayOverlay::handleEvent,
                    AWTEvent.MOUSE_EVENT_MASK);
            listenerInstalled = true;
        }
    }

    static String selectedBodyId(World world) {
        return world == null ? "" : SELECTED.getOrDefault(world, "");
    }

    static void refresh(World world) {
        if (world == null || GraphicsEnvironment.isHeadless()) return;
        DialogState state = DIALOGS.get(world);
        if (state == null || !state.dialog.isDisplayable()) return;
        WorldSystemState system = activeState(world);
        String bodyId = selectedBodyId(world);
        state.text.setText(describe(system, bodyId, localPlayerId(world)));
        state.text.setCaretPosition(0);
    }

    private static void handleEvent(AWTEvent raw) {
        if (!(raw instanceof MouseEvent event)
                || event.getID() != MouseEvent.MOUSE_CLICKED
                || event.getButton() != MouseEvent.BUTTON1
                || event.getClickCount() != 1
                || !(event.getSource() instanceof GamePanel panel)) return;
        World world = worldFrom(panel);
        if (world == null) return;
        GameCamera camera = GameCamera.forWorld(world);
        WorldSystemState system = activeState(world);
        if (camera == null || system == null || system.celestials == null) return;
        Point2D point = camera.screenToWorld(event.getPoint());
        CelestialSystem.BodyView body = system.celestials.bodyAt(point.getX(), point.getY(), 28.0);
        if (body == null || body.visualClass() == CelestialVisualClass.STAR) return;

        SELECTED.put(world, body.id());
        String playerId = localPlayerId(world);
        world.status = oneLine(system, body.id(), playerId);
        SwingUtilities.invokeLater(() -> show(world, panel));
    }

    private static void show(World world, GamePanel panel) {
        DialogState state = DIALOGS.get(world);
        if (state == null || !state.dialog.isDisplayable()) {
            Window owner = SwingUtilities.getWindowAncestor(panel);
            JDialog dialog = owner instanceof Frame frame
                    ? new JDialog(frame, "Celestial Intel", false)
                    : new JDialog((Frame) null, "Celestial Intel", false);
            dialog.setModalityType(Dialog.ModalityType.MODELESS);
            JTextArea text = new JTextArea();
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setBorder(javax.swing.BorderFactory.createEmptyBorder(12, 12, 12, 12));
            JScrollPane scroll = new JScrollPane(text);
            scroll.setPreferredSize(new Dimension(420, 390));
            dialog.setContentPane(scroll);
            dialog.pack();
            dialog.setLocationRelativeTo(panel);
            state = new DialogState(dialog, text);
            DIALOGS.put(world, state);
        }
        refresh(world);
        state.dialog.setVisible(true);
        state.dialog.toFront();
    }

    private static String oneLine(WorldSystemState system, String bodyId, String playerId) {
        CelestialBodyState state = CelestialGameplaySystem.bodyState(system, bodyId);
        if (state == null) return "Celestial body selected.";
        CelestialIntelLevel intel = CelestialGameplaySystem.intel(system, bodyId, playerId);
        String claim = state.contested ? "contested"
                : state.claimantId == null || state.claimantId.isBlank() ? "unclaimed"
                : "claimed by " + PlayerRegistry.name(state.claimantId);
        return "Selected " + state.profile.bodyName() + " | " + state.profile.bodyType()
                + " | " + claim + " | intel: " + intel.name().toLowerCase();
    }

    private static String describe(WorldSystemState system, String bodyId, String playerId) {
        if (system == null || bodyId == null || bodyId.isBlank()) return "Click a planet or moon to inspect it.";
        CelestialBodyState state = CelestialGameplaySystem.bodyState(system, bodyId);
        CelestialSystem.BodyView view = system.celestials.bodyView(bodyId);
        if (state == null || view == null) return "Celestial body data is unavailable.";

        CelestialIntelLevel intel = CelestialGameplaySystem.intel(system, bodyId, playerId);
        StringBuilder out = new StringBuilder();
        out.append(state.profile.bodyName()).append(view.moon() ? "  [MOON]\n" : "  [PLANET]\n");
        out.append("Type: ").append(state.profile.bodyType()).append('\n');
        out.append("Radius: ").append(Math.round(view.radius())).append('\n');
        out.append("Intel: ").append(intel).append(" (remain nearby to improve scan)\n");
        out.append("Control: ");
        if (state.contested) out.append("CONTESTED");
        else if (state.claimantId == null || state.claimantId.isBlank()) out.append("Unclaimed");
        else out.append(PlayerRegistry.name(state.claimantId));
        out.append('\n');
        out.append("Installation slots: ").append(state.installations.size())
                .append('/').append(state.profile.installationSlots()).append('\n');
        if (!state.installations.isEmpty()) out.append("Installations: ").append(join(state.installations)).append('\n');

        if (intel.ordinal() >= CelestialIntelLevel.SCANNED.ordinal()) {
            out.append("\nDEPOSITS\n");
            if (state.profile.deposits().isEmpty()) out.append("None detected\n");
            else for (Material material : state.profile.deposits()) out.append(" - ").append(material.label).append('\n');
            out.append("\nTRAITS\n");
            if (state.profile.traits().isEmpty()) out.append("None\n");
            else for (String trait : state.profile.traits()) out.append(" - ").append(trait).append('\n');
            out.append("\nHAZARDS\n");
            if (state.profile.hazards().isEmpty()) out.append("None detected\n");
            else for (String hazard : state.profile.hazards()) out.append(" - ").append(hazard).append('\n');
        } else {
            out.append("\nDeposits, traits and hazards require a completed scan.\n");
        }

        if (intel == CelestialIntelLevel.ANALYZED) {
            out.append("\nSTRATEGIC BONUSES\n");
            if (state.profile.bonuses().isEmpty()) out.append("None\n");
            else state.profile.bonuses().forEach((kind, value) -> out.append(" - ")
                    .append(kind).append(": +").append(Math.round(value * 100)).append("%\n"));
        } else {
            out.append("\nExact strategic bonuses require full analysis.\n");
        }

        out.append("\nOBJECTIVES\n");
        for (CelestialObjectiveType objective : CelestialObjectiveType.values()) {
            CelestialObjectiveStatus status = CelestialGameplaySystem.objectiveStatus(system, bodyId, playerId, objective);
            out.append(" - ").append(objective).append(": ");
            if (status.complete()) out.append("COMPLETE");
            else if (objective == CelestialObjectiveType.HOLD) {
                out.append(Math.round(status.progress())).append('/').append(Math.round(status.target())).append(" sec");
            } else if (objective == CelestialObjectiveType.EXTRACT) {
                out.append(Math.round(status.progress())).append('/').append(Math.round(status.target()));
            } else out.append("pending");
            out.append('\n');
        }
        out.append("\nDeploy a station package near this body to establish an orbital installation and claim it.");
        return out.toString();
    }

    private static String join(Iterable<?> values) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Object value : values) joiner.add(String.valueOf(value));
        return joiner.toString();
    }

    private static String localPlayerId(World world) {
        String id = PlayerRegistry.localId();
        return id == null || id.isBlank() ? world.localPlayerId : id;
    }

    private static WorldSystemState activeState(World world) {
        if (world == null) return null;
        String active = world.activeSystemId();
        for (WorldSystemState state : world.policySystemStates()) {
            if (state != null && state.id.equals(active)) return state;
        }
        return null;
    }

    private static World worldFrom(GamePanel panel) {
        try {
            Field field = gamePanelWorldField;
            if (field == null) {
                field = GamePanel.class.getDeclaredField("world");
                field.setAccessible(true);
                gamePanelWorldField = field;
            }
            Object value = field.get(panel);
            return value instanceof World world ? world : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private record DialogState(JDialog dialog, JTextArea text) { }
}
