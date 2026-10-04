package com.nicodim.ocapp.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a static, self-contained HTML fixture designed as a printable financial report:
 * title page, table of contents, a line chart + sunburst chart with a company summary,
 * key metrics/risks, and a 7-row asset table with green/red percentage deltas. Intended as a
 * stable manual/E2E target for the {@code /MakePDF} and {@code /MakePPTX} conversion endpoints.
 */
@RestController
public class TestReportController {
    private static final String RESOURCE = "fixtures/test_report.html";
    private final String html = load();

    @GetMapping("/test_report")
    public ResponseEntity<String> testReport() {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
            .cacheControl(CacheControl.noStore())
            .body(html);
    }

    private static String load() {
        try {
            return new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Missing bundled resource: " + RESOURCE, ex);
        }
    }
}
