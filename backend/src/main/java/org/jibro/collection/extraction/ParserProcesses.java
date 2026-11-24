package org.jibro.collection.extraction;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Minimum process environment and bounded process-tree cleanup, shared by both isolated workers.
 */
final class ParserProcesses {
    private ParserProcesses() {
    }

    static void isolate(ProcessBuilder builder, Path directory) {
        String systemRoot = System.getenv("SystemRoot"), windows = System.getenv("WINDIR");
        builder.environment().clear();
        if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
        if (windows != null) builder.environment().put("WINDIR", windows);
        builder.environment().put("TEMP", directory.toString());
        builder.environment().put("TMP", directory.toString());
        builder.environment().put("TMPDIR", directory.toString());
        builder.directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
    }

    static boolean terminate(Process process) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<ProcessHandle> children = process.descendants().toList();
        try {
            children.forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            boolean parentStopped = !process.isAlive();
            while (System.nanoTime() < deadline) {
                try {
                    if (!parentStopped)
                        parentStopped = process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (parentStopped && children.stream().noneMatch(ProcessHandle::isAlive)) return true;
                    children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
            return parentStopped && children.stream().noneMatch(ProcessHandle::isAlive);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static boolean terminateChildren(ProcessHandle parent) {
        boolean interrupted = Thread.interrupted();
        var children = parent.descendants().toList();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        try {
            children.forEach(ProcessHandle::destroyForcibly);
            while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
            return children.stream().noneMatch(ProcessHandle::isAlive);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
