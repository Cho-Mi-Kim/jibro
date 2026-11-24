package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;

class BoundedDocumentParserTest {
    @TempDir
    Path root;

    @Test
    void realStandaloneJarParsesDocumentsAndRecoversAfterBadInput() throws Exception {
        var parser = new BoundedDocumentParser();
        assertThrows(Exception.class, () -> parser.parse(new byte[]{1, 2, 3}, "application/pdf"));
        var parts = parser.parse(hwpx(), "application/zip");
        assertEquals("제출 서류 확인\n", parts.get(0).text());
        assertTrue(parts.get(0).location().contains("Contents/section0.xml"));
        assertTrue(parser.parse(DocumentTextParserTest.pdf("Isolated PDF"), "application/pdf").get(0).text().contains("Isolated PDF"));
        assertTrue(parser.parse(DocumentTextParserTest.hwp("격리 HWP 확인", 1), "application/x-ole-storage").get(0).text().contains("격리 HWP 확인"));
    }

    @Test
    void standaloneWorkerIncludesTheOfficialPictureAndNumberingReaders() throws Exception {
        var parser = new BoundedDocumentParser();
        for (String file : List.of("lh-multichild-2026.hwpx", "lh-multichild-two-up.pdf")) {
            try (var input = getClass().getResourceAsStream("/extraction/" + file)) {
                var parts = parser.parse(input.readAllBytes(), file.endsWith(".pdf") ? "application/pdf" : "application/zip");
                assertTrue(parts.stream().anyMatch(p -> p.text().contains("→")), file);
            }
        }
    }

    @Test
    void timeoutKillsNonCooperativeProcessRemovesFilesAndAllowsNextJob() throws Exception {
        Path pid = root.resolve("timeout.pid");
        var parser = helper("hang", pid, Duration.ofSeconds(2));
        assertThrows(TimeoutException.class, () -> parser.parse(new byte[1], "application/pdf"));
        assertDead(pid);
        assertNoJobDirectories();
        assertFalse(new BoundedDocumentParser().parse(hwpx(), "application/zip").isEmpty());
    }

    @Test
    void outputFloodAndMemoryExhaustionCannotTakeDownTheApplication() throws Exception {
        for (String mode : List.of("flood", "memory")) {
            Path pid = root.resolve(mode + ".pid");
            var parser = helper(mode, pid, Duration.ofSeconds(5));
            assertThrows(IOException.class, () -> parser.parse(new byte[1], "application/pdf"));
            assertDead(pid);
            assertNoJobDirectories();
        }
        assertFalse(new BoundedDocumentParser().parse(hwpx(), "application/zip").isEmpty());
    }

    @Test
    void childReceivesOnlyMinimumOperatingSystemEnvironment() throws Exception {
        Path pid = root.resolve("env.pid");
        var result = helper("environment", pid, Duration.ofSeconds(5)).parse(new byte[1], "application/pdf");
        Set<String> names = new HashSet<>(Arrays.asList(result.get(0).text().toUpperCase(Locale.ROOT).split(",")));
        assertTrue(Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR").containsAll(names), names.toString());
        assertTrue(names.contains("TMPDIR"));
        assertDead(pid);
        assertNoJobDirectories();
    }

    @Test
    void invalidOpticalConfigurationPreservesNativePartsAndMarksUnreadImageContent() throws Exception {
        String executable = System.getProperty("jibro.ocr.executable"), data = System.getProperty("jibro.ocr.data-path");
        try {
            System.setProperty("jibro.ocr.executable", root.resolve("not-installed.exe").toString());
            System.setProperty("jibro.ocr.data-path", root.resolve("missing-models").toString());
            var parts = new BoundedDocumentParser().parse(OcrDocumentParserTest.imagePdf(1, true), "application/pdf");
            assertTrue(parts.stream().anyMatch(p -> p.text().contains("123.45")));
            assertTrue(OcrProvenance.incomplete(parts));
            assertTrue(parts.stream().anyMatch(p -> p.location().contains("OCR_CONFIGURATION_INVALID")));
        } finally {
            if (executable == null) System.clearProperty("jibro.ocr.executable");
            else System.setProperty("jibro.ocr.executable", executable);
            if (data == null) System.clearProperty("jibro.ocr.data-path");
            else System.setProperty("jibro.ocr.data-path", data);
        }
    }

    @RepeatedTest(5)
    void concurrentWorkersAreBoundedAndCancellationTerminatesBoth() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        Path first = root.resolve("first.pid"), second = root.resolve("second.pid");
        var failures = new ConcurrentLinkedQueue<Exception>();
        Future<?> one = executor.submit(() -> runHang(first, failures));
        Future<?> two = executor.submit(() -> runHang(second, failures));
        try {
            awaitFile(first);
            awaitFile(second);
            assertThrows(RejectedExecutionException.class, () -> new BoundedDocumentParser().parse(hwpx(), "application/zip"));
        } finally {
            one.cancel(true);
            two.cancel(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertDead(first);
        assertDead(second);
        assertNoJobDirectories();
        assertFalse(new BoundedDocumentParser().parse(hwpx(), "application/zip").isEmpty());
    }

    @Test
    void repeatedCancellationStillWaitsForTerminationAndRestoresInterrupt() throws Exception {
        Process process = mock(Process.class);
        when(process.descendants()).thenReturn(java.util.stream.Stream.empty());
        when(process.isAlive()).thenReturn(true);
        when(process.waitFor(anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new InterruptedException("second cancellation")).thenReturn(true);
        try {
            assertTrue(BoundedDocumentParser.terminate(process));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(process).destroyForcibly();
            verify(process, times(2)).waitFor(anyLong(), eq(TimeUnit.NANOSECONDS));
        } finally {
            Thread.interrupted();
        }
    }

    private void runHang(Path pid, Queue<Exception> failures) {
        try {
            helper("hang", pid, Duration.ofSeconds(20)).parse(new byte[1], "application/pdf");
        } catch (
                InterruptedException expected) { /* Cancellation must still complete the supervisor's cleanup. */ } catch (
                Exception failure) {
            failures.add(failure);
        }
    }

    private BoundedDocumentParser helper(String mode, Path pid, Duration timeout) {
        return new BoundedDocumentParser(timeout, root, (directory, input, media) -> {
            var command = BoundedDocumentParser.javaCommand(directory);
            command.addAll(List.of("-cp", System.getProperty("jibro.parser.test-classpath"), ParserTestWorker.class.getName(), mode, pid.toString()));
            return command;
        });
    }

    private void assertDead(Path pid) throws IOException {
        assertTrue(Files.exists(pid), "child must actually have started");
        long value = Long.parseLong(Files.readString(pid));
        assertFalse(ProcessHandle.of(value).map(ProcessHandle::isAlive).orElse(false));
    }

    private void assertNoJobDirectories() throws IOException {
        try (var files = Files.list(root)) {
            assertTrue(files.noneMatch(Files::isDirectory));
        }
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(path));
    }

    private static byte[] hwpx() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("Contents/section0.xml"));
            zip.write("<root><p><t>제출 서류 확인</t></p></root>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
