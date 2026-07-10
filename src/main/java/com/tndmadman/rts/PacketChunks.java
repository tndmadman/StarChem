package com.tndmadman.rts;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

final class PacketChunks {
    private static final int MAX_BYTES = 1100;
    private static final int CHUNK_CHARS = 900;
    private static final int MAX_ASSEMBLIES = 256;
    private static final int MAX_CHUNKS = 512;
    private static final long ASSEMBLY_TTL_MS = 10_000;

    private final String idPrefix;
    private final Map<String, Assembly> assemblies = new LinkedHashMap<>();
    private final PerfStats perfStats;

    PacketChunks(String idPrefix) { this(idPrefix, null); }

    PacketChunks(String idPrefix, PerfStats perfStats) {
        this.idPrefix = idPrefix == null || idPrefix.isBlank() ? "" : idPrefix + '-';
        this.perfStats = perfStats;
    }

    void send(DatagramSocket socket, String message, InetAddress address, int port) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            sendDatagram(socket, bytes, address, port);
            return;
        }
        String id = idPrefix + Long.toHexString(System.nanoTime()) + Integer.toHexString(message.hashCode());
        int total = Math.max(1, (message.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_CHARS;
            int end = Math.min(message.length(), start + CHUNK_CHARS);
            String chunk = "CHUNK|" + id + "|" + i + "|" + total + "|" + message.substring(start, end);
            byte[] chunkBytes = chunk.getBytes(StandardCharsets.UTF_8);
            sendDatagram(socket, chunkBytes, address, port);
        }
    }

    synchronized String receive(String message, InetAddress address, int port) {
        if (!message.startsWith("CHUNK|")) return message;
        String[] parts = message.split("\\|", 5);
        if (parts.length < 5) return null;
        int index;
        int total;
        try {
            index = Integer.parseInt(parts[2]);
            total = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (index < 0 || total <= 0 || index >= total || total > MAX_CHUNKS) return null;
        long now = System.currentTimeMillis();
        trimAssemblies(now);
        String key = address.getHostAddress() + ':' + port + '|' + parts[1];
        Assembly assembly = assemblies.computeIfAbsent(key, k -> new Assembly(total, now));
        if (assembly.parts.length != total) {
            assemblies.remove(key);
            return null;
        }
        assembly.lastTouchedMs = now;
        if (assembly.parts[index] == null) {
            assembly.parts[index] = parts[4];
            assembly.count++;
        }
        trimAssemblies(now);
        if (assembly.count < total) return null;
        assemblies.remove(key);
        StringBuilder out = new StringBuilder();
        for (String part : assembly.parts) {
            if (part == null) return null;
            out.append(part);
        }
        return out.toString();
    }

    private void sendDatagram(DatagramSocket socket, byte[] bytes, InetAddress address, int port) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
        if (perfStats != null) perfStats.recordPacketSent(bytes.length);
    }

    private void trimAssemblies(long now) {
        Iterator<Map.Entry<String, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Assembly assembly = iterator.next().getValue();
            if (now - assembly.lastTouchedMs > ASSEMBLY_TTL_MS) iterator.remove();
        }
        while (assemblies.size() > MAX_ASSEMBLIES) {
            String first = assemblies.keySet().iterator().next();
            assemblies.remove(first);
        }
    }

    private static final class Assembly {
        final String[] parts;
        long lastTouchedMs;
        int count;

        Assembly(int total, long now) {
            this.parts = new String[total];
            this.lastTouchedMs = now;
        }
    }
}
