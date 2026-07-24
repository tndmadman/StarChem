package com.tndmadman.rts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded byte reads and strict UTF-8 decoding for persistent state. */
final class BoundedText {
    private static final int BUFFER_SIZE = 16 * 1024;

    private BoundedText() { }

    static String readUtf8(Path path, int maxBytes, MiniJson.Limits limits, String label) throws IOException {
        if (path == null || !Files.isRegularFile(path)) throw new IOException(label + " file is missing");
        long size = Files.size(path);
        if (size > maxBytes) throw new IOException(label + " exceeds " + maxBytes + " bytes");
        try (InputStream input = Files.newInputStream(path)) {
            return decodeUtf8(readBytes(input, maxBytes, label), limits.maxDocumentChars(), label);
        }
    }

    static byte[] readBytes(InputStream input, int maxBytes, String label) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, BUFFER_SIZE));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) break;
            if (read == 0) continue;
            if (read > maxBytes - total) throw new IOException(label + " exceeds " + maxBytes + " bytes");
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    static String decodeUtf8(byte[] bytes, int maxChars, String label) throws IOException {
        if (bytes == null) throw new IOException(label + " is missing");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.length() > maxChars) throw new IOException(label + " exceeds " + maxChars + " characters");
            return text;
        } catch (CharacterCodingException ex) {
            throw new IOException(label + " is not valid UTF-8", ex);
        }
    }
}
