package org.jibro.collection.extraction;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import org.springframework.stereotype.Component;

import static org.jibro.collection.extraction.ExtractionModels.Part;

@Component
public class BoundedDocumentParser {
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final Semaphore OCR_SLOTS = new Semaphore(1);
    private static final Set<String> TYPES = Set.of("application/pdf", "application/x-ole-storage", "application/zip");
    private final Duration timeout;
    private final Path tempRoot;
    private final WorkerCommand worker;
    private final boolean opticalFallback;

    public BoundedDocumentParser() {
        this(Duration.ofSeconds(8), Path.of(System.getProperty("java.io.tmpdir")), BoundedDocumentParser::workerCommand, true);
    }

    BoundedDocumentParser(Duration timeout, Path tempRoot, WorkerCommand worker) {
        this(timeout, tempRoot, worker, false);
    }
    BoundedDocumentParser(Duration timeout, Path tempRoot, WorkerCommand worker, boolean opticalFallback) {
        this.timeout = timeout;
        this.tempRoot = tempRoot.toAbsolutePath();
        this.worker = worker;
        this.opticalFallback = opticalFallback;
    }

    @FunctionalInterface
    interface WorkerCommand {
        List<String> create(Path directory, Path input, String mediaType) throws IOException;
    }

    public List<Part> parse(byte[] bytes, String mediaType) throws Exception {
        if (bytes.length > ParserProtocol.MAX_INPUT || !TYPES.contains(mediaType))
            throw new IOException("PARSER_INPUT_INVALID");
        List<Part> nativeParts = parseIsolated(bytes, mediaType, timeout, worker);
        if (!opticalFallback || !OcrDocumentParser.needsFallback(nativeParts, mediaType)) return nativeParts;
        boolean acquired = false;
        try {
            var configuration = OcrConfiguration.configured();
            if (configuration == null) return nativeParts;
            acquired = OCR_SLOTS.tryAcquire();
            if (!acquired) return incomplete(nativeParts, "OCR 처리 대기·일부 미판독");
            return parseIsolated(bytes, mediaType, OcrDocumentParser.DOCUMENT_TIMEOUT,
                (directory, input, type) -> ocrWorkerCommand(directory, input, type, configuration));
        } catch (InterruptedException cancellation) {
            throw cancellation;
        } catch (Exception failure) {
            return incomplete(nativeParts, "OCR 판독 실패·일부 미판독 (" + OcrDocumentParser.failureCode(failure) + ")");
        } finally {
            if (acquired) OCR_SLOTS.release();
        }
    }

    private List<Part> incomplete(List<Part> nativeParts, String reason) {
        var output = new ArrayList<>(nativeParts);
        output.add(new Part(OcrProvenance.MARKER + " " + reason, ""));
        return List.copyOf(output);
    }

    private List<Part> parseIsolated(byte[] bytes, String mediaType, Duration timeout, WorkerCommand worker) throws Exception {
        if (!SLOTS.tryAcquire()) throw new RejectedExecutionException("PARSER_BUSY");
        Path directory = null;
        Process process = null;
        boolean terminated = true;
        ExecutorService reader = null;
        try {
            directory = Files.createTempDirectory(tempRoot, "jibro-parser-");
            Path input = directory.resolve("input.bin");
            Files.write(input, bytes, StandardOpenOption.CREATE_NEW);
            var builder = new ProcessBuilder(worker.create(directory, input, mediaType));
            ParserProcesses.isolate(builder, directory);
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            reader = Executors.newSingleThreadExecutor(task -> {
                var thread = new Thread(task, "document-worker-output");
                thread.setDaemon(true);
                return thread;
            });
            Future<byte[]> output = reader.submit(() -> boundedOutput(running));
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                byte[] result = output.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.NANOSECONDS))
                    throw new TimeoutException("PARSER_TIMEOUT");
                if (process.exitValue() != 0) throw new IOException("PARSER_WORKER_FAILED");
                return ParserProtocol.read(result);
            } catch (ExecutionException failed) {
                throw new IOException("PARSER_OUTPUT_FAILED");
            }
        } finally {
            if (process != null) terminated = terminate(process);
            if (reader != null) reader.shutdownNow();
            try {
                if (terminated && directory != null) removeTemporaryFiles(directory);
            } finally {
                // Do not admit another worker if the OS did not confirm termination of this one.
                if (terminated) SLOTS.release();
            }
        }
    }

    static List<String> javaCommand(Path directory) {
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-Xms16m", "-Xmx128m", "-XX:MaxMetaspaceSize=64m", "-XX:MaxDirectMemorySize=16m", "-XX:ActiveProcessorCount=2", "-XX:+ExitOnOutOfMemoryError",
            "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory));
    }

    private static List<String> workerCommand(Path directory, Path input, String mediaType) throws IOException {
        Path jar = directory.resolve("worker.jar");
        String configured = System.getProperty("jibro.parser.worker-jar");
        if (configured != null) Files.copy(Path.of(configured), jar);
        else {
            try (InputStream embedded = BoundedDocumentParser.class.getResourceAsStream("/worker/document-parser-worker.jar")) {
                if (embedded == null) throw new IOException("PARSER_WORKER_UNAVAILABLE");
                Files.copy(embedded, jar);
            }
        }
        var command = javaCommand(directory);
        command.addAll(List.of("-jar", jar.toString(), input.toString(), mediaType, Long.toString(ProcessHandle.current().pid())));
        return command;
    }

    private static List<String> ocrWorkerCommand(Path directory, Path input, String mediaType, OcrConfiguration configuration) throws IOException {
        var command = workerCommand(directory, input, mediaType);
        command.replaceAll(value -> value.equals("-Xmx128m") ? "-Xmx256m" : value);
        command.addAll(List.of("ocr", configuration.executable().toString(), configuration.data().toString()));
        return command;
    }

    private static byte[] boundedOutput(Process process) throws IOException {
        try (var input = process.getInputStream(); var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = input.read(buffer)) != -1) {
                if (length > ParserProtocol.MAX_OUTPUT - bytes.size()) {
                    process.destroyForcibly();
                    throw new IOException("PARSER_OUTPUT_LIMIT");
                }
                bytes.write(buffer, 0, length);
            }
            return bytes.toByteArray();
        }
    }

    static boolean terminate(Process process) {
        return ParserProcesses.terminate(process);
    }

    private static void removeTemporaryFiles(Path directory) throws IOException {
        // Only this randomly created job directory is traversed. Files.walk does not follow symlinks.
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            List<Path> entries;
            try (var files = Files.walk(directory)) {
                entries = files.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path file : entries) {
                while (true) {
                    try {
                        Files.deleteIfExists(file);
                        break;
                    } catch (FileSystemException locked) {
                        // Windows may briefly retain the terminated worker's current-directory
                        // handle. Retry only cleanup, with one shared budget; never extend execution.
                        if (locked instanceof AccessDeniedException || !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                            || System.nanoTime() >= deadline) throw locked;
                        try {
                            TimeUnit.MILLISECONDS.sleep(10);
                        } catch (InterruptedException cancellation) {
                            interrupted = true;
                        }
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
