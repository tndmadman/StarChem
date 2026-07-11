package com.tndmadman.rts;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PacketChunks {
    static final int MAX_DATAGRAM_BYTES = 1200;
    static final int MAX_MESSAGE_BYTES = 512_000;
    private static final int DIRECT_MESSAGE_BYTES = 1100;
    private static final int CHUNK_PAYLOAD_BYTES = 900;
    private static final int MAX_ASSEMBLIES = 64;
    private static final int MAX_CHUNKS = 640;
    private static final int MAX_ASSEMBLY_ID_LENGTH = 96;
    private static final int MAX_BUFFERED_BYTES = 8_000_000;
    private static final long ASSEMBLY_TTL_MS = 10_000;

    private final String idPrefix;
    private final Map<String, Assembly> assemblies = new LinkedHashMap<>();
    private final PerfStats perfStats;
    private int bufferedBytes;

    PacketChunks(String idPrefix) { this(idPrefix, null); }

    PacketChunks(String idPrefix, PerfStats perfStats) {
        this.idPrefix = idPrefix == null || idPrefix.isBlank() ? "" : idPrefix + '-';
        this.perfStats = perfStats;
    }

    void send(DatagramSocket socket, String message, InetAddress address, int port) throws IOException {
        if (message == null) return;
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MESSAGE_BYTES) throw new IOException("UDP message exceeds " + MAX_MESSAGE_BYTES + " bytes.");
        if (bytes.length <= DIRECT_MESSAGE_BYTES) {
            sendDatagram(socket, bytes, address, port);
            return;
        }

        String id = idPrefix + Long.toHexString(System.nanoTime()) + Integer.toHexString(message.hashCode());
        if (id.length() > MAX_ASSEMBLY_ID_LENGTH) throw new IOException("UDP chunk ID is too long.");
        List<String> payloads = splitUtf8(message);
        if (payloads.size() > MAX_CHUNKS) throw new IOException("UDP message requires too many chunks.");
        int total = payloads.size();
        for (int i = 0; i < total; i++) {
            String chunk = "CHUNK|" + id + "|" + i + "|" + total + "|" + payloads.get(i);
            byte[] chunkBytes = chunk.getBytes(StandardCharsets.UTF_8);
            if (chunkBytes.length > MAX_DATAGRAM_BYTES) throw new IOException("UDP chunk exceeds datagram limit.");
            sendDatagram(socket, chunkBytes, address, port);
        }
    }

    synchronized String receive(String message, InetAddress address, int port) {
        if (message == null || address == null || port < 1 || port > 65535) {
            malformed();
            return null;
        }
        int messageBytes = utf8Length(message);
        if (messageBytes > MAX_MESSAGE_BYTES) {
            malformed();
            return null;
        }
        if (!message.startsWith("CHUNK|")) return message;

        String[] parts = message.split("\\|", 5);
        if (parts.length < 5 || parts[1].isBlank() || parts[1].length() > MAX_ASSEMBLY_ID_LENGTH) {
            malformed();
            return null;
        }
        int index;
        int total;
        try {
            index = Integer.parseInt(parts[2]);
            total = Integer.parseInt(parts[3]);
        } catch (NumberFormatException ignored) {
            malformed();
            return null;
        }
        int payloadBytes = utf8Length(parts[4]);
        if (index < 0 || total <= 0 || index >= total || total > MAX_CHUNKS || payloadBytes > CHUNK_PAYLOAD_BYTES) {
            malformed();
            return null;
        }

        long now = System.currentTimeMillis();
        trimAssemblies(now);
        String key = address.getHostAddress() + ':' + port + '|' + parts[1];
        Assembly assembly = assemblies.computeIfAbsent(key, k -> new Assembly(total, now));
        if (assembly.parts.length != total) {
            removeAssembly(key);
            malformed();
            return null;
        }
        assembly.lastTouchedMs = now;

        String existing = assembly.parts[index];
        if (existing != null) {
            if (!existing.equals(parts[4])) {
                removeAssembly(key);
                malformed();
            }
            return null;
        }
        if (assembly.totalBytes + payloadBytes > MAX_MESSAGE_BYTES) {
            removeAssembly(key);
            malformed();
            return null;
        }

        assembly.parts[index] = parts[4];
        assembly.count++;
        assembly.totalBytes += payloadBytes;
        bufferedBytes += payloadBytes;
        trimAssemblies(now);
        if (assemblies.get(key) != assembly || assembly.count < total) return null;

        removeAssembly(key);
        StringBuilder out = new StringBuilder();
        for (String part : assembly.parts) {
            if (part == null) {
                malformed();
                return null;
            }
            out.append(part);
        }
        String decoded = out.toString();
        if (utf8Length(decoded) > MAX_MESSAGE_BYTES) {
            malformed();
            return null;
        }
        return decoded;
    }

    private List<String> splitUtf8(String message) throws IOException {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        int bytes = 0;
        while (index < message.length()) {
            int codePoint = message.codePointAt(index);
            int chars = Character.charCount(codePoint);
            int codePointBytes = utf8Length(new String(Character.toChars(codePoint)));
            if (codePointBytes > CHUNK_PAYLOAD_BYTES) throw new IOException("A character exceeds the UDP chunk payload limit.");
            if (bytes > 0 && bytes + codePointBytes > CHUNK_PAYLOAD_BYTES) {
                chunks.add(message.substring(start, index));
                start = index;
                bytes = 0;
            }
            bytes += codePointBytes;
            index += chars;
        }
        if (start < message.length()) chunks.add(message.substring(start));
        return chunks;
    }

    private void sendDatagram(DatagramSocket socket, byte[] bytes, InetAddress address, int port) throws IOException {
        if (bytes.length > MAX_DATAGRAM_BYTES) throw new IOException("UDP datagram exceeds " + MAX_DATAGRAM_BYTES + " bytes.");
        socket.send(new DatagramPacket(bytes, bytes.length, address, port));
        if (perfStats != null) perfStats.recordPacketSent(bytes.length);
    }

    private void trimAssemblies(long now) {
        Iterator<Map.Entry<String, Assembly>> iterator = assemblies.entrySet().iterator();
        while (iterator.hasNext()) {
            Assembly assembly = iterator.next().getValue();
            if (now - assembly.lastTouchedMs > ASSEMBLY_TTL_MS) {
                bufferedBytes = Math.max(0, bufferedBytes - assembly.totalBytes);
                iterator.remove();
            }
        }
        while (assemblies.size() > MAX_ASSEMBLIES || bufferedBytes > MAX_BUFFERED_BYTES) {
            String first = assemblies.keySet().iterator().next();
            removeAssembly(first);
        }
    }

    private void removeAssembly(String key) {
        Assembly removed = assemblies.remove(key);
        if (removed != null) bufferedBytes = Math.max(0, bufferedBytes - removed.totalBytes);
    }

    private void malformed() { if (perfStats != null) perfStats.recordMalformedPacket(); }
    private int utf8Length(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static final class Assembly {
        final String[] parts;
        long lastTouchedMs;
        int count;
        int totalBytes;

        Assembly(int total, long now) {
            this.parts = new String[total];
            this.lastTouchedMs = now;
        }
    }
}
