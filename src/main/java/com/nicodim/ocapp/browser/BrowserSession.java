package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.support.ConversionException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

public final class BrowserSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);
    private final AtomicReference<ChromeDriver> driver = new AtomicReference<>();
    private final AtomicReference<ChromeDriverService> service = new AtomicReference<>();
    private final Path profileDirectory;
    private final Duration cleanupTimeout;
    private final ProcessSupervisor processSupervisor;
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.NEW);
    private final AtomicBoolean cleanupOwner = new AtomicBoolean();
    private final CountDownLatch cleanupFinished = new CountDownLatch(1);
    private final AtomicReference<CleanupResult> cleanupResult = new AtomicReference<>();

    BrowserSession(Path profileDirectory, Duration cleanupTimeout) {
        this.profileDirectory = profileDirectory;
        this.cleanupTimeout = cleanupTimeout;
        this.processSupervisor = this::terminateOwnedProcesses;
    }

    BrowserSession(Path profileDirectory, Duration cleanupTimeout, ProcessSupervisor processSupervisor) {
        this.profileDirectory = profileDirectory;
        this.cleanupTimeout = cleanupTimeout;
        this.processSupervisor = processSupervisor;
    }

    BrowserSession(ChromeDriver driver, ChromeDriverService service, Path profileDirectory, Duration cleanupTimeout) {
        this(profileDirectory, cleanupTimeout);
        attachService(service);
        attachDriver(driver);
    }

    BrowserSession(ChromeDriver driver, Path profileDirectory) {
        this(driver, null, profileDirectory, Duration.ofSeconds(1));
    }

    void attachService(ChromeDriverService value) {
        if (value == null) return;
        if (!service.compareAndSet(null, value)) throw new IllegalStateException("Browser service is already attached");
        if (lifecycle.get() == Lifecycle.CANCELLED && service.compareAndSet(value, null)) {
            runBounded("service stop", () -> stopService(value));
            mergeCleanupResult(terminateOwnedProcessesSafely(value));
        }
    }

    void attachDriver(ChromeDriver value) {
        if (value == null) throw new IllegalArgumentException("Browser driver is required");
        if (!driver.compareAndSet(null, value)) throw new IllegalStateException("Browser driver is already attached");
        if (lifecycle.get() == Lifecycle.CANCELLED && driver.compareAndSet(value, null)) {
            runBounded("quit", () -> quitDriver(value));
            mergeCleanupResult(terminateOwnedProcessesSafely(null));
        }
    }

    boolean beginStartup() { return lifecycle.compareAndSet(Lifecycle.NEW, Lifecycle.STARTING); }

    boolean finishStartup() { return lifecycle.compareAndSet(Lifecycle.STARTING, Lifecycle.ACTIVE); }

    public ChromeDriver driver() {
        ChromeDriver value = driver.get();
        if (value == null) throw new IllegalStateException("Browser driver is not ready");
        return value;
    }

    @Override public void close() {
        CleanupResult result = forceClose();
        if (!result.successful()) throw cleanupFailure();
    }

    public CleanupResult forceClose() {
        lifecycle.set(Lifecycle.CANCELLED);
        if (cleanupOwner.compareAndSet(false, true)) {
            CleanupResult result = CleanupResult.SUCCESS;
            try {
                ChromeDriver currentDriver = driver.getAndSet(null);
                if (currentDriver != null) runBounded("quit", () -> quitDriver(currentDriver));
                ChromeDriverService currentService = service.getAndSet(null);
                if (currentService != null) runBounded("service stop", () -> stopService(currentService));
                result = terminateOwnedProcessesSafely(currentService);
                if (result.successful()) deleteDirectory(profileDirectory);
            } finally {
                mergeCleanupResult(result);
                cleanupFinished.countDown();
            }
        } else {
            awaitCleanupOwner();
        }
        return cleanupResult.get() == null ? CleanupResult.PROCESS_TREE_SURVIVED : cleanupResult.get();
    }

    private void awaitCleanupOwner() {
        try {
            long millis = Math.max(1L, cleanupTimeout.toMillis()) * 4L;
            if (!cleanupFinished.await(millis, TimeUnit.MILLISECONDS)) {
                mergeCleanupResult(CleanupResult.PROCESS_TREE_SURVIVED);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            mergeCleanupResult(CleanupResult.PROCESS_TREE_SURVIVED);
        }
    }

    private void mergeCleanupResult(CleanupResult result) {
        cleanupResult.accumulateAndGet(result, (current, update) ->
            current == CleanupResult.PROCESS_TREE_SURVIVED || update == CleanupResult.PROCESS_TREE_SURVIVED
                ? CleanupResult.PROCESS_TREE_SURVIVED : CleanupResult.SUCCESS);
    }

    static ConversionException cleanupFailure() {
        return new ConversionException(HttpStatus.SERVICE_UNAVAILABLE, "BROWSER_CLEANUP_FAILED",
            "Browser process cleanup did not complete");
    }

    private void runBounded(String operation, Runnable action) {
        CountDownLatch finished = new CountDownLatch(1);
        Thread.ofVirtual().name("browser-" + operation.replace(' ', '-')).start(() -> {
            try { action.run(); }
            finally { finished.countDown(); }
        });
        try {
            if (!finished.await(cleanupTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Browser {} exceeded cleanup timeout", operation);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private CleanupResult terminateOwnedProcessesSafely(ChromeDriverService currentService) {
        try { return processSupervisor.terminate(currentService); }
        catch (RuntimeException ex) {
            log.warn("Browser process-tree termination failed: {}", ex.getClass().getSimpleName());
            return CleanupResult.PROCESS_TREE_SURVIVED;
        }
    }

    private CleanupResult terminateOwnedProcesses(ChromeDriverService currentService) {
        String profileMarker = profileDirectory == null ? "" : profileDirectory.toAbsolutePath().toString();
        String portMarker = "";
        if (currentService != null) {
            try { portMarker = "--port=" + currentService.getUrl().getPort(); }
            catch (RuntimeException ignored) { }
        }
        Set<ProcessHandle> tree = findOwnedProcesses(profileMarker, portMarker);
        terminate(tree, false);
        waitForExit(tree, cleanupTimeout);
        tree.addAll(findOwnedProcesses(profileMarker, portMarker));
        expandDescendants(tree);
        terminate(tree, true);
        waitForExit(tree, cleanupTimeout);
        tree.addAll(findOwnedProcesses(profileMarker, portMarker));
        expandDescendants(tree);
        return tree.stream().anyMatch(ProcessHandle::isAlive)
            ? CleanupResult.PROCESS_TREE_SURVIVED : CleanupResult.SUCCESS;
    }

    private static Set<ProcessHandle> findOwnedProcesses(String profileMarker, String portMarker) {
        Set<ProcessHandle> owned = new LinkedHashSet<>();
        ProcessHandle.current().descendants().filter(process -> {
            String command = process.info().commandLine().orElse("");
            return (!profileMarker.isEmpty() && command.contains(profileMarker))
                || (!portMarker.isEmpty() && command.contains(portMarker));
        }).forEach(process -> addProcessAndAncestors(process, owned));
        expandDescendants(owned);
        return owned;
    }

    private static void addProcessAndAncestors(ProcessHandle process, Set<ProcessHandle> owned) {
        ProcessHandle current = process;
        while (current.pid() != ProcessHandle.current().pid() && owned.add(current)) {
            current = current.parent().orElse(ProcessHandle.current());
        }
    }

    static CleanupResult terminateProcessTree(Set<ProcessHandle> processes, Duration timeout) {
        Set<ProcessHandle> tree = new LinkedHashSet<>(processes);
        expandDescendants(tree);
        terminate(tree, false);
        waitForExit(tree, timeout);
        expandDescendants(tree);
        terminate(tree, true);
        waitForExit(tree, timeout);
        expandDescendants(tree);
        return tree.stream().anyMatch(ProcessHandle::isAlive)
            ? CleanupResult.PROCESS_TREE_SURVIVED : CleanupResult.SUCCESS;
    }

    private static void expandDescendants(Set<ProcessHandle> processes) {
        Set<ProcessHandle> snapshot = new LinkedHashSet<>(processes);
        for (ProcessHandle process : snapshot) process.descendants().forEach(processes::add);
    }

    private static void terminate(Set<ProcessHandle> processes, boolean forcibly) {
        for (ProcessHandle process : processes) {
            if (!process.isAlive()) continue;
            if (forcibly) process.destroyForcibly();
            else process.destroy();
        }
    }

    private static void waitForExit(Set<ProcessHandle> processes, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try { Thread.sleep(Math.min(10L, Math.max(1L, timeout.toMillis()))); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static void quitDriver(ChromeDriver value) {
        try { value.quit(); }
        catch (RuntimeException ex) { log.warn("Browser quit failed: {}", ex.getClass().getSimpleName()); }
    }

    private static void stopService(ChromeDriverService value) {
        try { value.stop(); }
        catch (RuntimeException ex) { log.warn("Browser service stop failed: {}", ex.getClass().getSimpleName()); }
    }

    private enum Lifecycle { NEW, STARTING, ACTIVE, CANCELLED }

    @FunctionalInterface
    interface ProcessSupervisor { CleanupResult terminate(ChromeDriverService service); }

    public enum CleanupResult {
        SUCCESS, PROCESS_TREE_SURVIVED;
        public boolean successful() { return this == SUCCESS; }
    }

    static void deleteDirectory(Path directory) {
        if (directory == null) return;
        try {
            if (Files.exists(directory)) try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException ex) { log.warn("Temporary browser file cleanup failed"); }
                });
            }
        } catch (IOException ex) {
            log.warn("Temporary browser profile cleanup failed");
        }
    }
}
