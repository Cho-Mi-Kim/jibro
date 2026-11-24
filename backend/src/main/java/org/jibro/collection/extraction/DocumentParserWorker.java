package org.jibro.collection.extraction;

import java.nio.file.*;

/**
 * Standalone child JVM: no Spring context, network client, database or inherited configuration.
 */
public final class DocumentParserWorker {
    private DocumentParserWorker() {
    }

    public static void main(String[] args) {
        try {
            boolean ocr = args.length == 6 && args[3].equals("ocr");
            boolean page = args.length == 5 && args[3].equals("pdf-page");
            if (args.length != 3 && !ocr && !page) throw new IllegalArgumentException();
            long parent = Long.parseLong(args[2]);
            startWatchdog(parent, java.time.Duration.ofSeconds(ocr ? 305 : 10));
            Path input = Path.of(args[0]);
            if (!Files.isRegularFile(input) || Files.size(input) > ParserProtocol.MAX_INPUT)
                throw new IllegalArgumentException();
            var bytes = Files.readAllBytes(input);
            if (page) {
                if (!args[1].equals("application/pdf")) throw new IllegalArgumentException();
                PdfPageRenderer.write(bytes, Integer.parseInt(args[4]), System.out);
                return;
            }
            var parts = ocr ? optical(bytes, args[1], input.getParent(), args[4], args[5])
                    : new DocumentTextParser().parse(bytes, args[1]);
            ParserProtocol.write(parts, System.out);
        } catch (Throwable failure) {
            System.exit(2);
        }
    }

    static void startWatchdog(long parent, java.time.Duration timeout) {
        var watchdog = new Thread(() -> {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline && ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException stopped) {
                    break;
                }
            }
            // Terminate and await the OCR child before halting this JVM, including when the app died.
            ParserProcesses.terminateChildren(ProcessHandle.current());
            Runtime.getRuntime().halt(3);
        }, "parser-parent-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static java.util.List<ExtractionModels.Part> optical(byte[] bytes, String type, Path directory, String executable, String data) throws Exception {
        var configuration = OcrConfiguration.verified(Path.of(executable), Path.of(data));
        boolean hwp = type.equals("application/x-ole-storage");
        var recognizer = new TesseractOcr(directory, configuration, hwp ? 3 : 1);
        int alternateMode = hwp ? 11 : 6;
        var alternate = new TesseractOcr(directory, configuration, alternateMode);
        return new OcrDocumentParser(directory, recognizer::read, alternate::read, alternateMode,
                OcrDocumentParser.DOCUMENT_TIMEOUT.minusSeconds(5)).parse(bytes, type);
    }
}
