package org.jibro.collection.extraction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit local executable/model paths only; no PATH lookup, downloads, secrets, or model API. */
record OcrConfiguration(Path executable, Path data) {
    static OcrConfiguration configured() throws IOException {
        String executable = setting("jibro.ocr.executable", "JIBRO_OCR_EXECUTABLE");
        String data = setting("jibro.ocr.data-path", "JIBRO_OCR_DATA_PATH");
        if (executable == null && data == null) return null;
        if (executable == null || data == null) throw new IOException("OCR_CONFIGURATION_INVALID");
        return verified(Path.of(executable), Path.of(data));
    }

    static OcrConfiguration verified(Path executable, Path data) throws IOException {
        if (!executable.isAbsolute() || !data.isAbsolute() || !Files.isRegularFile(executable)
            || !Files.isDirectory(data) || !Files.isRegularFile(data.resolve("kor.traineddata"))
            || !Files.isRegularFile(data.resolve("eng.traineddata"))
            || !Files.isRegularFile(data.resolve("osd.traineddata"))) throw new IOException("OCR_CONFIGURATION_INVALID");
        return new OcrConfiguration(executable.normalize(), data.normalize());
    }

    private static String setting(String property, String environment) {
        String value = System.getProperty(property, System.getenv(environment));
        return value == null || value.isBlank() ? null : value;
    }
}
