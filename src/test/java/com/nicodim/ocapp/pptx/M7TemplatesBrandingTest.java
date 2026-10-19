package com.nicodim.ocapp.pptx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nicodim.ocapp.config.ConverterProperties;
import com.nicodim.ocapp.pagemodel.Bounds;
import com.nicodim.ocapp.pagemodel.CaptureMetadata;
import com.nicodim.ocapp.pagemodel.DocumentMetadata;
import com.nicodim.ocapp.pagemodel.PageGeometry;
import com.nicodim.ocapp.pagemodel.PageModel;
import com.nicodim.ocapp.pagination.BreakRationale;
import com.nicodim.ocapp.pagination.PaginationPlan;
import com.nicodim.ocapp.pagination.SlideSlice;
import com.nicodim.ocapp.pagination.SlideViewport;
import com.nicodim.ocapp.pagination.SourceBounds;
import com.nicodim.ocapp.support.ConversionException;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.SlideLayout;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class M7TemplatesBrandingTest {
    @TempDir Path temporary;

    @Test void stablePathFailuresRejectMissingRelativeExtensionAndSymlinkWithoutDetails() throws Exception {
        assertFailure(properties(temporary.resolve("missing.pptx")), "PPTX_TEMPLATE_NOT_FOUND", HttpStatus.SERVICE_UNAVAILABLE);
        assertFailure(properties(Path.of("relative.pptx")), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        assertFailure(properties(temporary.resolve("template.ppt")), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path target = writeTemplate(temporary.resolve("target.pptx"), false);
        Path link = temporary.resolve("linked.pptx");
        Files.createSymbolicLink(link, target);
        assertFailure(properties(link), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path linkedDirectory = temporary.resolve("linked-directory");
        Files.createSymbolicLink(linkedDirectory, temporary);
        assertFailure(properties(linkedDirectory.resolve("target.pptx")), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test void unreadableTemplateHasStableServiceError() throws Exception {
        Path template = writeTemplate(temporary.resolve("unreadable.pptx"), false);
        var original = Files.getPosixFilePermissions(template);
        try {
            Files.setPosixFilePermissions(template, java.util.Set.of());
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(template),
                "Current filesystem identity can still read mode-000 files");
            assertFailure(properties(template), "PPTX_TEMPLATE_UNREADABLE", HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            Files.setPosixFilePermissions(template, original);
        }
    }

    @Test void validatesTemplateCachesImmutableBytesAndOpensFreshShowsConcurrently() throws Exception {
        Path template = writeTemplate(temporary.resolve("brand.pptx"), false);
        ConverterProperties properties = properties(template);
        PptxTemplateLoader loader = new PptxTemplateLoader(properties);
        var snapshot = loader.preflight();
        assertThat(snapshot.originalSlides()).isZero();
        assertThat(snapshot.entries()).isPositive();
        assertThat(snapshot.uncompressedBytes()).isPositive();
        byte[] exposed = snapshot.bytes(); exposed[0] = 0;
        assertThat(snapshot.bytes()[0]).isEqualTo((byte) 'P');
        try (var show = loader.openFresh()) {
            assertThat(show.getSlides()).isEmpty();
            assertThat(show.getSlideMasters()).hasSize(1);
            assertThat(loader.selectLayout(show, "section").getType()).isEqualTo(SlideLayout.SECTION_HEADER);
            properties.getPptx().getLayouts().setDefaultAlias("title");
            assertThat(loader.selectLayout(show, "blank").getType()).isEqualTo(SlideLayout.BLANK);
        }
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 12; i++) calls.add(() -> { try (var show = loader.openFresh()) {
                show.createSlide(loader.selectLayout(show, "content")); return show.getSlides().size();
            }});
            assertThat(executor.invokeAll(calls)).allSatisfy(future -> assertThat(future.get()).isEqualTo(1));
        } finally { executor.shutdownNow(); }
    }

    @Test void originalSlidesAreRejectedByDefaultOrRemovedInReverse() throws Exception {
        Path template = writeTemplate(temporary.resolve("slides.pptx"), true);
        assertFailure(properties(template), "PPTX_TEMPLATE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);
        ConverterProperties remove = properties(template);
        remove.getPptx().getTemplate().setOriginalSlidePolicy(ConverterProperties.Pptx.Template.OriginalSlidePolicy.REMOVE);
        try (var show = new PptxTemplateLoader(remove).openFresh()) {
            assertThat(show.getSlides()).isEmpty();
            assertThat(show.getSlideMasters()).isNotEmpty();
        }
        PptxTemplateLoader cached = new PptxTemplateLoader(remove);
        cached.preflight();
        remove.getPptx().getTemplate().setOriginalSlidePolicy(ConverterProperties.Pptx.Template.OriginalSlidePolicy.REJECT);
        assertThatThrownBy(cached::openFresh).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("PPTX_TEMPLATE_INVALID");
    }

    @Test void prePoiZipGateRejectsTraversalExternalMacroEmbeddedAndBounds() throws Exception {
        Path base = writeTemplate(temporary.resolve("base.pptx"), false);
        Path traversal = addZipEntry(base, temporary.resolve("traversal.pptx"), "../escape.xml", "x");
        assertFailure(properties(traversal), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path external = addZipEntry(base, temporary.resolve("external.pptx"), "_rels/evil.rels",
            "<Relationships><Relationship TargetMode=\"External\" Target=\"https://secret.test/x\"/></Relationships>");
        assertFailure(properties(external), "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path macro = addZipEntry(base, temporary.resolve("macro.pptx"), "ppt/vbaProject.bin", "bad");
        assertFailure(properties(macro), "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path embedded = addZipEntry(base, temporary.resolve("embedded.pptx"), "ppt/embeddings/object.bin", "bad");
        assertFailure(properties(embedded), "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path duplicate = addZipEntry(base, temporary.resolve("duplicate.pptx"), "ppt/../ppt/presentation.xml", "duplicate");
        assertFailure(properties(duplicate), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path absolute = addZipEntry(base, temporary.resolve("absolute.pptx"), "/operator/secret.xml", "x");
        assertFailure(properties(absolute), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path linkedMedia = addZipEntry(base, temporary.resolve("linked-media.pptx"), "ppt/_rels/linked.rels",
            "<Relationships><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/video\" Target=\"media/movie.mp4\"/></Relationships>");
        assertFailure(properties(linkedMedia), "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path compressed = addZipEntry(base, temporary.resolve("ratio.pptx"), "docProps/repeated.txt", "x".repeat(20_000));
        ConverterProperties ratio = properties(compressed);
        ratio.getPptx().getTemplate().setMaxCompressionRatio(2);
        assertFailure(ratio, "PPTX_TEMPLATE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
        ConverterProperties bounded = properties(base);
        bounded.getPptx().getTemplate().setMaxEntries(1);
        assertFailure(bounded, "PPTX_TEMPLATE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
        bounded = properties(base);
        bounded.getPptx().getTemplate().setMaxBytes(1024);
        assertFailure(bounded, "PPTX_TEMPLATE_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test void validatesConfiguredAliasesAndUsesPageThenGlobalThenContentThenBlank() throws Exception {
        Path template = writeTemplate(temporary.resolve("aliases.pptx"), false);
        ConverterProperties properties = properties(template);
        properties.getPptx().getLayouts().setDefaultAlias("title");
        properties.getPptx().getLayouts().setAliases(java.util.Map.of("closing", "Blank"));
        PptxTemplateLoader loader = new PptxTemplateLoader(properties);
        try (var show = loader.openFresh()) {
            assertThat(loader.selectLayout(show, "section").getType()).isEqualTo(SlideLayout.SECTION_HEADER);
            assertThat(loader.selectLayout(show, "closing").getType()).isEqualTo(SlideLayout.BLANK);
            assertThat(loader.selectLayout(show, "").getType()).isEqualTo(SlideLayout.TITLE);
        }
        ConverterProperties invalid = properties(template);
        invalid.getPptx().getLayouts().setAliases(java.util.Map.of("content", "operator-secret-layout"));
        assertThatThrownBy(() -> new PptxTemplateLoader(invalid).preflight())
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_LAYOUT_UNAVAILABLE");
        assertThatThrownBy(() -> new PptxTemplateLoader(invalid).preflight())
            .hasMessageNotContaining("operator-secret-layout");

        assertThat(PptxTemplateLoader.uniqueLayoutIndex(
            List.of("Title", "Ambiguous", "AMBIGUOUS", "Blank"), "ambiguous")).isEqualTo(-2);
        assertThat(PptxTemplateLoader.uniqueLayoutIndex(List.of("Title", "Blank"), "missing")).isEqualTo(-1);
    }

    @Test void editableRenderRetainsTemplateMasterThemeAndPassesStructuralReopen() throws Exception {
        Path template = writeTemplate(temporary.resolve("render.pptx"), false);
        ConverterProperties properties = properties(template);
        PptxTemplateLoader loader = new PptxTemplateLoader(properties);
        Rectangle2D box = loader.contentBox();
        PageModel model = model(box.getWidth(), box.getHeight(), URI.create("https://example.test/report?token=secret#private"));
        PaginationPlan plan = plan(box.getWidth(), box.getHeight());
        BufferedImage screenshot = new BufferedImage(Math.max(1, (int) box.getWidth()), Math.max(1, (int) box.getHeight()), BufferedImage.TYPE_INT_RGB);
        byte[] bytes = new HybridPptxRenderer(properties, loader).render(screenshot, model, plan);
        PptxOutputValidator.validate(bytes, 1, true);
        try (var show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(show.getSlides()).hasSize(1);
            assertThat(show.getSlides().getFirst().getSlideMaster()).isNotNull();
            assertThat(show.getSlides().getFirst().getTheme()).isNotNull();
            assertThat(show.getProperties().getCustomProperties().getProperty("ocapp.template.contract").getLpwstr()).isEqualTo("1");
        }
    }

    @Test void brandingSanitizesSourceAndAddsFooterNumbersAndBoundedLogo() throws Exception {
        Path logo = temporary.resolve("logo.png");
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB), "png", logo.toFile());
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().setSlideWidthInches(10); properties.getPptx().setSlideHeightInches(5);
        var brand = properties.getPptx().getBrand();
        brand.setLogoPath(logo.toAbsolutePath().toString()); brand.setFooter("  Acme\u0000 Report  ");
        brand.setSourceUrl(true); brand.setSlideNumbers(true); brand.setFooterFontFamily("Arial");
        PptxTemplateLoader loader = new PptxTemplateLoader(properties);
        Rectangle2D box = loader.contentBox();
        URI source = URI.create("https://user:password@Example.Test/report?q=secret#fragment");
        byte[] bytes = new HybridPptxRenderer(properties, loader).render(
            new BufferedImage((int) box.getWidth(), (int) box.getHeight(), BufferedImage.TYPE_INT_RGB),
            model(box.getWidth(), box.getHeight(), source), plan(box.getWidth(), box.getHeight()));
        try (var show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            String text = show.getSlides().getFirst().getShapes().stream()
                .filter(org.apache.poi.xslf.usermodel.XSLFTextShape.class::isInstance)
                .map(org.apache.poi.xslf.usermodel.XSLFTextShape.class::cast).map(t -> t.getText()).reduce("", (a,b) -> a + " " + b);
            assertThat(text).contains("Acme Report", "https://example.test/report", "1")
                .doesNotContain("secret", "password", "fragment", "\u0000");
            assertThat(show.getSlides().getFirst().getShapes()).anyMatch(org.apache.poi.xslf.usermodel.XSLFPictureShape.class::isInstance);
        }
        assertThat(PptxBranding.sanitizeSource(URI.create("file:///private/secret"))).isEmpty();
        assertThat(PptxBranding.sanitizeSource(URI.create("https://Example.Test/private?q=secret"), false))
            .isEqualTo("https://example.test");
        brand.setSourceUrl(false);
        brand.setSourceMode(ConverterProperties.Pptx.Brand.SourceMode.ORIGIN);
        assertThat(brand.getSourceMode()).isEqualTo(ConverterProperties.Pptx.Brand.SourceMode.ORIGIN);
    }

    @Test void brandingAcceptsJpegAndRejectsOversizedOrMalformedLogoPaths() throws Exception {
        Path jpeg = temporary.resolve("logo.jpg");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpeg", jpeg.toFile());
        ConverterProperties valid = new ConverterProperties();
        valid.getPptx().getBrand().setLogoPath(jpeg.toAbsolutePath().toString());
        new PptxBranding(valid).preflight();

        Path large = temporary.resolve("large.png");
        Files.write(large, new byte[2048]);
        ConverterProperties bounded = new ConverterProperties();
        bounded.getPptx().getBrand().setLogoPath(large.toAbsolutePath().toString());
        bounded.getPptx().getBrand().setMaxLogoBytes(1024);
        assertThatThrownBy(() -> new PptxBranding(bounded).preflight())
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_TOO_LARGE");

        ConverterProperties malformed = new ConverterProperties();
        malformed.getPptx().getBrand().setLogoPath("bad" + (char) 0 + "path");
        assertThatThrownBy(() -> new PptxBranding(malformed).preflight())
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_CONTENT_NOT_ALLOWED");
    }

    @Test void logoMetadataRejectsCompressedExtremeDimensionsAndMalformedDataBeforeRendering() throws Exception {
        Path extreme = temporary.resolve("extreme-logo.png");
        Files.write(extreme, pngWithDimensions(20_000, 2));
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().getBrand().setLogoPath(extreme.toString());
        assertThatThrownBy(() -> new PptxBranding(properties).preflight())
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_CONTENT_NOT_ALLOWED");

        Path malformed = temporary.resolve("malformed.png");
        Files.write(malformed, new byte[]{(byte)137,80,78,71,13,10,26,10,0,0,0});
        properties.getPptx().getBrand().setLogoPath(malformed.toString());
        assertThatThrownBy(() -> new PptxBranding(properties).preflight())
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_CONTENT_NOT_ALLOWED");
    }

    @Test void cachedBrandingBytesSurviveSourceDeletionWithoutLateReread() throws Exception {
        Path logo = temporary.resolve("cached-logo.png");
        ImageIO.write(new BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB), "png", logo.toFile());
        ConverterProperties properties = new ConverterProperties();
        properties.getPptx().getBrand().setLogoPath(logo.toString());
        PptxBranding branding = new PptxBranding(properties);
        branding.preflight();
        Files.delete(logo);
        PptxTemplateLoader loader = new PptxTemplateLoader(properties);
        Rectangle2D box = loader.contentBox();
        byte[] bytes = new HybridPptxRenderer(properties, loader, branding).render(
            new BufferedImage((int) box.getWidth(), (int) box.getHeight(), BufferedImage.TYPE_INT_RGB),
            model(box.getWidth(), box.getHeight(), URI.create("https://example.test")), plan(box.getWidth(), box.getHeight()));
        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertThat(show.getSlides().getFirst().getShapes())
                .anyMatch(org.apache.poi.xslf.usermodel.XSLFPictureShape.class::isInstance);
        }
    }

    @Test void trustedFilesRejectNestedAncestorLinksContainmentEscapeAndReplacementRace() throws Exception {
        Path actual = Files.createDirectories(temporary.resolve("actual/deep"));
        Path template = writeTemplate(actual.resolve("safe.pptx"), false);
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, temporary.resolve("actual"));
        assertFailure(properties(alias.resolve("deep/safe.pptx")), "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);
        Path realLogo = temporary.resolve("actual/deep/logo.png");
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", realLogo.toFile());
        ConverterProperties linkedLogo = new ConverterProperties();
        linkedLogo.getPptx().getBrand().setLogoPath(alias.resolve("deep/logo.png").toString());
        assertThatThrownBy(() -> new PptxBranding(linkedLogo).preflight())
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_CONTENT_NOT_ALLOWED");
        ConverterProperties escaped = properties(template);
        escaped.getPptx().getTemplate().setTrustedRoot(temporary.resolve("elsewhere").toString());
        Files.createDirectories(temporary.resolve("elsewhere"));
        assertFailure(escaped, "PPTX_TEMPLATE_UNTRUSTED", HttpStatus.UNPROCESSABLE_ENTITY);

        Path race = temporary.resolve("race.bin"); Files.writeString(race, "before");
        assertThatThrownBy(() -> TrustedLocalFile.read(race.toString(), temporary.toString(), 1024,
            () -> new ConversionException(HttpStatus.UNPROCESSABLE_ENTITY, "RACE", "race"),
            () -> new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "LARGE", "large"),
            opened -> { try {
                Path replacement = temporary.resolve("replacement.bin"); Files.writeString(replacement, "after-value");
                Files.move(replacement, opened, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ex) { throw new RuntimeException(ex); } }))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("RACE");
    }

    @Test void strictOpcRejectsMissingTraversalDisguisedMediaCommentsAndOrphanNotes() throws Exception {
        Path base = writeTemplate(temporary.resolve("opc-base.pptx"), false);
        for (var attack : List.of(
            new String[]{"missing.pptx", "ppt/slides/_rels/orphan.xml.rels", "<Relationships><Relationship Target=\"../media/missing.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/></Relationships>"},
            new String[]{"escape.pptx", "ppt/slides/_rels/orphan.xml.rels", "<Relationships><Relationship Target=\"../../../../escape.xml\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/></Relationships>"},
            new String[]{"comments.pptx", "ppt/comments/comment1.xml", "<x/>"},
            new String[]{"notes.pptx", "ppt/notesSlides/notesSlide99.xml", "<x/>"})) {
            Path attacked = addZipEntry(base, temporary.resolve(attack[0]), attack[1], attack[2]);
            assertFailure(properties(attacked), "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Path audio = addZipEntry(base, temporary.resolve("audio.pptx"), "ppt/media/sound.mp3", "not really audio");
        assertFailure(properties(audio), "PPTX_TEMPLATE_CONTENT_NOT_ALLOWED", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test void authoredPlaceholdersRetainAnchorsAndLosePromptText() throws Exception {
        Path template = writeTemplate(temporary.resolve("placeholders.pptx"), false);
        PptxTemplateLoader loader = new PptxTemplateLoader(properties(template));
        try (XMLSlideShow show = loader.openFresh()) {
            var layout = loader.selectLayout(show, "section");
            var expected = java.util.Arrays.stream(layout.getPlaceholders())
                .filter(p -> p.getTextType() == org.apache.poi.sl.usermodel.Placeholder.TITLE
                    || p.getTextType() == org.apache.poi.sl.usermodel.Placeholder.CENTERED_TITLE)
                .findFirst().orElseThrow();
            var slide = loader.createSlide(show, "section");
            var actual = java.util.Arrays.stream(slide.getPlaceholders())
                .filter(p -> p.getTextType() == expected.getTextType()).findFirst().orElseThrow();
            assertThat(actual.getAnchor()).isEqualTo(expected.getAnchor());
            assertThat(slide.getShapes().stream().filter(org.apache.poi.xslf.usermodel.XSLFTextShape.class::isInstance)
                .map(org.apache.poi.xslf.usermodel.XSLFTextShape.class::cast).map(org.apache.poi.xslf.usermodel.XSLFTextShape::getText))
                .allMatch(text -> text == null || text.isBlank()).noneMatch(text -> text != null && text.contains("Click to edit Master"));
        }
    }

    @Test void noTemplateGeometryUsesConfiguredDoublesAndOutputRejectsHugePages() throws Exception {
        ConverterProperties properties = new ConverterProperties();
        Rectangle2D box = new PptxTemplateLoader(properties).contentBox();
        assertThat(box.getWidth()).isEqualTo(13.333d * 72d);
        assertThat(box.getHeight()).isEqualTo(7.5d * 72d);
        byte[] huge;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            show.setPageSize(new Dimension(14_401, 500)); show.createSlide(); show.write(out); huge = out.toByteArray();
        }
        assertThatThrownBy(() -> PptxOutputValidator.validate(huge, 1, false))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_OUTPUT_INVALID");
    }

    @Test void outputValidatorUsesStableContentFreeError() {
        assertThatThrownBy(() -> PptxOutputValidator.validate(new byte[]{1,2,3}, 1, true))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_OUTPUT_INVALID");
    }

    @Test void rejectsExtremeDimensionsOutOfBoundsShapesAndBrandingBudgets() throws Exception {
        Path extreme = temporary.resolve("extreme.pptx");
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.setPageSize(new Dimension(20_000, 500)); show.createSlide(); show.removeSlide(0);
            try (var output = Files.newOutputStream(extreme)) { show.write(output); }
        }
        assertFailure(properties(extreme.toAbsolutePath()), "PPTX_TEMPLATE_INVALID", HttpStatus.UNPROCESSABLE_ENTITY);

        byte[] invalidOutput;
        try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var shape = show.createSlide().createTextBox();
            shape.setAnchor(new Rectangle2D.Double(0, 0, show.getPageSize().width + 1, 20));
            show.write(output); invalidOutput = output.toByteArray();
        }
        assertThatThrownBy(() -> PptxOutputValidator.validate(invalidOutput, 1, false))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("PPTX_TEMPLATE_OUTPUT_INVALID");

        ConverterProperties shaped = new ConverterProperties();
        shaped.getPptx().setMaxItemsPerSlide(1);
        shaped.getPptx().getBrand().setFooter("footer");
        shaped.getPptx().getBrand().setSlideNumbers(true);
        Rectangle2D box = new PptxTemplateLoader(shaped).contentBox();
        assertThatThrownBy(() -> new HybridPptxRenderer(shaped).render(
            new BufferedImage((int) box.getWidth(), (int) box.getHeight(), BufferedImage.TYPE_INT_RGB),
            model(box.getWidth(), box.getHeight(), URI.create("https://example.test")), plan(box.getWidth(), box.getHeight())))
            .isInstanceOf(ConversionException.class).extracting("code").isEqualTo("OUTPUT_TOO_LARGE");
    }

    private static ConverterProperties properties(Path path) {
        ConverterProperties result = new ConverterProperties();
        result.getPptx().getTemplate().setPath(path.toString());
        return result;
    }

    private static void assertFailure(ConverterProperties properties, String code, HttpStatus status) {
        assertThatThrownBy(() -> new PptxTemplateLoader(properties).preflight())
            .isInstanceOf(ConversionException.class)
            .satisfies(ex -> {
                ConversionException conversion = (ConversionException) ex;
                assertThat(conversion.code()).isEqualTo(code);
                assertThat(conversion.status()).isEqualTo(status);
                assertThat(conversion.getMessage()).doesNotContain("missing.pptx", "target.pptx", "secret.test");
            });
    }

    private static Path writeTemplate(Path path, boolean retainSlide) throws Exception {
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.createSlide();
            if (!retainSlide) show.removeSlide(0);
            try (var output = Files.newOutputStream(path)) { show.write(output); }
        }
        return path.toAbsolutePath();
    }

    private static Path addZipEntry(Path source, Path target, String name, String content) throws Exception {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source));
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry entry; byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                int read; while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry(name)); output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.closeEntry();
        }
        return target.toAbsolutePath();
    }

    private static byte[] pngWithDimensions(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", out);
        byte[] bytes = out.toByteArray();
        for (int i = 0; i < 4; i++) { bytes[16 + i] = (byte) (width >>> (24 - i * 8)); bytes[20 + i] = (byte) (height >>> (24 - i * 8)); }
        CRC32 crc = new CRC32(); crc.update(bytes, 12, 17); long value = crc.getValue();
        for (int i = 0; i < 4; i++) bytes[29 + i] = (byte) (value >>> (24 - i * 8));
        return bytes;
    }

    private static PageModel model(double width, double height, URI source) {
        return new PageModel(PageModel.SCHEMA_VERSION, new DocumentMetadata(source, "", ""),
            new CaptureMetadata("Chrome", "en", "UTC", 1, "complete", List.of()),
            new PageGeometry(width, height, new Bounds(0, 0, width, height), 0, 0),
            List.of(), List.of(), List.of(), List.of());
    }

    private static PaginationPlan plan(double width, double height) {
        return new PaginationPlan(width, height, List.of(new SlideSlice(0,
            new SourceBounds(0, 0, width, height), new SlideViewport(width, height),
            BreakRationale.DOCUMENT_END, List.of())), List.of());
    }
}
