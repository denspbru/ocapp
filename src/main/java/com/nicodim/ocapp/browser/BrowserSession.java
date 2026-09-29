package com.nicodim.ocapp.browser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.openqa.selenium.chrome.ChromeDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BrowserSession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BrowserSession.class);
    private final ChromeDriver driver;
    private final Path profileDirectory;

    BrowserSession(ChromeDriver driver, Path profileDirectory) { this.driver = driver; this.profileDirectory = profileDirectory; }
    public ChromeDriver driver() { return driver; }

    @Override public void close() {
        try { driver.quit(); } catch (RuntimeException ex) { log.warn("Browser quit failed: {}", ex.getClass().getSimpleName()); }
        deleteDirectory(profileDirectory);
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
