package org.jibro.collection.extraction;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TesseractOcrTest {
    @TempDir
    Path root;

    @Test
    void readsUtf8AndReceivesNoApplicationSecretsOrInheritedPath() throws Exception {
        assertEquals("신청 서류 2026.11.01", helper("text").read(root.resolve("image.png"), Duration.ofSeconds(5)));
        String result = helper("environment").read(root.resolve("image.png"), Duration.ofSeconds(5));
        var names = new HashSet<>(Arrays.asList(result.toUpperCase(Locale.ROOT).split(",")));
        assertTrue(Set.of("SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR", "OMP_THREAD_LIMIT").containsAll(names));
        assertFalse(names.contains("PATH"));
        assertFalse(names.contains("LH_SERVICE_KEY"));
        assertFalse(names.contains("JAVA_TOOL_OPTIONS"));
    }

    @Test
    void timeoutKillsTheOcrProcessAndItsDescendant() throws Exception {
        assertThrows(TimeoutException.class, () -> helper("tree").read(root.resolve("image.png"), Duration.ofSeconds(3)));
        assertDead("tree.pid");
        assertDead("tree.pid.child");
    }

    @Test
    void watchdogAwaitsDescendantTerminationWhenTheOwningApplicationIsGone() throws Exception {
        assertThrows(IOException.class, () -> helper("watchdog").read(root.resolve("image.png"), Duration.ofSeconds(6)));
        assertDead("watchdog.pid");
        assertDead("watchdog.pid.child");
    }

    @Test
    void outputFloodIsBoundedAndDoesNotPreventTheNextRead() throws Exception {
        assertThrows(IOException.class, () -> helper("flood").read(root.resolve("image.png"), Duration.ofSeconds(5)));
        assertDead("flood.pid");
        assertTrue(helper("text").read(root.resolve("image.png"), Duration.ofSeconds(5)).contains("2026"));
    }

    @Test
    void configurationRequiresAbsoluteExecutableAndAllThreeLocalModels() throws Exception {
        Path executable = Files.write(root.resolve("ocr.exe"), new byte[0]);
        Path data = Files.createDirectory(root.resolve("tessdata"));
        assertThrows(IOException.class, () -> OcrConfiguration.verified(Path.of("ocr.exe"), data));
        Files.write(data.resolve("kor.traineddata"), new byte[0]);
        Files.write(data.resolve("eng.traineddata"), new byte[0]);
        assertThrows(IOException.class, () -> OcrConfiguration.verified(executable, data));
        Files.write(data.resolve("osd.traineddata"), new byte[0]);
        assertEquals(executable, OcrConfiguration.verified(executable, data).executable());
    }

    private TesseractOcr helper(String mode) {
        return new TesseractOcr(root, image -> {
            var command = BoundedDocumentParser.javaCommand(root);
            command.addAll(List.of("-cp", System.getProperty("jibro.parser.test-classpath"), OcrTestWorker.class.getName(), mode, root.resolve(mode + ".pid").toString()));
            return command;
        });
    }

    private void assertDead(String file) throws Exception {
        assertTrue(Files.exists(root.resolve(file)), "The real child process must have started");
        long pid = Long.parseLong(Files.readString(root.resolve(file)));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
}
