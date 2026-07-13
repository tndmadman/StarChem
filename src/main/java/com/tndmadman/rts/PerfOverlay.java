package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PerfOverlay {
    private static final long REFRESH_NANOS = 250_000_000L;
    private static final int PANEL_WIDTH = 450;
    private long nextRefreshNanos;
    private List<String> lines = List.of("Collecting performance samples...");

    void draw(Graphics2D g2, World world, int screenWidth, String updateLabel,
              PerfSnapshot frame, PerfSnapshot network, PerfSnapshot host) {
        long now = System.nanoTime();
        if (now >= nextRefreshNanos) {
            lines = buildLines(world, updateLabel, frame, network, host);
            nextRefreshNanos = now + REFRESH_NANOS;
        }

        int x = Math.max(14, screenWidth - PANEL_WIDTH - 14);
        int y = 132;
        int lineHeight = 16;
        int height = 34 + lines.size() * lineHeight;
        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRoundRect(x, y, PANEL_WIDTH, height, 12, 12);
        g2.setColor(new Color(80, 180, 255, 190));
        g2.drawRoundRect(x, y, PANEL_WIDTH, height, 12, 12);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        g2.setColor(Color.WHITE);
        g2.drawString("DEV PERFORMANCE (F4)", x + 12, y + 20);
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
        g2.setColor(new Color(220, 238, 250));
        int textY = y + 40;
        for (String line : lines) {
            g2.drawString(line, x + 12, textY);
            textY += lineHeight;
        }
    }

    private List<String> buildLines(World world, String updateLabel,
                                    PerfSnapshot frame, PerfSnapshot network, PerfSnapshot host) {
        List<String> out = new ArrayList<>();
        out.add(String.format(Locale.ROOT,
                "FPS %.1f | frame %.2f ms avg / %.2f max | draw %.2f / %.2f",
                frame.fps(), frame.frameAvgMs(), frame.frameMaxMs(), frame.drawAvgMs(), frame.drawMaxMs()));
        out.add(String.format(Locale.ROOT, "%s %.2f ms avg / %.2f max",
                updateLabel, frame.updateAvgMs(), frame.updateMaxMs()));
        out.add("Entities U " + world.units.size() + " | B " + world.bases.size() + " | R " + world.resources.size()
                + " | shots " + world.shots.size() + " | FX " + world.explosions.size() + " | items " + world.items.size());
        GalaxyMapSnapshot galaxy = world.galaxyMapSnapshot();
        out.add("Active system " + world.activeSystemId() + " | known systems " + (galaxy == null ? 0 : galaxy.systems().size()));

        if (network != null) {
            addNetworkLines(out, "Client", network);
        } else {
            out.add("Network: solo");
        }

        if (host != null) {
            out.add(String.format(Locale.ROOT, "Host simulation %.2f ms avg / %.2f max",
                    host.serverUpdateAvgMs(), host.serverUpdateMaxMs()));
            addNetworkLines(out, "Host", host);
        }
        return out;
    }

    private void addNetworkLines(List<String> out, String label, PerfSnapshot stats) {
        out.add(String.format(Locale.ROOT,
                "%s TCP %.2f ms avg / %.2f max | conn %d | queued %d / %.1f KiB",
                label, stats.networkAvgMs(), stats.networkMaxMs(), stats.activeConnections(), stats.queuedFrames(),
                kib(stats.queuedBytes())));
        out.add(String.format(Locale.ROOT, "%s frames tx %.1f/s %.1f KiB/s | rx %.1f/s %.1f KiB/s",
                label, stats.packetsSentPerSecond(), kib(stats.bytesSentPerSecond()),
                stats.packetsReceivedPerSecond(), kib(stats.bytesReceivedPerSecond())));
        out.add(snapshotLine(label + " snapshots", stats));
        if (stats.coalescedSnapshotsPerSecond() > 0) {
            out.add(String.format(Locale.ROOT, "%s coalesced snapshots %.1f/s", label, stats.coalescedSnapshotsPerSecond()));
        }
        addTransportIssueLine(out, label + " transport", stats);
        if (stats.snapshotAgeMs() >= 0) out.add("Last snapshot " + valueMs(stats.snapshotAgeMs()) + " ago");
    }

    private void addTransportIssueLine(List<String> out, String label, PerfSnapshot stats) {
        if (stats.rejectedConnectionsPerSecond() <= 0 && stats.slowConnectionClosesPerSecond() <= 0
                && stats.inboundOverflowsPerSecond() <= 0 && stats.malformedPacketsPerSecond() <= 0
                && stats.snapshotDecodeFailuresPerSecond() <= 0) return;
        out.add(String.format(Locale.ROOT,
                "%s reject %.1f/s | slow %.1f/s | overflow %.1f/s | frame %.1f/s | snapshot %.1f/s",
                label, stats.rejectedConnectionsPerSecond(), stats.slowConnectionClosesPerSecond(),
                stats.inboundOverflowsPerSecond(), stats.malformedPacketsPerSecond(),
                stats.snapshotDecodeFailuresPerSecond()));
    }

    private String snapshotLine(String label, PerfSnapshot stats) {
        return String.format(Locale.ROOT, "%s tx %.1f/s %.1f KiB avg | rx %.1f/s %.1f KiB avg",
                label, stats.snapshotsSentPerSecond(), kib(stats.averageSnapshotBytesSent()),
                stats.snapshotsReceivedPerSecond(), kib(stats.averageSnapshotBytesReceived()));
    }

    private String valueMs(double value) {
        return value < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f ms", value);
    }

    private double kib(double bytes) { return bytes / 1024.0; }
}
