package org.jibro.collection.extraction;

import java.nio.file.*;
import java.util.*;

import static org.jibro.collection.extraction.ExtractionModels.Part;

/**
 * Intentionally hostile child processes exercise the real process supervisor, never production parsing.
 */
public final class ParserTestWorker {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
        switch (args[0]) {
            case "hang" -> Thread.sleep(60_000);
            case "flood" -> {
                for (int i = 0; i < 10_000; i++) System.out.write(new byte[8192]);
            }
            case "memory" -> {
                var blocks = new ArrayList<byte[]>();
                while (true) blocks.add(new byte[8 * 1024 * 1024]);
            }
            case "environment" ->
                    ParserProtocol.write(List.of(new Part("environment", String.join(",", new TreeSet<>(System.getenv().keySet())))), System.out);
            default -> throw new IllegalArgumentException();
        }
    }
}
