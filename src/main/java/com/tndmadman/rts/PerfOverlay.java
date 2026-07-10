package com.tndmadman.rts;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class PerfOverlay {
    private static final long REFRESH_NANOS = 250_000_000L;
    private static final int PANEL_WIDTH = 430;
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
            out.add(String.format(Locale.ROOT, "Client net %.2f ms avg / %.2f max | pending %d | resends %.1f/s",
                    network.networkAvgMs(), network.networkMaxMs(), network.pendingReliable(), network.reliableResendsPerSecond()));
            out.add(String.format(Locale.ROOT, "UDP tx %.1f/s %.1f KiB/s | rx %.1f/s %.1f KiB/s",
                    network.packetsSentPerSecond(), kib(network.bytesSentPerSecond()),
                    network.packetsReceivedPerSecond(), kib(network.bytesReceivedPerSecond())));
            out.add(snapshotLine("Snapshots", network));
            if (network.rttMs() >= 0 || network.snapshotAgeMs() >= 0) {
                out.add("RTT " + valueMs(network.rttMs()) + " | last snapshot " + valueMs(network.snapshotAgeMs()) + " ago");
            }
        } else {
            out.add("Network: solo");
        }

        if (host != null) {
            out.add(String.format(Locale.ROOT, "Host simulation %.2f ms avg / %.2f max",
                    host.serverUpdateAvgMs(), host.serverUpdateMaxMs()));
            out.add(String.format(Locale.ROOT, "Host net %.2f ms avg / %.2f max | pending %d | resends %.1f/s",
                    host.networkAvgMs(), host.networkMaxMs(), host.pendingReliable(), host.reliableResendsPerSecond()));
            out.add(String.format(Locale.ROOT, "Host UDP tx %.1f/s %.1f KiB/s | rx %.1f/s %.1f KiB/s",
                    host.packetsSentPerSecond(), kib(host.bytesSentPerSecond()),
                    host.packetsReceivedPerSecond(), kib(host.bytesReceivedPerSecond())));
            out.add(snapshotLine("Host snapshots", host));
        }
        return out;
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
