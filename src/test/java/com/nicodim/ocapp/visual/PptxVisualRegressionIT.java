package com.nicodim.ocapp.visual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.testsupport.LocalOfficeRunner;
import com.nicodim.ocapp.testsupport.PdfPageRenderer;
import com.nicodim.ocapp.testsupport.VisualBaselineManifest;
import com.nicodim.ocapp.testsupport.VisualImageComparator;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PptxVisualRegressionIT {
    private static final Path TARGET = Path.of("target", "pptx-visual-regression").toAbsolutePath().normalize();

    @Test void syntheticDeckMatchesChecksumProtectedApprovedBaselines() throws Exception {
        VisualBaselineManifest manifest = manifest();
        Properties fixtureMetadata = fixtureMetadata();
        assertThat(fixtureMetadata).containsEntry("fixture", manifest.fixture())
            .containsEntry("external.resources", "none").containsEntry("pages", Integer.toString(manifest.pageCount()));
        assertThat(manifest.dpi()).isEqualTo((int) PdfPageRenderer.DPI);
        String configured = System.getProperty("ocapp.visual.libreoffice", "").trim();
        assertThat(configured).withFailMessage("Visual regression is fail-closed: set -Docapp.visual.libreoffice=/absolute/path/to/soffice").isNotBlank();
        Path office = Path.of(configured).toAbsolutePath().normalize();
        String version = LocalOfficeRunner.version(office, Duration.ofSeconds(10));
        assertThat(version).as("pinned visual renderer version").isEqualTo(manifest.rendererVersion());

        Path work = freshWork("synthetic-mixed");
        Path deck = SyntheticVisualFixture.create(work.resolve("synthetic-mixed.pptx"));
        LocalOfficeRunner.Result conversion = LocalOfficeRunner.convertToPdf(office, deck, work,
            work.resolve("libreoffice.log"), Duration.ofSeconds(45), visualPdfByteLimit());
        List<Path> candidates = PdfPageRenderer.render(conversion.pdf(), work.resolve("candidates"), manifest.fixture());
        assertThat(candidates).hasSize(manifest.pageCount());

        for (int page = 1; page <= manifest.pageCount(); page++) {
            Path baseline = copyBaselineToTarget(manifest, page, work.resolve("approved"));
            manifest.verifyBaseline(page, baseline);
            VisualImageComparator.Comparison comparison = VisualImageComparator.compare(baseline, candidates.get(page - 1),
                Path.of("target"), work.resolve("artifacts/page-" + page), manifest.channelTolerance(), manifest.changedPixelRatio());
            assertThat(comparison.passed()).withFailMessage("Visual page %s differs: ratio=%s diff=%s metadata=%s",
                page, comparison.changedRatio(), comparison.diff(), comparison.metadata()).isTrue();
        }
        Files.writeString(work.resolve("run-metadata.properties"), "format=1\nfixture=" + manifest.fixture()
            + "\nrenderer.version=" + version + "\ndpi=" + (int) PdfPageRenderer.DPI + "\npages=" + candidates.size()
            + "\nnetwork=disabled-by-self-contained-fixture-and-invalid-proxy\nlistener=disabled\n");
    }

    @Test void baselineFailuresAreClosedAndCandidatesCannotEscapeTarget() throws Exception {
        Path work = freshWork("support-contract");
        Path image = image(work.resolve("candidate.png"), Color.BLUE);
        Path missing = work.resolve("missing.png");
        assertThatThrownBy(() -> manifest().verifyBaseline(1, missing)).isInstanceOf(IOException.class).hasMessageContaining("missing");
        Path corrupt = image(work.resolve("corrupt.png"), Color.RED);
        assertThatThrownBy(() -> manifest().verifyBaseline(1, corrupt)).isInstanceOf(IOException.class).hasMessageContaining("checksum mismatch");
        assertThatThrownBy(() -> VisualImageComparator.compare(corrupt, image,
            TARGET.resolve("different-root"), work.resolve("artifacts"), 0, 0)).isInstanceOf(IOException.class)
            .hasMessageContaining("under target");
    }

    @Test void officeRunnerRejectsOversizedPdfBeforeRendering() throws Exception {
        Path work = freshWork("office-output-bound");
        Path input = Files.writeString(work.resolve("oversized.pptx"), "fixture");
        Path office = fakeOffice(work.resolve("fake-office.sh"),
            "dd if=/dev/zero of=\"$out/$base.pdf\" bs=32 count=1 2>/dev/null");
        assertThatThrownBy(() -> LocalOfficeRunner.convertToPdf(office, input, work, work.resolve("office.log"),
            Duration.ofSeconds(5), 16)).isInstanceOf(IOException.class)
            .hasMessageContaining("exceeds configured byte limit").hasMessageContaining("limit=16");
        assertThat(Files.size(work.resolve("oversized.pdf"))).isEqualTo(32);
        assertNoOfficeProfiles(work);
    }

    @Test void officeRunnerTracksAndTerminatesOrphanAfterNormalParentExit() throws Exception {
        Path work = freshWork("office-process-cleanup");
        Path input = Files.writeString(work.resolve("orphan.pptx"), "fixture");
        Path office = fakeOffice(work.resolve("fake-office.sh"),
            "sleep 30 &\necho $! > \"$out/orphan.pid\"\nsleep 1\nprintf pdf > \"$out/$base.pdf\"");
        var result = LocalOfficeRunner.convertToPdf(office, input, work, work.resolve("office.log"),
            Duration.ofSeconds(5), 1024);
        long orphanPid = Long.parseLong(Files.readString(work.resolve("orphan.pid")).trim());
        assertThat(result.pdfBytes()).isEqualTo(3);
        assertThat(ProcessHandle.of(orphanPid)).isEmpty();
        assertNoOfficeProfiles(work);
    }

    @Test void comparatorProducesDeterministicBoundedDiffMetadata() throws Exception {
        Path work = freshWork("comparator-contract");
        Path approved = image(work.resolve("approved.png"), Color.WHITE);
        Path candidate = image(work.resolve("candidate.png"), new Color(250, 255, 255));
        VisualImageComparator.Comparison first = VisualImageComparator.compare(approved, candidate, Path.of("target"),
            work.resolve("first"), 2, 0);
        VisualImageComparator.Comparison second = VisualImageComparator.compare(approved, candidate, Path.of("target"),
            work.resolve("second"), 2, 0);
        assertThat(first.passed()).isFalse();
        assertThat(first.changedPixels()).isEqualTo(64).isEqualTo(second.changedPixels());
        assertThat(Files.readAllBytes(first.diff())).isEqualTo(Files.readAllBytes(second.diff()));
        assertThat(Files.readString(first.metadata())).isEqualTo(Files.readString(second.metadata()));
    }

    private static Path freshWork(String name) throws IOException {
        Path work = TARGET.resolve(name);
        if (Files.exists(work)) {
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return Files.createDirectories(work);
    }

    private static void assertNoOfficeProfiles(Path work) throws IOException {
        try (var paths = Files.list(work)) {
            assertThat(paths.filter(path -> path.getFileName().toString().startsWith(".libreoffice-profile-")).toList()).isEmpty();
        }
    }

    private static Path fakeOffice(Path target, String body) throws IOException {
        String script = "#!/bin/sh\nout=''\ninput=''\nwhile [ \"$#\" -gt 0 ]; do\n"
            + "  if [ \"$1\" = '--outdir' ]; then shift; out=\"$1\"; fi\n"
            + "  input=\"$1\"\n  shift\ndone\n"
            + "name=$(basename \"$input\")\nbase=${name%.*}\n" + body + "\n";
        Files.writeString(target, script);
        if (!target.toFile().setExecutable(true, true)) throw new IOException("Cannot make fake office executable");
        return target;
    }

    private static long visualPdfByteLimit() {
        String configured = System.getProperty("ocapp.visual.maxPdfBytes", "33554432");
        try {
            long value = Long.parseLong(configured);
            if (value < 1 || value > 512L * 1024 * 1024) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("ocapp.visual.maxPdfBytes must be between 1 and 536870912");
        }
    }

    private static VisualBaselineManifest manifest() throws IOException {
        return VisualBaselineManifest.load(PptxVisualRegressionIT.class.getResourceAsStream("/visual-regression/synthetic-mixed.manifest.properties"));
    }
    private static Properties fixtureMetadata() throws IOException {
        Properties metadata = new Properties();
        try (InputStream input = PptxVisualRegressionIT.class.getResourceAsStream("/visual-regression/synthetic-mixed.fixture.properties")) {
            if (input == null) throw new IOException("Synthetic fixture metadata is missing");
            metadata.load(input);
        }
        return metadata;
    }
    private static Path copyBaselineToTarget(VisualBaselineManifest manifest, int page, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("page-" + page + ".png");
        try (InputStream input = PptxVisualRegressionIT.class.getResourceAsStream(manifest.baselineResource(page))) {
            if (input == null) throw new IOException("Approved baseline resource is missing: " + manifest.baselineResource(page));
            Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
    private static Path image(Path target, Color color) throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, 0, 8, 8); graphics.dispose();
        if (!ImageIO.write(image, "png", target.toFile())) throw new IOException("PNG writer unavailable");
        return target;
    }
}
