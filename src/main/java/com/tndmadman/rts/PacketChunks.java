package com.tndmadman.rts;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class PacketChunks {
    private static final int MAX_BYTES = 1100;
    private static final int CHUNK_CHARS = 900;
    private static final int MAX_ASSEMBLIES = 256;
    private static final Map<String, Assembly> ASSEMBLIES = new LinkedHashMap<>();

    private PacketChunks() { }

    static void send(DatagramSocket socket, String message, InetAddress address, int port) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            socket.send(new DatagramPacket(bytes, bytes.length, address, port));
            return;
        }
        String id = Long.toHexString(System.nanoTime()) + Integer.toHexString(message.hashCode());
        int total = Math.max(1, (message.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
        for (int i = 0; i < total; i++) {
            int start = i * CHUNK_CHARS;
            int end = Math.min(message.length(), start + CHUNK_CHARS);
            String chunk = "CHUNK|" + id + "|" + i + "|" + total + "|" + message.substring(start, end);
            byte[] chunkBytes = chunk.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(chunkBytes, chunkBytes.length, address, port));
        }
    }

    static String receive(String message, InetAddress address, int port) {
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
        if (index < 0 || total <= 0 || index >= total || total > 512) return null;
        String key = address.getHostAddress() + ':' + port + '|' + parts[1];
        Assembly assembly = ASSEMBLIES.computeIfAbsent(key, k -> new Assembly(total));
        if (assembly.parts.length != total) return null;
        if (assembly.parts[index] == null) {
            assembly.parts[index] = parts[4];
            assembly.count++;
        }
        trimAssemblies();
        if (assembly.count < total) return null;
        ASSEMBLIES.remove(key);
        StringBuilder out = new StringBuilder();
        for (String part : assembly.parts) {
            if (part == null) return null;
            out.append(part);
        }
        return out.toString();
    }

    private static void trimAssemblies() {
        while (ASSEMBLIES.size() > MAX_ASSEMBLIES) {
            String first = ASSEMBLIES.keySet().iterator().next();
            ASSEMBLIES.remove(first);
        }
    }

    private static final class Assembly {
        final String[] parts;
        int count;

        Assembly(int total) {
            this.parts = new String[total];
        }
    }
}
