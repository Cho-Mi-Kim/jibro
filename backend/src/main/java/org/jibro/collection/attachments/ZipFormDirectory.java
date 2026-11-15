package org.jibro.collection.attachments;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Read the central directory only. Form files do not need extractable text. */
final class ZipFormDirectory {
    private ZipFormDirectory() {}

    static List<String> read(Path path) throws IOException {
        try {
            return names(path, StandardCharsets.UTF_8);
        } catch (ZipException | IllegalArgumentException invalidUtf8) {
            return names(path, Charset.forName("MS949"));
        }
    }

    private static List<String> names(Path path, Charset charset) throws IOException {
        try (var zip = new ZipFile(path.toFile(), charset)) {
            if (zip.size() > 4096) throw new IOException("ZIP_DIRECTORY_LIMIT");
            var names = new ArrayList<String>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || name.length() > 512 || name.contains(":")
                    || name.codePoints().anyMatch(Character::isISOControl)
                    || Arrays.stream(name.split("/", -1)).anyMatch(s -> s.isBlank() || s.startsWith(".") || s.equals("__MACOSX"))) continue;
                names.add(name);
            }
            return List.copyOf(names);
        }
    }
}
