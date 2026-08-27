package com.tndmadman.rts;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RowFilter;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;

/** Player-facing strategic view of owner-authorized fleets, stations, production, research and alerts. */
final class EmpireOverviewOverlay {
    static final int HOTKEY = KeyEvent.VK_F7;

    private static final Map<World, Controller> CONTROLLERS = Collections.synchronizedMap(new WeakHashMap<>());
    private static WeakReference<World> lastWorld = new WeakReference<>(null);
    private static boolean dispatcherInstalled;

    private EmpireOverviewOverlay() { }

    static void ensureInstalled(World world, PeerNetwork network) {
        if (world == null) return;
        Controller controller;
        synchronized (CONTROLLERS) {
            controller = CONTROLLERS.computeIfAbsent(world, Controller::new);
            if (network != null) controller.setNetwork(network);
            lastWorld = new WeakReference<>(world);
            if (!dispatcherInstalled) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(EmpireOverviewOverlay::dispatchHotkey);
                dispatcherInstalled = true;
            }
        }
    }

    static void clear(World world) {
        if (world == null) return;
        Controller controller = CONTROLLERS.remove(world);
        if (controller != null) controller.dispose();
        World last = lastWorld.get();
        if (last == world) lastWorld = new WeakReference<>(null);
    }

    static boolean visibleForTest(World world) {
        Controller controller = world == null ? null : CONTROLLERS.get(world);
        return controller != null && controller.visible();
    }

    private static boolean dispatchHotkey(KeyEvent event) {
        if (event == null || event.getID() != KeyEvent.KEY_PRESSED || event.getKeyCode() != HOTKEY
                || event.isControlDown() || event.isAltDown() || event.isMetaDown()) return false;
        World world = lastWorld.get();
        Controller controller = world == null ? null : CONTROLLERS.get(world);
        if (controller == null) return false;
        controller.toggle();
        return true;
    }

    enum Tab { SYSTEMS, FLEETS, STATIONS, PRODUCTION, RESEARCH, ALERTS }
    enum SortMode { NAME, SYSTEM, STATUS }

    private static final class Controller {
        private static final String[] SORT_LABELS = {"Name", "System", "Status"};
        private final WeakReference<World> worldRef;
        private WeakReference<PeerNetwork> networkRef = new WeakReference<>(null);
        private final Preferences prefs = Preferences.userNodeForPackage(EmpireOverviewOverlay.class).node("strategic-overview");
        private final EnumMap<Tab, Boolean> attentionByTab = new EnumMap<>(Tab.class);
        private final EnumMap<Tab, SortMode> sortByTab = new EnumMap<>(Tab.class);
        private final EnumMap<Tab, OverviewTable> tables = new EnumMap<>(Tab.class);
        private JDialog dialog;
        private JTabbedPane tabs;
        private JTextField search;
        private JCheckBox attentionOnly;
        private JComboBox<String> sort;
        private JSpinner inventoryThreshold;
        private JLabel stateLabel;
        private Timer refreshTimer;
        private StrategicSummarySnapshot rendered;

        Controller(World world) {
            worldRef = new WeakReference<>(world);
            for (Tab tab : Tab.values()) {
                attentionByTab.put(tab, prefs.getBoolean("attention." + tab.name(), false));
                sortByTab.put(tab, parseSort(prefs.get("sort." + tab.name(), SortMode.SYSTEM.name())));
            }
        }

        void setNetwork(PeerNetwork network) { if (network != null) networkRef = new WeakReference<>(network); }
        boolean visible() { return dialog != null && dialog.isVisible(); }

        void toggle() {
            if (dialog == null) buildDialog();
            if (dialog == null) return;
            if (dialog.isVisible()) {
                savePreferences();
                dialog.setVisible(false);
                return;
            }
            refresh(true);
            dialog.setVisible(true);
            dialog.toFront();
            search.requestFocusInWindow();
        }

        void dispose() {
            if (refreshTimer != null) refreshTimer.stop();
            savePreferences();
            if (dialog != null) dialog.dispose();
            dialog = null;
        }

        private void buildDialog() {
            World world = worldRef.get();
            if (world == null) return;
            Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            dialog = new JDialog(owner, "Strategic Empire Overview", Dialog.ModalityType.MODELESS);
            dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            dialog.setMinimumSize(new Dimension(820, 540));
            dialog.setSize(Math.max(820, prefs.getInt("width", 1080)), Math.max(540, prefs.getInt("height", 720)));
            int px = prefs.getInt("x", Integer.MIN_VALUE);
            int py = prefs.getInt("y", Integer.MIN_VALUE);
            if (px == Integer.MIN_VALUE || py == Integer.MIN_VALUE) dialog.setLocationRelativeTo(owner);
            else dialog.setLocation(px, py);
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { savePreferences(); }
                @Override public void windowDeactivated(WindowEvent event) { savePreferences(); }
            });
            dialog.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-overview");
            dialog.getRootPane().getActionMap().put("close-overview", new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    savePreferences();
                    dialog.setVisible(false);
                }
            });

            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JPanel header = new JPanel(new BorderLayout(8, 8));
            JLabel title = new JLabel("STRATEGIC EMPIRE OVERVIEW  •  F7");
            title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
            header.add(title, BorderLayout.WEST);
            stateLabel = new JLabel("Synchronizing strategic state...");
            header.add(stateLabel, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            tabs = new JTabbedPane();
            for (Tab tab : Tab.values()) {
                OverviewTable table = new OverviewTable(tab, this::navigate);
                tables.put(tab, table);
                tabs.addTab(label(tab), new JScrollPane(table));
            }
            tabs.setSelectedIndex(Math.max(0, Math.min(Tab.values().length - 1, prefs.getInt("tab", 0))));
            tabs.addChangeListener(event -> syncControlsFromTab());
            root.add(tabs, BorderLayout.CENTER);

            JPanel controls = new JPanel(new BorderLayout(8, 4));
            JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            filters.add(new JLabel("Search:"));
            search = new JTextField(prefs.get("query", ""), 24);
            search.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { filtersChanged(); }
                @Override public void removeUpdate(DocumentEvent event) { filtersChanged(); }
                @Override public void changedUpdate(DocumentEvent event) { filtersChanged(); }
            });
            filters.add(search);
            attentionOnly = new JCheckBox("Attention only");
            attentionOnly.addActionListener(event -> {
                Tab tab = activeTab();
                attentionByTab.put(tab, attentionOnly.isSelected());
                prefs.putBoolean("attention." + tab.name(), attentionOnly.isSelected());
                applyFilter(tab);
            });
            filters.add(attentionOnly);
            filters.add(new JLabel("Sort:"));
            sort = new JComboBox<>(new DefaultComboBoxModel<>(SORT_LABELS));
            sort.addActionListener(event -> {
                if (sort.getSelectedIndex() < 0) return;
                Tab tab = activeTab();
                SortMode mode = SortMode.values()[sort.getSelectedIndex()];
                sortByTab.put(tab, mode);
                prefs.put("sort." + tab.name(), mode.name());
                tables.get(tab).sort(mode);
            });
            filters.add(sort);
            filters.add(new JLabel("Low inventory <"));
            inventoryThreshold = new JSpinner(new SpinnerNumberModel(
                    Math.max(0, prefs.getInt("inventoryThreshold", 100)), 0, 1_000_000, 25));
            inventoryThreshold.addChangeListener(event -> {
                prefs.putInt("inventoryThreshold", ((Number)inventoryThreshold.getValue()).intValue());
                refresh(true);
            });
            filters.add(inventoryThreshold);
            controls.add(filters, BorderLayout.CENTER);
            JButton close = new JButton("Close");
            close.addActionListener(event -> { savePreferences(); dialog.setVisible(false); });
            controls.add(close, BorderLayout.EAST);
            root.add(controls, BorderLayout.SOUTH);

            dialog.setContentPane(root);
            refreshTimer = new Timer(400, event -> refresh(false));
            refreshTimer.setCoalesce(true);
            refreshTimer.start();
            syncControlsFromTab();
        }

        private void refresh(boolean force) {
            World world = worldRef.get();
            if (world == null) { dispose(); return; }
            PeerNetwork network = networkRef.get();
            String ownerId = network != null ? network.localPlayerId() : PlayerRegistry.localId();
            StrategicSummarySnapshot snapshot;
            boolean waiting = false;
            if (network != null && network.clientMode()) {
                StrategicSummaryRegistry.State state = StrategicSummaryRegistry.state(world);
                if (!state.initialized() || !ownerId.equals(state.ownerId())) {
                    snapshot = StrategicSummarySnapshot.empty(ownerId);
                    waiting = true;
                } else snapshot = state.snapshot();
            } else {
                snapshot = StrategicSummaryService.capture(world, ownerId);
            }
            if (!force && snapshot.equals(rendered)) return;
            rendered = snapshot;
            populate(snapshot);
            String suffix = snapshot.truncated() ? " • bounded view (more assets exist)" : "";
            stateLabel.setText(waiting ? "Synchronizing owner-scoped strategic state..."
                    : snapshot.systems().size() + " systems • " + snapshot.fleets().size() + " ships • "
                    + snapshot.stations().size() + " stations" + suffix);
        }

        private void populate(StrategicSummarySnapshot snapshot) {
            int threshold = ((Number)inventoryThreshold.getValue()).intValue();
            List<RowData> rows = new ArrayList<>();
            for (StrategicSystemRow row : snapshot.systems()) rows.add(new RowData(
                    new Object[]{row.name(), row.controlled() ? "Controlled" : "Presence", row.ships(), row.stations(),
                            row.productionJobs(), row.damagedAssets(), row.alerts()},
                    row.damagedAssets() > 0 || row.alerts() > 0, new NavTarget(row.systemId(), Double.NaN, Double.NaN)));
            tables.get(Tab.SYSTEMS).setRows(rows);

            rows = new ArrayList<>();
            for (StrategicFleetRow row : snapshot.fleets()) rows.add(new RowData(
                    new Object[]{row.systemId(), row.unitKey(), row.hullName(), row.status(), pct(row.hullFraction()), pct(row.shieldFraction())},
                    row.hullFraction() < 0.72 || "FIGHTING".equals(row.status()), new NavTarget(row.systemId(), row.x(), row.y())));
            tables.get(Tab.FLEETS).setRows(rows);

            rows = new ArrayList<>();
            for (StrategicStationRow row : snapshot.stations()) rows.add(new RowData(
                    new Object[]{row.systemId(), row.baseId(), row.typeName(), row.status(), row.queueSize(), pct(row.hullFraction()),
                            Math.round(row.inventoryTotal())},
                    row.hullFraction() < 0.72 || "BLOCKED".equals(row.status()) || row.inventoryTotal() < threshold,
                    new NavTarget(row.systemId(), row.x(), row.y())));
            tables.get(Tab.STATIONS).setRows(rows);

            rows = new ArrayList<>();
            for (StrategicProductionRow row : snapshot.production()) rows.add(new RowData(
                    new Object[]{row.systemId(), row.baseId(), row.queuePosition(), row.kind(), row.itemName(), pct(row.progress()),
                            Math.round(row.remaining()) + "s", row.blockedReason()},
                    !row.blockedReason().isBlank(), new NavTarget(row.systemId(), row.x(), row.y())));
            tables.get(Tab.PRODUCTION).setRows(rows);

            rows = new ArrayList<>();
            for (StrategicResearchRow row : snapshot.research()) rows.add(new RowData(
                    new Object[]{row.name(), row.status(), row.detail()},
                    "ACTIVE".equals(row.status()) || "AVAILABLE".equals(row.status()), NavTarget.NONE));
            tables.get(Tab.RESEARCH).setRows(rows);

            rows = new ArrayList<>();
            for (StrategicAlertRow row : snapshot.alerts()) rows.add(new RowData(
                    new Object[]{row.systemId(), row.category(), row.assetKey(), row.text()}, true,
                    new NavTarget(row.systemId(), row.x(), row.y())));
            tables.get(Tab.ALERTS).setRows(rows);

            for (Tab tab : Tab.values()) {
                tabs.setTitleAt(tab.ordinal(), label(tab) + " (" + tables.get(tab).getRowCount() + ")");
                tables.get(tab).sort(sortByTab.get(tab));
                applyFilter(tab);
            }
        }

        private void filtersChanged() {
            if (search == null) return;
            prefs.put("query", search.getText());
            applyFilter(activeTab());
        }

        private void syncControlsFromTab() {
            if (tabs == null || attentionOnly == null || sort == null) return;
            Tab tab = activeTab();
            prefs.putInt("tab", tab.ordinal());
            attentionOnly.setSelected(attentionByTab.get(tab));
            sort.setSelectedIndex(sortByTab.get(tab).ordinal());
            applyFilter(tab);
        }

        private void applyFilter(Tab tab) {
            OverviewTable table = tables.get(tab);
            if (table == null) return;
            String query = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
            boolean attention = attentionByTab.getOrDefault(tab, false);
            table.filter(query, attention);
        }

        private Tab activeTab() {
            int index = tabs == null ? 0 : Math.max(0, tabs.getSelectedIndex());
            return Tab.values()[Math.min(Tab.values().length - 1, index)];
        }

        private void navigate(NavTarget target) {
            if (target == null || target.systemId().isBlank()) return;
            World world = worldRef.get();
            if (world == null) return;
            PeerNetwork network = networkRef.get();
            if (dialog != null) dialog.setVisible(false);
            world.status = "Strategic overview: navigating to " + target.systemId() + ".";
            if (!target.systemId().equals(world.activeSystemId())) {
                if (network != null && network.clientMode()) {
                    if (!network.clientReady()) {
                        world.status = "Strategic overview navigation is waiting for multiplayer synchronization.";
                        return;
                    }
                    network.viewSystem(network.localPlayerId(), target.systemId());
                } else if (!world.viewGalaxySystem(target.systemId())) {
                    world.status = "Strategic overview could not open " + target.systemId() + ".";
                    return;
                }
            }
            centerWhenReady(target, 0);
        }

        private void centerWhenReady(NavTarget target, int attempt) {
            World world = worldRef.get();
            if (world == null) return;
            if (target.systemId().equals(world.activeSystemId())) {
                if (Double.isFinite(target.x()) && Double.isFinite(target.y())) {
                    GameCamera camera = GameCamera.forWorld(world);
                    if (camera != null) {
                        Window window = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                        int width = window == null ? 1280 : Math.max(1, window.getWidth());
                        int height = window == null ? 900 : Math.max(1, window.getHeight());
                        camera.centerAt(target.x(), target.y(), world, width, height);
                    }
                }
                world.status = "Strategic overview: viewing " + target.systemId() + ".";
                return;
            }
            if (attempt >= 30) {
                world.status = "Strategic overview navigation timed out while switching systems.";
                return;
            }
            Timer retry = new Timer(100, event -> centerWhenReady(target, attempt + 1));
            retry.setRepeats(false);
            retry.start();
        }

        private void savePreferences() {
            if (dialog == null) return;
            prefs.putInt("x", dialog.getX());
            prefs.putInt("y", dialog.getY());
            prefs.putInt("width", dialog.getWidth());
            prefs.putInt("height", dialog.getHeight());
            if (tabs != null) prefs.putInt("tab", Math.max(0, tabs.getSelectedIndex()));
            if (search != null) prefs.put("query", search.getText());
        }

        private static SortMode parseSort(String value) {
            try { return SortMode.valueOf(value); }
            catch (RuntimeException ex) { return SortMode.SYSTEM; }
        }

        private static String label(Tab tab) {
            String text = tab.name().toLowerCase(Locale.ROOT).replace('_', ' ');
            return Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        private static String pct(double value) { return Math.round(Math.max(0, Math.min(1, value)) * 100) + "%"; }
    }

    private interface Navigator { void navigate(NavTarget target); }

    private record NavTarget(String systemId, double x, double y) {
        static final NavTarget NONE = new NavTarget("", Double.NaN, Double.NaN);
        NavTarget { systemId = systemId == null ? "" : systemId; }
    }

    private record RowData(Object[] values, boolean attention, NavTarget target) { }

    private static final class OverviewTable extends JTable {
        private final Tab tab;
        private final StrategicTableModel model;
        private final TableRowSorter<StrategicTableModel> sorter;
        private final Navigator navigator;

        OverviewTable(Tab tab, Navigator navigator) {
            super();
            this.tab = tab;
            this.navigator = navigator;
            model = new StrategicTableModel(columns(tab));
            setModel(model);
            sorter = new TableRowSorter<>(model);
            setRowSorter(sorter);
            setAutoCreateRowSorter(false);
            setFillsViewportHeight(true);
            setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            setRowHeight(24);
            setGridColor(new Color(60, 68, 78));
            getTableHeader().setReorderingAllowed(false);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() < 2 || !SwingUtilities.isLeftMouseButton(event)) return;
                    int viewRow = rowAtPoint(event.getPoint());
                    if (viewRow < 0) return;
                    int modelRow = convertRowIndexToModel(viewRow);
                    navigator.navigate(model.target(modelRow));
                }
            });
        }

        void setRows(List<RowData> rows) { model.setRows(rows); }

        void filter(String query, boolean attentionOnly) {
            sorter.setRowFilter(new RowFilter<>() {
                @Override public boolean include(Entry<? extends StrategicTableModel, ? extends Integer> entry) {
                    int row = entry.getIdentifier();
                    if (attentionOnly && !model.attention(row)) return false;
                    if (query == null || query.isBlank()) return true;
                    for (int column = 0; column < model.getColumnCount(); column++) {
                        Object value = model.getValueAt(row, column);
                        if (value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(query)) return true;
                    }
                    return false;
                }
            });
        }

        void sort(SortMode mode) {
            int column = switch (mode) {
                case NAME -> nameColumn(tab);
                case SYSTEM -> systemColumn(tab);
                case STATUS -> statusColumn(tab);
            };
            if (column < 0 || column >= model.getColumnCount()) column = 0;
            sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(column, javax.swing.SortOrder.ASCENDING)));
            sorter.sort();
        }

        private static String[] columns(Tab tab) {
            return switch (tab) {
                case SYSTEMS -> new String[]{"System", "Control", "Ships", "Stations", "Jobs", "Damaged", "Alerts"};
                case FLEETS -> new String[]{"System", "Ship", "Hull", "Status", "Hull", "Shield"};
                case STATIONS -> new String[]{"System", "Station", "Type", "Status", "Queue", "Hull", "Inventory"};
                case PRODUCTION -> new String[]{"System", "Station", "Queue", "Kind", "Item", "Progress", "Remaining", "Blocked"};
                case RESEARCH -> new String[]{"Research", "Status", "Detail"};
                case ALERTS -> new String[]{"System", "Category", "Asset", "Alert"};
            };
        }

        private static int nameColumn(Tab tab) {
            return switch (tab) {
                case SYSTEMS -> 0;
                case FLEETS, STATIONS -> 2;
                case PRODUCTION -> 4;
                case RESEARCH -> 0;
                case ALERTS -> 3;
            };
        }

        private static int systemColumn(Tab tab) { return tab == Tab.RESEARCH ? 0 : 0; }
        private static int statusColumn(Tab tab) {
            return switch (tab) {
                case SYSTEMS -> 1;
                case FLEETS, STATIONS -> 3;
                case PRODUCTION -> 7;
                case RESEARCH -> 1;
                case ALERTS -> 1;
            };
        }
    }

    private static final class StrategicTableModel extends AbstractTableModel {
        private final String[] columns;
        private List<RowData> rows = List.of();

        StrategicTableModel(String[] columns) { this.columns = columns; }
        void setRows(List<RowData> rows) { this.rows = rows == null ? List.of() : List.copyOf(rows); fireTableDataChanged(); }
        boolean attention(int row) { return row >= 0 && row < rows.size() && rows.get(row).attention(); }
        NavTarget target(int row) { return row >= 0 && row < rows.size() ? rows.get(row).target() : NavTarget.NONE; }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) { return rows.get(rowIndex).values()[columnIndex]; }
        @Override public Class<?> getColumnClass(int columnIndex) {
            for (RowData row : rows) {
                Object value = row.values()[columnIndex];
                if (value != null) return value.getClass();
            }
            return String.class;
        }
    }
}
