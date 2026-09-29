package com.nicodim.ocapp.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import com.nicodim.ocapp.support.ConversionException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ValueTypesTest {
    @Test void outputFormatsAndRecordsExposeContractValues() {
        assertThat(OutputFormat.MHTML.mediaType()).isEqualTo("application/x-mimearchive");
        assertThat(OutputFormat.MHTML.extension()).isEqualTo("mhtml");
        assertThat(OutputFormat.PDF.mediaType()).isEqualTo("application/pdf");
        assertThat(OutputFormat.PDF.extension()).isEqualTo("pdf");
        assertThat(OutputFormat.PPTX.mediaType()).contains("presentationml");
        assertThat(OutputFormat.PPTX.extension()).isEqualTo("pptx");
        ConversionResult result = new ConversionResult(new byte[]{1}, OutputFormat.PDF, "a.pdf");
        assertThat(result.bytes()).containsExactly(1);
        assertThat(result.format()).isEqualTo(OutputFormat.PDF);
        assertThat(result.filename()).isEqualTo("a.pdf");
        PageRenderer renderer = (uri, format) -> uri.toString().getBytes();
        assertThat(renderer.render(URI.create("https://example.org"), OutputFormat.PDF)).isNotEmpty();
    }

    @Test void conversionExceptionExposesStatusCodeMessageAndCause() {
        ConversionException plain = new ConversionException(HttpStatus.BAD_REQUEST, "BAD", "message");
        assertThat(plain.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(plain.code()).isEqualTo("BAD");
        assertThat(plain.getMessage()).isEqualTo("message");
        RuntimeException cause = new RuntimeException("cause");
        ConversionException wrapped = new ConversionException(HttpStatus.INTERNAL_SERVER_ERROR, "ERR", "wrapped", cause);
        assertThat(wrapped.getCause()).isSameAs(cause);
    }
}
