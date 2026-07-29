package com.tndmadman.rts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class WormholeSnapshotSync {
    private static final int MAX_WORMHOLES = 256;
    private static final int MAX_ID_LENGTH = 128;

    private WormholeSnapshotSync() { }

    static String write(World world) {
        if (world == null || world.wormholes.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (WormholeGate gate : world.wormholes) {
            if (gate == null) continue;
            if (!out.isEmpty()) out.append(';');
            out.append(token(gate.id)).append(',')
                    .append(token(gate.fromSystemId)).append(',')
                    .append(token(gate.toSystemId)).append(',')
                    .append(precise(gate.x)).append(',').append(precise(gate.y)).append(',')
                    .append(precise(gate.exitX)).append(',').append(precise(gate.exitY));
        }
        return out.toString();
    }

    static void validate(String data) {
        decode(data);
    }

    static void apply(World world, String data) {
        if (world == null) return;
        List<WormholeGate> decoded = decode(data);
        world.wormholes.clear();
        world.wormholes.addAll(decoded);
    }

    private static List<WormholeGate> decode(String data) {
        if (data == null || data.isBlank()) return List.of();
        String[] rows = data.split(";", -1);
        if (rows.length > MAX_WORMHOLES) throw new SnapshotDecodeException("Wormhole state exceeds safe limits.");
        List<WormholeGate> out = new ArrayList<>(rows.length);
        for (int i = 0; i < rows.length; i++) {
            if (rows[i].isBlank()) throw new SnapshotDecodeException("Malformed wormhole state row.");
            String[] fields = rows[i].split(",", -1);
            if (fields.length != 7) throw new SnapshotDecodeException("Malformed wormhole state columns.");
            String id = text(fields[0]);
            String from = text(fields[1]);
            String to = text(fields[2]);
            if (id.length() > MAX_ID_LENGTH || from.length() > MAX_ID_LENGTH || to.isBlank() || to.length() > MAX_ID_LENGTH) {
                throw new SnapshotDecodeException("Malformed wormhole identity.");
            }
            double x = coordinate(fields[3]);
            double y = coordinate(fields[4]);
            double exitX = coordinate(fields[5]);
            double exitY = coordinate(fields[6]);
            out.add(new WormholeGate(id, from, to, x, y, exitX, exitY));
        }
        return List.copyOf(out);
    }

    private static String token(String value) {
        String safe = value == null ? "" : value;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(String value) {
        try {
            if (value == null || value.isBlank()) return "";
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new SnapshotDecodeException("Malformed wormhole text token.");
        }
    }

    private static String precise(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Wormhole coordinate must be finite.");
        return Double.toString(value);
    }

    private static double coordinate(String value) {
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || Math.abs(parsed) > SnapshotReader.MAX_ABS_COORDINATE) throw new NumberFormatException();
            return parsed;
        } catch (RuntimeException ex) {
            throw new SnapshotDecodeException("Malformed wormhole coordinate.");
        }
    }
}
