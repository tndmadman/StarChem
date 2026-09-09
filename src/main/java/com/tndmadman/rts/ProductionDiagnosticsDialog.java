package com.tndmadman.rts;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Lightweight player-facing viewer for ProductionCausalAnalyzer results. */
final class ProductionDiagnosticsDialog {
    private ProductionDiagnosticsDialog() { }

    static void show(Component parent,
                     ProductionCausalAnalyzer.Analysis analysis,
                     Consumer<ProductionCausalAnalyzer.RecoveryAction> actionHandler) {
        if (analysis == null) return;

        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = owner instanceof Frame frame
                ? new JDialog(frame, "Production Diagnostics", true)
                : owner instanceof Dialog parentDialog
                ? new JDialog(parentDialog, "Production Diagnostics", true)
                : new JDialog((Frame)null, "Production Diagnostics", true);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JLabel summary = new JLabel(analysis.summary());
        summary.setFont(summary.getFont().deriveFont(Font.BOLD, 13f));
        root.add(summary, BorderLayout.NORTH);

        DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode(
                analysis.jobId().isBlank() ? "Production" : "Job " + analysis.jobId());
        for (ProductionCausalAnalyzer.Cause cause : analysis.causes()) addCause(treeRoot, cause);
        JTree tree = new JTree(new DefaultTreeModel(treeRoot));
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new Dimension(640, 360));
        root.add(scroll, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        List<ProductionCausalAnalyzer.RecoveryAction> available = collectActions(analysis.causes());
        for (ProductionCausalAnalyzer.RecoveryAction action : available) {
            JButton button = new JButton(buttonLabel(action.type()));
            button.setToolTipText(action.label());
            button.addActionListener(event -> {
                if (actionHandler != null) actionHandler.accept(action);
                dialog.dispose();
            });
            actions.add(button);
        }
        if (available.isEmpty()) actions.add(new JLabel("No direct recovery action is available for this state."));
        bottom.add(actions, BorderLayout.CENTER);

        JButton close = new JButton("CLOSE");
        close.addActionListener(event -> dialog.dispose());
        bottom.add(close, BorderLayout.EAST);
        root.add(bottom, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(680, 470));
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }

    private static void addCause(DefaultMutableTreeNode parent, ProductionCausalAnalyzer.Cause cause) {
        if (cause == null) return;
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(cause.summary());
        parent.add(node);
        for (ProductionCausalAnalyzer.Cause child : cause.children()) addCause(node, child);
        for (ProductionCausalAnalyzer.RecoveryAction action : cause.actions()) {
            node.add(new DefaultMutableTreeNode("Action: " + action.label()));
        }
    }

    private static List<ProductionCausalAnalyzer.RecoveryAction> collectActions(
            List<ProductionCausalAnalyzer.Cause> causes) {
        List<ProductionCausalAnalyzer.RecoveryAction> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        collectActions(causes, out, seen);
        return List.copyOf(out);
    }

    private static void collectActions(List<ProductionCausalAnalyzer.Cause> causes,
                                       List<ProductionCausalAnalyzer.RecoveryAction> out,
                                       Set<String> seen) {
        if (causes == null) return;
        for (ProductionCausalAnalyzer.Cause cause : causes) {
            if (cause == null) continue;
            for (ProductionCausalAnalyzer.RecoveryAction action : cause.actions()) {
                String key = action.type().name() + '|' + action.parameters();
                if (seen.add(key)) out.add(action);
            }
            collectActions(cause.children(), out, seen);
        }
    }

    private static String buttonLabel(ProductionCausalAnalyzer.ActionType type) {
        if (type == null) return "REVIEW";
        return switch (type) {
            case WAIT_FOR_TRANSIT -> "WAIT FOR CARGO";
            case MOVE_STOCK -> "LOCATE STOCK";
            case CREATE_ROUTE -> "OPEN SOURCE";
            case ENQUEUE_INTERMEDIATE -> "QUEUE COMPONENT";
            case OPEN_RESEARCH -> "OPEN RESEARCH";
            case CHOOSE_STATION -> "FIND STATION";
            case REVIEW_POLICY -> "OPEN POLICY";
            case REVIEW_JOB -> "REVIEW JOB";
        };
    }
}
