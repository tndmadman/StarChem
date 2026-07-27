package com.tndmadman.rts;

import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;

final class TutorialOverlay extends JComponent {
    private static final Preferences PREFS = Preferences.userNodeForPackage(TutorialOverlay.class);
    private static final String PREF_DISABLED = "firstRunTutorialDisabled";
    private static final String PREF_LEGACY_COMPLETED = "firstRunTutorialCompleted";
    private static final String PREF_CORE_COMPLETED = "firstRunTutorialCoreCompleted";
    private static final String PREF_ADVANCED_COMPLETED = "firstRunTutorialAdvancedCompleted";

    private static final int HUD_X = 16;
    private static final int HUD_Y = 154;
    private static final int HUD_HEIGHT = 286;
    private static final int CONTROL_GAP = 7;

    private final World world;
    private final boolean eligible;
    private final boolean persist;
    private final Timer timer;
    private final KeyEventDispatcher keyDispatcher = this::dispatchKeyEvent;
    private final Rectangle pauseButton = new Rectangle();
    private final Rectangle skipStepButton = new Rectangle();
    private final Rectangle skipSectionButton = new Rectangle();
    private final Rectangle restartButton = new Rectangle();
    private final Rectangle skipTrackButton = new Rectangle();

    private boolean dispatcherInstalled;
    private boolean disabled;
    private boolean coreCompleted;
    private boolean advancedCompleted;
    private boolean advancedActive;
    private boolean mapOpened;
    private boolean catalogOpened;
    private boolean codexOpened;
    private boolean deliveryAlreadyObserved;
    private int startingProspectors;
    private double deliveryInventoryBaseline;
    private String homeSystemId;
    private String encounterSystemId = "";
    private int encounterEnemyCount;
    private ProductionJobKind trackedIndustryKind;
    private String trackedIndustryItemId = "";
    private String trackedIndustryPlayerId = "";
    private Material trackedIndustryOutput;
    private double trackedIndustryOutputBaseline;
    private String lastCompletedObjective = "";
    private long lastCompletedUntilNanos;
    private Step step = Step.SELECT;

    TutorialOverlay(World world, boolean eligible) {
        this(world, eligible, true);
    }

    TutorialOverlay(World world, boolean eligible, boolean persist) {
        this.world = world;
        this.eligible = eligible;
        this.persist = persist;
        this.disabled = persist && readBoolean(PREF_DISABLED, false);
        boolean legacyCompleted = persist && readBoolean(PREF_LEGACY_COMPLETED, false);
        this.coreCompleted = persist && (legacyCompleted || readBoolean(PREF_CORE_COMPLETED, false));
        this.advancedCompleted = persist && readBoolean(PREF_ADVANCED_COMPLETED, false);
        if (coreCompleted) step = advancedCompleted ? Step.COMPLETE : Step.ADVANCED_READY;
        setOpaque(false);
        setFocusable(false);
        timer = new Timer(100, event -> tick());
        installMouseControls();
        resetCoreBaselines();
    }

    void start() {
        if (!eligible) return;
        if (!dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher);
            dispatcherInstalled = true;
        }
        timer.start();
    }

    void stop() {
        timer.stop();
        if (dispatcherInstalled) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher);
            dispatcherInstalled = false;
        }
    }

    void toggle() {
        if (!eligible) {
            world.status = "Tutorials are available in SOLO mode.";
            return;
        }
        if (disabled) {
            disabled = false;
            writeBoolean(PREF_DISABLED, false);
            if (!coreCompleted) {
                world.status = "Core tutorial resumed.";
            } else if (!advancedCompleted) {
                advancedActive = true;
                if (!step.isAdvanced()) {
                    step = Step.CATALOG;
                    resetAdvancedObservations();
                }
                world.status = "Advanced tutorial resumed.";
            } else {
                restartAll();
            }
            repaint();
            return;
        }
        if (active()) {
            disabled = true;
            advancedActive = false;
            writeBoolean(PREF_DISABLED, true);
            world.status = (step.isAdvanced() ? "Advanced" : "Core")
                    + " tutorial paused. Press F2 to resume.";
            repaint();
            return;
        }
        if (coreCompleted && !advancedCompleted) {
            advancedActive = true;
            step = Step.CATALOG;
            resetAdvancedObservations();
            world.status = "Optional advanced tutorial started.";
        } else {
            restartAll();
        }
        repaint();
    }

    void skipCurrentObjective() {
        if (!active()) return;
        Step skipped = step;
        if (step == Step.RESPOND) {
            completeCore("Skipped objective: " + skipped.objective + ". Advanced tutorial is available with F2.",
                    "Core tutorial completed by skipping the final objective.");
            return;
        }
        if (step == Step.COMPLETE_INDUSTRY) {
            completeAdvanced("Skipped objective: " + skipped.objective + ". All tutorials are complete.",
                    "Advanced tutorial completed by skipping the final objective.");
            return;
        }
        step = step.next();
        onStepEntered();
        rememberCompletion(skipped);
        world.status = "Skipped objective: " + skipped.objective + ".";
        AlertCenter.push(world, world.status);
        repaint();
    }

    void skipCurrentSection() {
        if (!active()) return;
        String chapter = step.chapter;
        boolean advanced = step.isAdvanced();
        Step candidate = step;
        while (candidate != Step.COMPLETE && candidate != Step.ADVANCED_READY
                && candidate.chapter.equals(chapter)) {
            candidate = candidate.next();
        }
        if (!advanced && candidate == Step.ADVANCED_READY) {
            completeCore("Skipped section: " + chapter + ". Advanced tutorial is available with F2.",
                    "Core section skipped: " + chapter + ".");
            return;
        }
        if (advanced && candidate == Step.COMPLETE) {
            completeAdvanced("Skipped section: " + chapter + ". All tutorials are complete.",
                    "Advanced section skipped: " + chapter + ".");
            return;
        }
        step = candidate;
        onStepEntered();
        lastCompletedObjective = "Skipped section: " + chapter;
        lastCompletedUntilNanos = System.nanoTime() + 2_500_000_000L;
        world.status = "Skipped tutorial section: " + chapter + ".";
        AlertCenter.push(world, world.status);
        repaint();
    }

    void restartCurrentTrack() {
        if (!eligible) return;
        disabled = false;
        writeBoolean(PREF_DISABLED, false);
        if (step.isAdvanced() || (coreCompleted && !advancedCompleted)) {
            advancedCompleted = false;
            advancedActive = true;
            step = Step.CATALOG;
            writeBoolean(PREF_ADVANCED_COMPLETED, false);
            resetAdvancedObservations();
            clearCompletionNotice();
            world.status = "Advanced tutorial restarted.";
        } else {
            restartAll();
            world.status = "Core tutorial restarted.";
        }
        AlertCenter.push(world, world.status);
        repaint();
    }

    void skipCurrentTrack() {
        if (!active()) return;
        if (step.isAdvanced()) {
            completeAdvanced("Advanced tutorial skipped. Press Shift+F5 to replay everything.",
                    "Advanced tutorial skipped.");
        } else {
            completeCore("Core tutorial skipped. Press F2 for the optional advanced tutorial.",
                    "Core tutorial skipped.");
        }
    }

    void skipAllTutorials() {
        if (!eligible) return;
        disabled = false;
        coreCompleted = true;
        advancedCompleted = true;
        advancedActive = false;
        step = Step.COMPLETE;
        writeBoolean(PREF_DISABLED, false);
        writeBoolean(PREF_CORE_COMPLETED, true);
        writeBoolean(PREF_ADVANCED_COMPLETED, true);
        writeBoolean(PREF_LEGACY_COMPLETED, true);
        world.status = "All tutorials skipped. Press Shift+F5 to restart them.";
        AlertCenter.push(world, world.status);
        repaint();
    }

    boolean active() {
        return eligible && !disabled
                && (!coreCompleted || (advancedActive && !advancedCompleted));
    }

    String stepNameForTest() { return step.name(); }
    String chapterNameForTest() { return step.chapter; }
    String trackNameForTest() { return step.isAdvanced() ? "Advanced" : "Core"; }
    int objectiveCountForTest() { return Step.corePlayableCount(); }
    int advancedObjectiveCountForTest() { return Step.advancedPlayableCount(); }
    boolean coreCompletedForTest() { return coreCompleted; }
    boolean advancedCompletedForTest() { return advancedCompleted; }
    void updateForTest() { updateProgress(); }
    void observeGalaxyMapForTest() { mapOpened = true; }
    void observeCatalogForTest() { catalogOpened = true; }
    void observeCodexForTest() { codexOpened = true; }
    void skipStepForTest() { skipCurrentObjective(); }
    void skipSectionForTest() { skipCurrentSection(); }
    void restartTrackForTest() { restartCurrentTrack(); }
    void skipTrackForTest() { skipCurrentTrack(); }
    void skipAllForTest() { skipAllTutorials(); }

    private void tick() {
        if (!active()) return;
        updateProgress();
        repaint();
    }

    private void updateProgress() {
        if (!active() || world == null) return;
        switch (step) {
            case SELECT -> { if (world.selectedCount() > 0) advance(); }
            case HARVEST -> { if (hasActiveHarvester()) advance(); }
            case COLLECT -> updateCollectionProgress();
            case DELIVER -> { if (deliveryAlreadyObserved || friendlyBaseInventoryTotal() > deliveryInventoryBaseline + 0.05) advance(); }
            case QUEUE_BUILD -> { if (hasQueuedShip(Rules.STARTING_SHIP) || localShipCount(Rules.STARTING_SHIP) > startingProspectors) advance(); }
            case BUILD_COMPLETE -> { if (localShipCount(Rules.STARTING_SHIP) > startingProspectors) advance(); }
            case MAP -> { if (mapOpened) advance(); }
            case WORMHOLE -> { String activeSystemId = safe(world.activeSystemId()); if (!activeSystemId.equals(homeSystemId) && hasLocalLiveUnit()) advance(); }
            case ENCOUNTER -> updateNpcEncounter();
            case RESPOND -> updateNpcResponse();
            case CATALOG -> { if (catalogOpened) advance(); }
            case CODEX -> { if (codexOpened) advance(); }
            case QUEUE_DEPLOYER -> { if (hasQueuedShip("station_builder") || hasLocalShip("station_builder")) advance(); }
            case DEPLOYER_COMPLETE -> { if (hasLocalShip("station_builder")) advance(); }
            case LOAD_INDUSTRY_PACKAGE -> { if (hasLoadedIndustryPackage() || hasIndustryStation()) advance(); }
            case PLACE_INDUSTRY_STATION -> { if (hasIndustryStation()) advance(); }
            case QUEUE_INDUSTRY -> { if (captureIndustryJob()) advance(); }
            case COMPLETE_INDUSTRY -> { if (industryCompletionObserved()) finishAdvanced(); }
            case ADVANCED_READY, COMPLETE -> { }
        }
    }

    private void updateCollectionProgress() {
        if (localCargoTotal() > 0.05) { advance(); return; }
        if (friendlyBaseInventoryTotal() > deliveryInventoryBaseline + 0.05) { deliveryAlreadyObserved = true; advance(); }
    }

    private void updateNpcEncounter() {
        int enemies = visibleNpcAssets();
        if (enemies <= 0) return;
        encounterSystemId = safe(world.activeSystemId());
        encounterEnemyCount = enemies;
        advance();
    }

    private void updateNpcResponse() {
        int enemies = visibleNpcAssets();
        String activeSystemId = safe(world.activeSystemId());
        if (encounterSystemId.isBlank()) {
            if (enemies <= 0) return;
            encounterSystemId = activeSystemId;
            encounterEnemyCount = enemies;
        }
        if (!activeSystemId.equals(encounterSystemId) || attackingNpc() || enemies < encounterEnemyCount) finishCore();
    }

    private void advance() {
        Step completedStep = step;
        step = step.next();
        rememberCompletion(completedStep);
        onStepEntered();
        AlertCenter.push(world, "Tutorial objective complete: " + completedStep.objective + ".");
    }

    private void onStepEntered() {
        if (step == Step.COLLECT) { deliveryInventoryBaseline = friendlyBaseInventoryTotal(); deliveryAlreadyObserved = false; }
        if (step == Step.DELIVER && !deliveryAlreadyObserved) deliveryInventoryBaseline = friendlyBaseInventoryTotal();
        if (step == Step.ENCOUNTER) { encounterSystemId = ""; encounterEnemyCount = 0; }
        if (step == Step.QUEUE_INDUSTRY) clearTrackedIndustryJob();
    }

    private void finishCore() { completeCore("Core tutorial complete. Press F2 when ready for optional advanced operations.", "Core tutorial complete. Press F2 for the optional advanced tutorial."); }

    private void completeCore(String status, String alert) {
        rememberCompletion(step);
        coreCompleted = true;
        advancedCompleted = false;
        advancedActive = false;
        disabled = false;
        step = Step.ADVANCED_READY;
        writeBoolean(PREF_DISABLED, false);
        writeBoolean(PREF_CORE_COMPLETED, true);
        writeBoolean(PREF_ADVANCED_COMPLETED, false);
        writeBoolean(PREF_LEGACY_COMPLETED, true);
        world.status = status;
        AlertCenter.push(world, alert);
        repaint();
    }

    private void finishAdvanced() { completeAdvanced("All tutorials complete. Press Shift+F5 to replay from the beginning.", "Advanced tutorial complete. Press Shift+F5 to replay all tutorials."); }

    private void completeAdvanced(String status, String alert) {
        rememberCompletion(step);
        coreCompleted = true;
        advancedCompleted = true;
        advancedActive = false;
        disabled = false;
        step = Step.COMPLETE;
        writeBoolean(PREF_DISABLED, false);
        writeBoolean(PREF_CORE_COMPLETED, true);
        writeBoolean(PREF_ADVANCED_COMPLETED, true);
        writeBoolean(PREF_LEGACY_COMPLETED, true);
        world.status = status;
        AlertCenter.push(world, alert);
        repaint();
    }

    private void restartAll() {
        disabled = false;
        coreCompleted = false;
        advancedCompleted = false;
        advancedActive = false;
        step = Step.SELECT;
        mapOpened = false;
        catalogOpened = false;
        codexOpened = false;
        encounterSystemId = "";
        encounterEnemyCount = 0;
        clearCompletionNotice();
        clearTrackedIndustryJob();
        writeBoolean(PREF_DISABLED, false);
        writeBoolean(PREF_CORE_COMPLETED, false);
        writeBoolean(PREF_ADVANCED_COMPLETED, false);
        writeBoolean(PREF_LEGACY_COMPLETED, false);
        resetCoreBaselines();
        world.status = "Core tutorial restarted.";
        repaint();
    }

    private void rememberCompletion(Step completedStep) { lastCompletedObjective = completedStep.objective; lastCompletedUntilNanos = System.nanoTime() + 2_500_000_000L; }
    private void clearCompletionNotice() { lastCompletedObjective = ""; lastCompletedUntilNanos = 0; }
    private void resetCoreBaselines() { startingProspectors = localShipCount(Rules.STARTING_SHIP); deliveryInventoryBaseline = friendlyBaseInventoryTotal(); deliveryAlreadyObserved = false; homeSystemId = world == null ? "" : safe(world.activeSystemId()); }
    private void resetAdvancedObservations() { catalogOpened = false; codexOpened = false; clearTrackedIndustryJob(); clearCompletionNotice(); }

    private boolean hasActiveHarvester() {
        for (Unit unit : world.units.values()) {
            if (!PlayerRegistry.isLocal(unit.playerId) || unit.hp <= 0) continue;
            if (unit.automationResourceId >= 0 && (unit.task == UnitTask.AUTO_HARVEST || unit.task == UnitTask.RETURN_TO_STATION)) return true;
        }
        return false;
    }

    private boolean hasQueuedShip(String shipId) {
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) if (job.kind == ProductionJobKind.SHIP && shipId.equals(job.itemId)) return true;
        }
        return false;
    }

    private boolean hasLocalLiveUnit() { for (Unit unit : world.units.values()) if (PlayerRegistry.isLocal(unit.playerId) && unit.hp > 0) return true; return false; }
    private boolean hasLocalShip(String shipId) { return localShipCount(shipId) > 0; }
    private int localShipCount(String shipId) { if (world == null) return 0; int count = 0; for (Unit unit : world.units.values()) if (PlayerRegistry.isLocal(unit.playerId) && unit.hp > 0 && shipId.equals(unit.shipTypeId)) count++; return count; }
    private boolean hasLoadedIndustryPackage() { for (Unit unit : world.units.values()) if (PlayerRegistry.isLocal(unit.playerId) && unit.hp > 0 && isIndustryStationType(unit.basePackageType)) return true; return false; }
    private boolean hasIndustryStation() { for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId) && base.hp > 0 && isIndustryStationType(base.typeId)) return true; return false; }

    private boolean captureIndustryJob() {
        if (trackedIndustryKind != null && !trackedIndustryItemId.isBlank()) return true;
        for (Base base : world.bases.values()) {
            if (!PlayerRegistry.isLocal(base.playerId) || base.hp <= 0) continue;
            for (ProductionJob job : base.productionQueue) {
                if (job.kind != ProductionJobKind.CRAFTABLE && job.kind != ProductionJobKind.RESEARCH) continue;
                trackedIndustryKind = job.kind;
                trackedIndustryItemId = job.itemId;
                trackedIndustryPlayerId = base.playerId;
                if (job.kind == ProductionJobKind.CRAFTABLE) {
                    CraftableItem item = CraftingRules.item(job.itemId);
                    trackedIndustryOutput = item == null ? null : item.outputMaterial;
                    trackedIndustryOutputBaseline = localMaterialTotal(trackedIndustryOutput);
                }
                return true;
            }
        }
        return false;
    }

    private boolean industryCompletionObserved() {
        if (trackedIndustryKind == null || trackedIndustryItemId.isBlank()) { captureIndustryJob(); return false; }
        boolean completed = switch (trackedIndustryKind) {
            case RESEARCH -> world.hasResearch(trackedIndustryPlayerId, trackedIndustryItemId);
            case CRAFTABLE -> trackedIndustryOutput != null && localMaterialTotal(trackedIndustryOutput) > trackedIndustryOutputBaseline + 0.001;
            default -> false;
        };
        if (completed) return true;
        if (!hasIndustryJob(trackedIndustryKind, trackedIndustryItemId)) { clearTrackedIndustryJob(); captureIndustryJob(); }
        return false;
    }

    private boolean hasIndustryJob(ProductionJobKind kind, String itemId) { for (Base base : world.bases.values()) { if (!PlayerRegistry.isLocal(base.playerId) || base.hp <= 0) continue; for (ProductionJob job : base.productionQueue) if (job.kind == kind && itemId.equals(job.itemId)) return true; } return false; }
    private void clearTrackedIndustryJob() { trackedIndustryKind = null; trackedIndustryItemId = ""; trackedIndustryPlayerId = ""; trackedIndustryOutput = null; trackedIndustryOutputBaseline = 0; }
    private double localMaterialTotal(Material material) { if (material == null) return 0; double total = 0; for (Base base : world.bases.values()) if (PlayerRegistry.isLocal(base.playerId) && base.hp > 0) total += Math.max(0, base.inventory.getOrDefault(material, 0.0)); return total; }
    private double localCargoTotal() { double total = 0; for (Unit unit : world.units.values()) if (PlayerRegistry.isLocal(unit.playerId) && unit.hp > 0) total += unit.cargoUsed(); return total; }
    private double friendlyBaseInventoryTotal() { if (world == null) return 0; double total = 0; for (Base base : world.bases.values()) { if (!PlayerRegistry.isLocal(base.playerId) || base.hp <= 0) continue; for (double amount : base.inventory.values()) total += Math.max(0, amount); } return total; }
    private int visibleNpcAssets() { int count = 0; for (Unit unit : world.units.values()) if (unit.hp > 0 && NpcRules.isNpcFaction(unit.playerId)) count++; for (Base base : world.bases.values()) if (base.hp > 0 && NpcRules.isNpcFaction(base.playerId)) count++; return count; }

    private boolean attackingNpc() {
        for (Unit unit : world.units.values()) {
            if (!PlayerRegistry.isLocal(unit.playerId) || unit.hp <= 0 || unit.task != UnitTask.ATTACK) continue;
            Unit targetUnit = CombatTarget.unit(world, unit.attackTarget);
            if (targetUnit != null && NpcRules.isNpcFaction(targetUnit.playerId)) return true;
            Base targetBase = CombatTarget.base(world, unit.attackTarget);
            if (targetBase != null && NpcRules.isNpcFaction(targetBase.playerId)) return true;
        }
        return false;
    }

    private boolean dispatchKeyEvent(KeyEvent event) {
        if (!active() || event.getID() != KeyEvent.KEY_PRESSED || event.isControlDown() || event.isAltDown() || event.isMetaDown() || event.getSource() instanceof JTextComponent) return false;
        switch (event.getKeyCode()) {
            case KeyEvent.VK_M -> mapOpened = true;
            case KeyEvent.VK_I -> catalogOpened = true;
            case KeyEvent.VK_F1 -> codexOpened = true;
            case KeyEvent.VK_F3 -> skipCurrentObjective();
            case KeyEvent.VK_F4 -> skipCurrentSection();
            case KeyEvent.VK_F5 -> { if (event.isShiftDown()) restartAll(); else restartCurrentTrack(); }
            case KeyEvent.VK_F6 -> { if (event.isShiftDown()) skipAllTutorials(); else skipCurrentTrack(); }
            default -> { return false; }
        }
        repaint();
        return false;
    }

    private void installMouseControls() {
        MouseAdapter controls = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!active() || event.getButton() != MouseEvent.BUTTON1) return;
                Control control = controlAt(event.getPoint());
                if (control == null) return;
                switch (control) {
                    case PAUSE -> toggle();
                    case SKIP_STEP -> skipCurrentObjective();
                    case SKIP_SECTION -> skipCurrentSection();
                    case RESTART -> restartCurrentTrack();
                    case SKIP_TRACK -> skipCurrentTrack();
                }
            }
            @Override public void mouseMoved(MouseEvent event) { setCursor(controlAt(event.getPoint()) == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); }
            @Override public void mouseExited(MouseEvent event) { setCursor(Cursor.getDefaultCursor()); }
        };
        addMouseListener(controls);
        addMouseMotionListener(controls);
    }

    @Override public boolean contains(int x, int y) { return active() && controlAt(new Point(x, y)) != null; }

    private Control controlAt(Point point) {
        layoutControlButtons();
        if (pauseButton.contains(point)) return Control.PAUSE;
        if (skipStepButton.contains(point)) return Control.SKIP_STEP;
        if (skipSectionButton.contains(point)) return Control.SKIP_SECTION;
        if (restartButton.contains(point)) return Control.RESTART;
        if (skipTrackButton.contains(point)) return Control.SKIP_TRACK;
        return null;
    }

    private void layoutControlButtons() {
        int width = hudWidth();
        int y = HUD_Y + HUD_HEIGHT - 43;
        int available = width - 28 - CONTROL_GAP * 4;
        int buttonWidth = Math.max(90, available / 5);
        int x = HUD_X + 14;
        pauseButton.setBounds(x, y, buttonWidth, 28); x += buttonWidth + CONTROL_GAP;
        skipStepButton.setBounds(x, y, buttonWidth, 28); x += buttonWidth + CONTROL_GAP;
        skipSectionButton.setBounds(x, y, buttonWidth, 28); x += buttonWidth + CONTROL_GAP;
        restartButton.setBounds(x, y, buttonWidth, 28); x += buttonWidth + CONTROL_GAP;
        skipTrackButton.setBounds(x, y, buttonWidth, 28);
    }

    @Override protected void paintComponent(Graphics graphics) {
        if (!active()) return;
        Graphics2D g2 = (Graphics2D) graphics.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        layoutControlButtons();
        int width = hudWidth();
        int innerWidth = width - 28;
        boolean advanced = step.isAdvanced();
        int objectiveIndex = advanced ? step.advancedIndex() : step.ordinal() + 1;
        int objectiveCount = advanced ? Step.advancedPlayableCount() : Step.corePlayableCount();
        String tutorialTitle = advanced ? "ADVANCED TUTORIAL (OPTIONAL)" : "CORE TUTORIAL";
        Color accent = advanced ? new Color(255, 185, 85) : new Color(80, 190, 255);
        Color accentLight = advanced ? new Color(255, 215, 145) : new Color(135, 220, 255);
        g2.setColor(new Color(5, 10, 17, 232));
        g2.fillRoundRect(HUD_X, HUD_Y, width, HUD_HEIGHT, 14, 14);
        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 225));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRoundRect(HUD_X, HUD_Y, width, HUD_HEIGHT, 14, 14);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(accentLight);
        g2.drawString(tutorialTitle + "  •  " + step.chapter.toUpperCase() + "  •  " + objectiveIndex + "/" + objectiveCount, HUD_X + 14, HUD_Y + 19);
        int barX = HUD_X + 14, barY = HUD_Y + 28, barW = innerWidth;
        g2.setColor(new Color(30, 45, 58)); g2.fillRoundRect(barX, barY, barW, 6, 6, 6);
        g2.setColor(accent); int progressW = (int)Math.round(barW * Math.min(1.0, objectiveIndex / (double)objectiveCount)); g2.fillRoundRect(barX, barY, Math.max(6, progressW), 6, 6, 6);
        drawActionGraphic(g2, HUD_X + 16, HUD_Y + 48, 128, 145, accent, accentLight);
        int textX = HUD_X + 160, textWidth = width - 178;
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 17f)); g2.setColor(Color.WHITE); g2.drawString(fit(g2, step.objective, textWidth), textX, HUD_Y + 62);
        drawDetailLine(g2, "DO", step.instruction, textX, HUD_Y + 88, textWidth, accentLight);
        drawDetailLine(g2, "LOOK FOR", step.lookFor, textX, HUD_Y + 113, textWidth, new Color(205, 226, 238));
        drawDetailLine(g2, "SUCCESS", step.success, textX, HUD_Y + 138, textWidth, new Color(135, 225, 165));
        drawDetailLine(g2, "WHY", step.context, textX, HUD_Y + 163, textWidth, new Color(150, 190, 215));
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        if (!lastCompletedObjective.isBlank() && System.nanoTime() < lastCompletedUntilNanos) { g2.setColor(new Color(125, 230, 155)); g2.drawString(fit(g2, "✓ " + lastCompletedObjective, innerWidth), HUD_X + 14, HUD_Y + 211); }
        else { g2.setColor(new Color(135, 175, 200)); g2.drawString("Keyboard: F2 pause • F3 skip step • F4 skip section • F5 restart • F6 skip track", HUD_X + 14, HUD_Y + 211); g2.drawString("Shift+F5 restarts everything • Shift+F6 skips everything", HUD_X + 14, HUD_Y + 226); }
        drawControlButton(g2, pauseButton, "Pause", "F2", accent);
        drawControlButton(g2, skipStepButton, "Skip Step", "F3", accent);
        drawControlButton(g2, skipSectionButton, "Skip Section", "F4", accent);
        drawControlButton(g2, restartButton, "Restart", "F5", accent);
        drawControlButton(g2, skipTrackButton, "Skip Tutorial", "F6", accent);
        g2.dispose();
    }

    private void drawActionGraphic(Graphics2D g2, int x, int y, int width, int height, Color accent, Color accentLight) {
        boolean pulse = (System.currentTimeMillis() / 450L) % 2 == 0;
        g2.setColor(new Color(15, 27, 39, 235)); g2.fillRoundRect(x, y, width, height, 12, 12);
        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), pulse ? 220 : 130)); g2.setStroke(new BasicStroke(pulse ? 2.2f : 1.4f)); g2.drawRoundRect(x, y, width, height, 12, 12);
        int cx = x + width / 2, cy = y + 49;
        g2.setColor(accentLight);
        switch (step.actionKind) {
            case LEFT_CLICK, RIGHT_CLICK -> drawMouse(g2, cx, cy, step.actionKind == ActionKind.LEFT_CLICK);
            case KEY -> drawKey(g2, cx, cy, step.inputLabel);
            case WAIT -> drawClock(g2, cx, cy);
            case WATCH -> drawEye(g2, cx, cy);
            case CHOICE -> drawChoice(g2, cx, cy);
        }
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f)); g2.setColor(accentLight);
        String input = step.actionKind == ActionKind.LEFT_CLICK ? "LEFT CLICK" : step.actionKind == ActionKind.RIGHT_CLICK ? "RIGHT CLICK" : step.actionKind == ActionKind.KEY ? "PRESS " + step.inputLabel : step.actionKind == ActionKind.WAIT ? "WAIT / WATCH" : step.actionKind == ActionKind.WATCH ? "LOOK AROUND" : "CHOOSE";
        drawCentered(g2, input, cx, y + 91);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f)); g2.setColor(new Color(205, 226, 238)); drawCentered(g2, "▼", cx, y + 107);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 11f)); drawCentered(g2, fit(g2, step.targetLabel, width - 12), cx, y + 126);
    }

    private void drawMouse(Graphics2D g2, int cx, int cy, boolean left) { int x = cx - 20, y = cy - 27; g2.setStroke(new BasicStroke(2f)); g2.drawRoundRect(x, y, 40, 56, 18, 18); g2.drawLine(cx, y, cx, y + 19); g2.drawLine(x, y + 19, x + 40, y + 19); g2.setColor(new Color(255, 225, 120)); if (left) g2.fillRoundRect(x + 3, y + 3, 15, 13, 7, 7); else g2.fillRoundRect(cx + 2, y + 3, 15, 13, 7, 7); }
    private void drawKey(Graphics2D g2, int cx, int cy, String label) { int width = Math.max(48, 20 + label.length() * 10), x = cx - width / 2, y = cy - 21; g2.setStroke(new BasicStroke(2f)); g2.drawRoundRect(x, y, width, 42, 9, 9); g2.drawLine(x + 6, y + 34, x + width - 6, y + 34); g2.setFont(g2.getFont().deriveFont(Font.BOLD, label.length() > 2 ? 14f : 20f)); drawCentered(g2, label, cx, y + 27); }
    private void drawClock(Graphics2D g2, int cx, int cy) { g2.setStroke(new BasicStroke(2f)); g2.drawOval(cx - 24, cy - 24, 48, 48); g2.drawLine(cx, cy, cx, cy - 15); g2.drawLine(cx, cy, cx + 13, cy + 8); }
    private void drawEye(Graphics2D g2, int cx, int cy) { g2.setStroke(new BasicStroke(2f)); g2.drawArc(cx - 31, cy - 17, 62, 34, 15, 150); g2.drawArc(cx - 31, cy - 17, 62, 34, 195, 150); g2.fillOval(cx - 7, cy - 7, 14, 14); }
    private void drawChoice(Graphics2D g2, int cx, int cy) { g2.setStroke(new BasicStroke(2.5f)); g2.drawLine(cx, cy + 24, cx, cy - 5); g2.drawLine(cx, cy - 5, cx - 23, cy - 25); g2.drawLine(cx, cy - 5, cx + 23, cy - 25); g2.drawLine(cx - 23, cy - 25, cx - 15, cy - 24); g2.drawLine(cx - 23, cy - 25, cx - 21, cy - 17); g2.drawLine(cx + 23, cy - 25, cx + 15, cy - 24); g2.drawLine(cx + 23, cy - 25, cx + 21, cy - 17); }
    private void drawDetailLine(Graphics2D g2, String label, String value, int x, int y, int width, Color valueColor) { g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f)); g2.setColor(new Color(115, 155, 180)); g2.drawString(label, x, y); int labelWidth = Math.max(62, g2.getFontMetrics().stringWidth(label) + 12); g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f)); g2.setColor(valueColor); g2.drawString(fit(g2, value, width - labelWidth), x + labelWidth, y); }
    private void drawControlButton(Graphics2D g2, Rectangle bounds, String label, String key, Color accent) { g2.setColor(new Color(20, 38, 52, 240)); g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8); g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 165)); g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 8, 8); g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10f)); g2.setColor(Color.WHITE); drawCentered(g2, fit(g2, label + "  [" + key + "]", bounds.width - 8), bounds.x + bounds.width / 2, bounds.y + 18); }
    private int hudWidth() { return Math.min(880, Math.max(640, getWidth() - 32)); }
    private static void drawCentered(Graphics2D g2, String text, int centerX, int baselineY) { int width = g2.getFontMetrics().stringWidth(text); g2.drawString(text, centerX - width / 2, baselineY); }
    private static String fit(Graphics2D g2, String value, int maxWidth) { if (value == null) return ""; if (g2.getFontMetrics().stringWidth(value) <= maxWidth) return value; String suffix = "…"; int length = value.length(); while (length > 1 && g2.getFontMetrics().stringWidth(value.substring(0, length) + suffix) > maxWidth) length--; return value.substring(0, Math.max(1, length)) + suffix; }
    private void writeBoolean(String key, boolean value) { if (!persist) return; try { PREFS.putBoolean(key, value); } catch (SecurityException ignored) { } }
    private static boolean readBoolean(String key, boolean fallback) { try { return PREFS.getBoolean(key, fallback); } catch (SecurityException ignored) { return fallback; } }
    private static boolean isIndustryStationType(String typeId) { return "manufacturing".equals(typeId) || "laboratory".equals(typeId); }
    private static String safe(String value) { return value == null ? "" : value; }

    private enum Control { PAUSE, SKIP_STEP, SKIP_SECTION, RESTART, SKIP_TRACK }
    private enum ActionKind { LEFT_CLICK, RIGHT_CLICK, KEY, WAIT, WATCH, CHOICE }

    private enum Step {
        SELECT("Fleet Control", "Select your Prospector", "Left-click the small Prospector mining ship near your Outpost.", "A bright selection ring and the ship information panel.", "The ship is selected and ready to receive orders.", "Selected ships receive movement, harvesting, and combat orders.", ActionKind.LEFT_CLICK, "LEFT", "PROSPECTOR SHIP"),
        HARVEST("Resource Operations", "Start auto-harvesting", "Keep the Prospector selected, then right-click a nearby rock or gas cloud.", "A resource node the Prospector can harvest and an automation route line.", "The Prospector begins moving to and mining the resource.", "Auto-harvest keeps the miner working without repeated commands.", ActionKind.RIGHT_CLICK, "RIGHT", "RESOURCE NODE"),
        COLLECT("Resource Operations", "Collect your first cargo", "Let the Prospector continue mining; no additional click is required.", "The cargo amount in the selected ship panel increasing above zero.", "Material appears in the Prospector cargo hold.", "Ships carry resources before returning them to a station.", ActionKind.WAIT, "", "CARGO METER"),
        DELIVER("Resource Operations", "Deliver cargo to the Outpost", "Allow the automated Prospector to return, or move it inside the Outpost unload range.", "The ship approaching the Outpost and its cargo amount dropping.", "The Outpost inventory increases after the cargo unloads.", "Delivered materials become available for construction and production.", ActionKind.WATCH, "", "OUTPOST"),
        QUEUE_BUILD("Production", "Queue a second Prospector", "Left-click the Outpost, open its production menu, then click Build Prospector.", "The Outpost build list and a Prospector production entry costing iron and copper.", "A Prospector job appears in the Outpost production queue.", "Stations reserve resources and process production jobs in order.", ActionKind.LEFT_CLICK, "LEFT", "OUTPOST → BUILD PROSPECTOR"),
        BUILD_COMPLETE("Production", "Wait for construction to finish", "Leave the job queued and watch the Outpost production progress.", "The active production indicator counting down toward completion.", "A second Prospector launches beside the Outpost.", "Additional miners increase income and let you split assignments.", ActionKind.WAIT, "", "PRODUCTION QUEUE"),
        MAP("Exploration", "Open the galaxy map", "Press M once while the game view has focus.", "Connected systems, wormhole links, and your local assets on the map.", "The galaxy map opens.", "The map shows where your fleet can expand or retreat.", ActionKind.KEY, "M", "GALAXY MAP"),
        WORMHOLE("Exploration", "Travel through a wormhole", "Close the map, select a ship, then right-click a wormhole gate leading to another system.", "A wormhole/gate marker and a travel order from the selected ship.", "The active system changes and one of your ships is present there.", "Wormholes connect systems with different resources and threats.", ActionKind.RIGHT_CLICK, "RIGHT", "WORMHOLE GATE"),
        ENCOUNTER("Survival", "Locate an NPC presence", "Look around the new system and allow contacts to enter scouting range.", "A non-player ship or station with a different faction name or color.", "The tutorial detects an NPC asset in the current system.", "New systems may contain miners, raiders, fleets, or stations.", ActionKind.WATCH, "", "NPC CONTACT"),
        RESPOND("Survival", "Respond to the NPC threat", "Choose: attack the NPC, destroy part of its force, or retreat through a wormhole.", "Attack lines and weapon fire, falling enemy count, or a safe route out.", "You engage, reduce the threat, or leave the encounter system.", "Choosing when to fight or withdraw protects your early economy.", ActionKind.CHOICE, "", "FIGHT OR WITHDRAW"),
        ADVANCED_READY("Advanced", "Advanced tutorial available", "Press F2 when you are ready to begin the optional advanced track.", "The status message confirming core completion.", "The advanced tutorial opens at Reference Tools.", "The optional track covers reference tools, station deployment, and industry.", ActionKind.KEY, "F2", "ADVANCED TRACK"),
        CATALOG("Reference Tools", "Open the resource catalog", "Press I to open the searchable resource catalog.", "Material names, resource locations, recipes, and uses.", "The resource catalog opens.", "The catalog helps you plan where to gather every required resource.", ActionKind.KEY, "I", "RESOURCE CATALOG"),
        CODEX("Reference Tools", "Open the codex", "Close the catalog if needed, then press F1.", "Ship, station, crafting, research, and control reference pages.", "The codex opens.", "The codex explains progression requirements before you commit resources.", ActionKind.KEY, "F1", "CODEX"),
        QUEUE_DEPLOYER("Station Expansion", "Queue a Deployer", "Left-click the Outpost and choose Build Deployer in its production menu.", "The Deployer entry and its material requirements.", "A Deployer job appears in the Outpost queue, or a Deployer already exists.", "Deployers transport and place packaged stations.", ActionKind.LEFT_CLICK, "LEFT", "OUTPOST → BUILD DEPLOYER"),
        DEPLOYER_COMPLETE("Station Expansion", "Launch the Deployer", "Keep the Deployer job queued until construction finishes.", "A large station-placement ship launching beside the Outpost.", "A living Deployer is present in your fleet.", "An empty Deployer must be near the Outpost before a station package can be loaded.", ActionKind.WAIT, "", "DEPLOYER SHIP"),
        LOAD_INDUSTRY_PACKAGE("Station Expansion", "Load an industry station package", "Move the empty Deployer near the Outpost, click the Outpost, then load Manufacturing Plant or Research Lab.", "A package entry in the production menu and the Deployer package slot.", "The Deployer carries an industry package, or that station already exists.", "Manufacturing creates components; laboratories convert components into research progress.", ActionKind.LEFT_CLICK, "LEFT", "OUTPOST → STATION PACKAGE"),
        PLACE_INDUSTRY_STATION("Station Expansion", "Place the industry station", "Select the loaded Deployer, open its placement menu, then click the package and choose open space.", "A placement preview that is clear of other stations and obstructions.", "A Manufacturing Plant or Research Lab appears in the system.", "Specialized stations unlock the deeper production and technology economy.", ActionKind.LEFT_CLICK, "LEFT", "DEPLOYER → PLACE PACKAGE"),
        QUEUE_INDUSTRY("Industry", "Queue manufacturing or research", "Click the new station and queue any available recipe or research topic.", "An unlocked entry whose required resources are in the station hangar.", "A crafting or research job appears in the station production queue.", "Advanced production consumes delivered resources and may require station fuel.", ActionKind.LEFT_CLICK, "LEFT", "INDUSTRY STATION"),
        COMPLETE_INDUSTRY("Industry", "Complete the industry job", "Keep the station fueled and supplied while the queued job counts down.", "The blocked-reason field staying clear and remaining time decreasing.", "A crafted output enters inventory or the research topic becomes complete.", "Completed components and research unlock stronger ships, stations, and equipment.", ActionKind.WAIT, "", "ACTIVE INDUSTRY JOB"),
        COMPLETE("Complete", "All tutorials complete", "Press Shift+F5 to replay everything from the beginning.", "Normal gameplay with no tutorial overlay blocking your view.", "Both core and advanced completion states remain saved locally.", "You now understand fleet control, economy, exploration, survival, expansion, and industry.", ActionKind.KEY, "SHIFT+F5", "REPLAY ALL");

        final String chapter, objective, instruction, lookFor, success, context, inputLabel, targetLabel;
        final ActionKind actionKind;
        Step(String chapter, String objective, String instruction, String lookFor, String success, String context, ActionKind actionKind, String inputLabel, String targetLabel) { this.chapter = chapter; this.objective = objective; this.instruction = instruction; this.lookFor = lookFor; this.success = success; this.context = context; this.actionKind = actionKind; this.inputLabel = inputLabel; this.targetLabel = targetLabel; }
        Step next() { return values()[Math.min(COMPLETE.ordinal(), ordinal() + 1)]; }
        boolean isAdvanced() { return ordinal() >= CATALOG.ordinal() && ordinal() < COMPLETE.ordinal(); }
        int advancedIndex() { return ordinal() - CATALOG.ordinal() + 1; }
        static int corePlayableCount() { return ADVANCED_READY.ordinal(); }
        static int advancedPlayableCount() { return COMPLETE.ordinal() - CATALOG.ordinal(); }
    }
}
