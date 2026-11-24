package org.jibro.collection.extraction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * Executes a fixed Tesseract CLI in a cleared environment. Document text never becomes command syntax.
 */
final class TesseractOcr {
    static final int MAX_PAGE_TEXT_BYTES = 400_000;
    private final Path directory;
    private final Command command;

    @FunctionalInterface
    interface Command {
        List<String> create(Path image);
    }

    @FunctionalInterface
    interface Recognizer {
        String read(Path image, Duration timeout) throws Exception;
    }

    TesseractOcr(Path directory, OcrConfiguration configuration) {
        this(directory, configuration, 3);
    }

    TesseractOcr(Path directory, OcrConfiguration configuration, int pageSegmentation) {
        this(directory, image -> List.of(configuration.executable().toString(), image.toString(), "stdout",
                "--tessdata-dir", configuration.data().toString(), "-l", "kor+eng", "--psm", Integer.toString(pageSegmentation),
                "-c", "preserve_interword_spaces=1"));
    }

    TesseractOcr(Path directory, Command command) {
        this.directory = directory;
        this.command = command;
    }

    String read(Path image, Duration timeout) throws Exception {
        var builder = new ProcessBuilder(command.create(image));
        ParserProcesses.isolate(builder, directory);
        builder.environment().put("OMP_THREAD_LIMIT", "2");
        Process process = builder.start();
        process.getOutputStream().close();
        var reader = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "ocr-output");
            thread.setDaemon(true);
            return thread;
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            var output = reader.submit(() -> {
                try (var input = process.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count > MAX_PAGE_TEXT_BYTES - bytes.size()) throw new IOException("OCR_TEXT_LIMIT");
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toByteArray();
                }
            });
            byte[] result = output.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS))
                throw new TimeoutException("OCR_PAGE_TIMEOUT");
            if (process.exitValue() != 0) throw new IOException("OCR_PROCESS_FAILED");
            return new String(result, StandardCharsets.UTF_8).strip();
        } catch (ExecutionException failure) {
            throw new IOException("OCR_OUTPUT_FAILED");
        } finally {
            boolean stopped = ParserProcesses.terminate(process);
            reader.shutdownNow();
            if (!stopped) throw new IOException("OCR_PROCESS_NOT_TERMINATED");
        }
    }
}
