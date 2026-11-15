package org.jibro.collection.extraction;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.jibro.collection.extraction.ExtractionModels.Part;

/** Versioned, bounded IPC; errors never contain document text, paths or exception messages. */
final class ParserProtocol {
    static final int MAX_INPUT = 20 * 1024 * 1024;
    static final int MAX_OUTPUT = 4_500_000;
    static final int MAX_PARTS = 200;
    static final int MAGIC = 0x4a425031;

    private ParserProtocol() {}

    static void write(List<Part> parts, OutputStream stream) throws IOException {
        if (parts.size() > MAX_PARTS) throw new IOException("PARSER_OUTPUT_LIMIT");
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(parts.size());
        int chars = 0;
        for (Part part : parts) {
            if (part.location().length() > 1024 || (chars += part.text().length()) > 1_000_000) throw new IOException("PARSER_OUTPUT_LIMIT");
            writeString(out, part.location());
            writeString(out, part.text());
            if (bytes.size() > MAX_OUTPUT) throw new IOException("PARSER_OUTPUT_LIMIT");
        }
        bytes.writeTo(stream);
        stream.flush();
    }

    static List<Part> read(byte[] bytes) throws IOException {
        if (bytes.length > MAX_OUTPUT) throw new IOException("PARSER_OUTPUT_LIMIT");
        var in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) throw new IOException("PARSER_PROTOCOL_INVALID");
        int count = in.readInt();
        if (count < 0 || count > MAX_PARTS) throw new IOException("PARSER_OUTPUT_LIMIT");
        var parts = new ArrayList<Part>();
        int chars = 0;
        for (int i = 0; i < count; i++) {
            String location = readString(in, 4096), text = readString(in, 4_000_000);
            if (location.length() > 1024 || (chars += text.length()) > 1_000_000) throw new IOException("PARSER_OUTPUT_LIMIT");
            parts.add(new Part(location, text));
        }
        if (in.available() != 0) throw new IOException("PARSER_PROTOCOL_INVALID");
        return List.copyOf(parts);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static String readString(DataInputStream in, int max) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > max || length > in.available()) throw new IOException("PARSER_PROTOCOL_INVALID");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
