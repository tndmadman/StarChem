package com.tndmadman.rts;

import javax.swing.*;
import java.awt.*;

final class ConnectionOverlayPanel extends JPanel {
    private static final long RETRY_TRACE_INTERVAL_MS = 2_000;

    private final GameFrame owner;
    private final PeerNetwork network;
    private final JLabel title = new JLabel("CONNECTING TO SERVER", SwingConstants.CENTER);
    private final JLabel detail = new JLabel("Opening TCP connection...", SwingConstants.CENTER);
    private final JLabel elapsed = new JLabel("Elapsed: 0.0s", SwingConstants.CENTER);
    private final JProgressBar progress = new JProgressBar(0, 4);
    private final JButton trust = new JButton("TRUST NEW CERTIFICATE");
    private final JButton cancel = new JButton("CANCEL");
    private final Timer timer;
    private String lastTraceKey = "";
    private long lastRetryTraceAt;

    ConnectionOverlayPanel(GameFrame owner, PeerNetwork network) {
        super(new GridBagLayout());
        this.owner = owner;
        this.network = network;
        setOpaque(false);
        setFocusable(true);

        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        detail.setForeground(new Color(220, 238, 250));
        detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 14f));
        elapsed.setForeground(new Color(150, 190, 215));
        progress.setStringPainted(true);
        progress.setValue(0);

        trust.setVisible(false);
        trust.addActionListener(e -> trustChangedCertificate());
        cancel.addActionListener(e -> {
            System.out.println("[CONNECTION][CLIENT][CANCELLED] User cancelled the connection attempt.");
            owner.showLobby("Connection cancelled.");
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        buttons.setOpaque(false);
        buttons.add(trust);
        buttons.add(cancel);

        JPanel card = new JPanel(new GridBagLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(70, 135, 180)),
                BorderFactory.createEmptyBorder(28, 34, 28, 34)));
        card.setBackground(new Color(8, 18, 30, 242));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 12, 0);
        card.add(title, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 16, 0);
        card.add(detail, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        card.add(progress, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 16, 0);
        card.add(elapsed, c);
        c.gridy++;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(0, 0, 0, 0);
        card.add(buttons, c);

        GridBagConstraints root = new GridBagConstraints();
        root.gridx = 0;
        root.gridy = 0;
        root.weightx = 1;
        root.weighty = 1;
        root.fill = GridBagConstraints.NONE;
        add(card, root);

        timer = new Timer(100, e -> refresh());
        timer.start();
        refresh();
    }

    void stop() {
        if (network != null && network.clientMode()) trace(network.clientConnectionProgress());
        timer.stop();
    }

    private void trustChangedCertificate() {
        if (network == null || !network.serverCertificateTrustRequired()) return;
        System.err.println("[CONNECTION][CLIENT][TLS] Server certificate changed; waiting for user verification.");
        int choice = JOptionPane.showConfirmDialog(this,
                network.serverCertificateTrustPrompt(),
                "Server Certificate Changed",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            System.err.println("[CONNECTION][CLIENT][TLS] Changed server certificate was not trusted.");
            return;
        }
        if (!network.trustChangedServerCertificate()) {
            System.err.println("[CONNECTION][CLIENT][TLS][FAILURE] Changed server certificate could not be stored.");
            JOptionPane.showMessageDialog(this,
                    "The pending certificate changed again or could not be stored. Reconnect and verify it again.",
                    "Certificate Trust Failed",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        System.out.println("[CONNECTION][CLIENT][TLS] Changed server certificate trusted; reconnecting.");
    }

    private void refresh() {
        if (network == null || !network.clientMode()) {
            hideModal();
            return;
        }
        ClientConnectionProgress state = network.clientConnectionProgress();
        trace(state);
        if (state.ready()) {
            hideModal();
            return;
        }
        title.setText(state.title());
        detail.setText(asHtml(state.detail()));
        progress.setMaximum(state.stageCount());
        progress.setValue(state.stage());
        progress.setString("Step " + state.stage() + " of " + state.stageCount());
        elapsed.setText(String.format("Elapsed: %.1fs", state.elapsedMillis() / 1000.0));
        trust.setVisible(network.serverCertificateTrustRequired());
        showModal();
        revalidate();
        repaint();
    }

    private void showModal() {
        if (isVisible()) return;
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            if (trust.isVisible()) trust.requestFocusInWindow();
            else cancel.requestFocusInWindow();
        });
    }

    private void hideModal() {
        if (!isVisible()) return;
        setVisible(false);
        restoreGameplayFocus();
    }

    private void restoreGameplayFocus() {
        Container parent = getParent();
        if (parent == null) return;
        for (Component component : parent.getComponents()) {
            if (component instanceof GamePanel panel && panel.isVisible()) {
                SwingUtilities.invokeLater(panel::requestFocusInWindow);
                return;
            }
        }
    }

    private void trace(ClientConnectionProgress state) {
        if (state == null) return;
        long now = System.currentTimeMillis();
        String cleanTitle = clean(state.title());
        String cleanDetail = clean(state.detail());
        String traceKey = state.phase() + "|" + state.stage() + "|" + cleanTitle + "|" + cleanDetail;
        boolean changed = !traceKey.equals(lastTraceKey);
        boolean retryHeartbeat = (state.phase() == ConnectionPhase.CONNECTING
                || state.phase() == ConnectionPhase.RECONNECTING)
                && now - lastRetryTraceAt >= RETRY_TRACE_INTERVAL_MS;
        if (!changed && !retryHeartbeat) return;

        String prefix = switch (state.phase()) {
            case CONNECTING, RECONNECTING -> "[CONNECTION][CLIENT][ATTEMPT]";
            case HANDSHAKING -> "[CONNECTION][CLIENT][TCP]";
            case SYNCHRONIZING -> "[CONNECTION][CLIENT][SYNC]";
            case READY -> "[CONNECTION][CLIENT][READY]";
            case FAILED -> "[CONNECTION][CLIENT][FAILURE]";
            case DISCONNECTED -> "[CONNECTION][CLIENT][DISCONNECTED]";
        };
        String message = prefix
                + " phase=" + state.phase()
                + " stage=" + state.stage() + "/" + state.stageCount()
                + " elapsed=" + String.format(java.util.Locale.ROOT, "%.1fs", state.elapsedMillis() / 1000.0)
                + " status=\"" + clean(network.statusLine()) + "\""
                + " detail=\"" + cleanDetail + "\"";
        if (state.failed()) System.err.println(message); else System.out.println(message);

        lastTraceKey = traceKey;
        if (state.phase() == ConnectionPhase.CONNECTING || state.phase() == ConnectionPhase.RECONNECTING) {
            lastRetryTraceAt = now;
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }

    private static String asHtml(String value) {
        String safe = value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
        return "<html><div style='text-align:center;width:1050px'>" + safe + "</div></html>";
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.dispose();
        super.paintComponent(g);
    }
}
