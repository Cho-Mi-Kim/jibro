package org.jibro.collection.extraction;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Fake local OCR executable used to verify process limits without a Tesseract installation. */
public final class OcrTestWorker {
    public static void main(String[] args) throws Exception {
        Path pid = Path.of(args[1]);
        Files.writeString(pid, Long.toString(ProcessHandle.current().pid()));
        switch (args[0]) {
            case "text" -> System.out.print("신청 서류 2026.11.01");
            case "environment" -> System.out.print(String.join(",", new TreeSet<>(System.getenv().keySet())));
            case "flood" -> { for (int i = 0; i < 100; i++) System.out.write(new byte[8192]); }
            case "hang" -> Thread.sleep(60_000);
            case "tree", "watchdog" -> {
                String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
                var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-cp", System.getProperty("java.class.path"), OcrTestWorker.class.getName(), "hang", pid + ".child").start();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!Files.exists(Path.of(pid + ".child")) && System.nanoTime() < deadline) Thread.sleep(10);
                if (args[0].equals("watchdog")) {
                    DocumentParserWorker.startWatchdog(Long.MAX_VALUE, Duration.ofSeconds(1));
                    // The watchdog kills the child before halting this JVM. Do not let main return
                    // with exit 0 when child.waitFor() wakes before the daemon calls halt(3).
                    Thread.sleep(60_000);
                    throw new IllegalStateException("Watchdog did not terminate the test worker");
                }
                child.waitFor();
            }
            default -> throw new IllegalArgumentException();
        }
    }
}
