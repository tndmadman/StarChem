package com.tndmadman.rts;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.function.IntSupplier;

/** Length-prefixed UTF-8 framing for StarChem's TCP protocol. */
final class TcpFrameCodec {
    static final int HEADER_BYTES = Integer.BYTES;
    static final int MAX_FRAME_BYTES = 512_000;

    private TcpFrameCodec() { }

    static byte[] encode(String message) throws IOException {
        if (message == null) throw new IOException("TCP frame message is null.");
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        if (payload.length <= 0 || payload.length > MAX_FRAME_BYTES) {
            throw new IOException("TCP frame payload must be 1-" + MAX_FRAME_BYTES + " bytes.");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(HEADER_BYTES + payload.length);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
        return bytes.toByteArray();
    }

    static DecodedFrame read(DataInputStream in) throws IOException {
        return read(in, () -> MAX_FRAME_BYTES);
    }

    static DecodedFrame read(DataInputStream in, int maxFrameBytes) throws IOException {
        return read(in, () -> maxFrameBytes);
    }

    static DecodedFrame read(DataInputStream in, IntSupplier maxFrameBytes) throws IOException {
        int first = in.read();
        if (first < 0) return null;
        byte[] header = new byte[HEADER_BYTES];
        header[0] = (byte) first;
        in.readFully(header, 1, HEADER_BYTES - 1);
        int length = ByteBuffer.wrap(header).getInt();
        int configuredMax = maxFrameBytes == null ? MAX_FRAME_BYTES : maxFrameBytes.getAsInt();
        int effectiveMax = Math.max(1, Math.min(MAX_FRAME_BYTES, configuredMax));
        if (length <= 0 || length > effectiveMax) {
            throw new IOException("Invalid TCP frame length: " + length + " (max " + effectiveMax + ").");
        }
        byte[] payload = new byte[length];
        in.readFully(payload);
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload));
            return new DecodedFrame(decoded.toString(), HEADER_BYTES + length);
        } catch (CharacterCodingException ex) {
            throw new IOException("TCP frame is not valid UTF-8.", ex);
        }
    }

    record DecodedFrame(String message, int wireBytes) { }
}
