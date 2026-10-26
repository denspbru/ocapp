package com.nicodim.ocapp.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs an explicitly configured local LibreOffice without listeners and with bounded resources. */
public final class LocalOfficeRunner {
    public static final int MAX_LOG_BYTES = 1024 * 1024;
    private static final Duration KILL_GRACE = Duration.ofSeconds(3);

    private LocalOfficeRunner() { }

    public static Result convertToPdf(Path executable, Path input, Path outputDirectory, Path log, Duration timeout,
                                      long maxPdfBytes) throws IOException, InterruptedException {
        requirePositive(timeout);
        if (maxPdfBytes < 1 || maxPdfBytes > 512L * 1024 * 1024)
            throw new IllegalArgumentException("maxPdfBytes must be between 1 and 536870912");
        Path office = executable.toAbsolutePath().normalize();
        Path source = input.toAbsolutePath().normalize();
        Path output = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isRegularFile(office) || !Files.isExecutable(office))
            throw new IOException("Configured LibreOffice is not an executable file: " + office);
        if (!Files.isRegularFile(source)) throw new IOException("Office input is not a regular file: " + source);
        Files.createDirectories(output);
        Path logFile = log.toAbsolutePath().normalize();
        Files.createDirectories(logFile.getParent());
        Path profile = Files.createTempDirectory(output, ".libreoffice-profile-");
        Path temp = Files.createDirectories(profile.resolve("tmp"));
        Path pdf = output.resolve(baseName(source) + ".pdf");
        Files.deleteIfExists(pdf);

        List<String> command = List.of(office.toString(), "--headless", "--invisible", "--nologo", "--nodefault",
            "--nolockcheck", "--norestore", "--nofirststartwizard", "-env:UserInstallation=" + profile.toUri(),
            "--convert-to", "pdf:impress_pdf_Export", "--outdir", output.toString(), source.toString());
        Process process = null;
        BoundedCapture capture = new BoundedCapture(MAX_LOG_BYTES);
        Thread drainer = null;
        ProcessTree tree = null;
        long started = System.nanoTime();
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Map<String,String> environment = builder.environment();
            environment.put("HOME", profile.toString());
            environment.put("TMPDIR", temp.toString());
            environment.put("SAL_USE_VCLPLUGIN", "svp");
            environment.put("SAL_DISABLE_OPENCL", "1");
            // The fixture contract is self-contained. Invalid proxies make accidental URL fetches fail closed.
            environment.put("http_proxy", "http://127.0.0.1:9");
            environment.put("https_proxy", "http://127.0.0.1:9");
            environment.put("HTTP_PROXY", "http://127.0.0.1:9");
            environment.put("HTTPS_PROXY", "http://127.0.0.1:9");
            environment.put("NO_PROXY", "");
            process = builder.start();
            tree = new ProcessTree(process.toHandle());
            InputStream stdout = process.getInputStream();
            drainer = Thread.ofVirtual().name("libreoffice-output-drainer").start(() -> capture.drain(stdout));
            if (!tree.waitForRoot(process, timeout))
                throw new IOException("LibreOffice timed out after " + timeout.toSeconds() + "s; log=" + logFile);
            drainer.join(KILL_GRACE.toMillis());
            writeLog(logFile, capture);
            if (process.exitValue() != 0)
                throw new IOException("LibreOffice exited " + process.exitValue() + "; log=" + logFile + "\n" + capture.text());
            if (!Files.isRegularFile(pdf) || Files.size(pdf) == 0)
                throw new IOException("LibreOffice did not create a non-empty PDF: " + pdf + "; log=" + logFile);
            long pdfBytes = Files.size(pdf);
            if (pdfBytes > maxPdfBytes)
                throw new IOException("LibreOffice PDF exceeds configured byte limit: bytes=" + pdfBytes + " limit=" + maxPdfBytes);
            return new Result(pdf, logFile, Duration.ofNanos(System.nanoTime() - started), capture.truncated(), pdfBytes);
        } finally {
            try {
                if (tree != null) tree.terminateAndVerify(KILL_GRACE);
            } finally {
                try {
                    if (drainer != null && drainer.isAlive()) drainer.join(KILL_GRACE.toMillis());
                } finally {
                    try { writeLog(logFile, capture); }
                    finally { deleteTree(profile); }
                }
            }
        }
    }

    public static String version(Path executable, Duration timeout) throws IOException, InterruptedException {
        requirePositive(timeout);
        Path office = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(office) || !Files.isExecutable(office))
            throw new IOException("Configured LibreOffice is not an executable file: " + office);
        Process process = new ProcessBuilder(office.toString(), "--version").redirectErrorStream(true).start();
        BoundedCapture capture = new BoundedCapture(16 * 1024);
        Thread drainer = Thread.ofVirtual().start(() -> capture.drain(process.getInputStream()));
        ProcessTree tree = new ProcessTree(process.toHandle());
        try {
            if (!tree.waitForRoot(process, timeout)) throw new IOException("LibreOffice version probe timed out");
            drainer.join(KILL_GRACE.toMillis());
            if (process.exitValue() != 0) throw new IOException("LibreOffice version probe exited " + process.exitValue());
            return capture.text().trim();
        } finally {
            tree.terminateAndVerify(KILL_GRACE);
            if (drainer.isAlive()) drainer.join(KILL_GRACE.toMillis());
        }
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    private static String baseName(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static final class ProcessTree {
        private static final long POLL_MILLIS = 25;
        private static final long QUIET_MILLIS = 100;
        private final ProcessHandle root;
        private final Map<Long, ProcessHandle> known = new LinkedHashMap<>();

        ProcessTree(ProcessHandle root) {
            this.root = root;
            known.put(root.pid(), root);
        }

        private boolean waitForRoot(Process process, Duration timeout) throws InterruptedException {
            long deadline = deadline(timeout);
            while (process.isAlive()) {
                discover();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                process.waitFor(Math.min(POLL_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS);
            }
            discover();
            return true;
        }

        void terminateAndVerify(Duration timeout) throws IOException, InterruptedException {
            long started = System.nanoTime();
            long deadline = deadline(timeout);
            long forceAt = started + Math.min(TimeUnit.SECONDS.toNanos(1), timeout.toNanos() / 2);
            long quietSince = -1;
            while (System.nanoTime() < deadline) {
                int before = known.size();
                discover();
                List<ProcessHandle> alive = alive();
                long now = System.nanoTime();
                if (alive.isEmpty()) {
                    if (known.size() != before || quietSince < 0) quietSince = now;
                    if (now - quietSince >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS)) {
                        discover();
                        if (alive().isEmpty()) return;
                        quietSince = -1;
                    }
                } else {
                    quietSince = -1;
                    boolean force = now >= forceAt;
                    alive.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
                    for (ProcessHandle handle : alive) {
                        if (force) handle.destroyForcibly(); else handle.destroy();
                    }
                }
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) Thread.sleep(Math.min(POLL_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
            discover();
            List<ProcessHandle> survivors = alive();
            if (!survivors.isEmpty())
                throw new IOException("LibreOffice process cleanup failed; survivingProcesses=" + survivors.size());
        }

        private void discover() {
            List<ProcessHandle> snapshot = new ArrayList<>(known.values());
            for (ProcessHandle handle : snapshot) {
                try { handle.descendants().forEach(child -> known.putIfAbsent(child.pid(), child)); }
                catch (RuntimeException ignored) { /* A concurrently exiting process can reject inspection. */ }
            }
        }

        private List<ProcessHandle> alive() {
            return new ArrayList<>(known.values().stream().filter(ProcessHandle::isAlive).toList());
        }

        private static long deadline(Duration timeout) {
            long now = System.nanoTime();
            try { return Math.addExact(now, timeout.toNanos()); }
            catch (ArithmeticException ex) { return Long.MAX_VALUE; }
        }
    }

    private static void writeLog(Path log, BoundedCapture capture) throws IOException {
        if (log == null) return;
        String suffix = capture.truncated() ? "\n[output truncated at " + capture.limit + " bytes]\n" : "";
        Files.writeString(log, capture.text() + suffix, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class BoundedCapture {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile boolean truncated;
        private BoundedCapture(int limit) { this.limit = limit; }
        private void drain(InputStream input) {
            try (input) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    synchronized (bytes) {
                        int retained = Math.min(read, Math.max(0, limit - bytes.size()));
                        if (retained > 0) bytes.write(buffer, 0, retained);
                        if (retained < read) truncated = true;
                    }
                }
            } catch (IOException ignored) { truncated = true; }
        }
        private String text() { synchronized (bytes) { return bytes.toString(StandardCharsets.UTF_8); } }
        private boolean truncated() { return truncated; }
    }

    public record Result(Path pdf, Path log, Duration elapsed, boolean logTruncated, long pdfBytes) { }
}
