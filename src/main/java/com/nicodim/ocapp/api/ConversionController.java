package com.nicodim.ocapp.api;

import com.nicodim.ocapp.conversion.ConversionOperations;
import com.nicodim.ocapp.conversion.ConversionResult;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.support.ConversionException;
import jakarta.validation.Valid;
import java.util.regex.Pattern;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversionController {
    private static final Pattern SAFE_FILENAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,159}");
    private final ConversionOperations conversions;

    public ConversionController(ConversionOperations conversions) { this.conversions = conversions; }

    @PostMapping(path = {"/MakeMHTML", "/MakeSnapshot"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> snapshot(@Valid @RequestBody ConversionRequest request) { return convert(request, OutputFormat.MHTML); }

    @PostMapping(path = "/MakePDF", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> pdf(@Valid @RequestBody ConversionRequest request) { return convert(request, OutputFormat.PDF); }

    @PostMapping(path = "/MakePPTX", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> pptx(@Valid @RequestBody ConversionRequest request) { return convert(request, OutputFormat.PPTX); }

    private ResponseEntity<byte[]> convert(ConversionRequest request, OutputFormat format) {
        return response(conversions.convert(request.url(), format));
    }

    static ResponseEntity<byte[]> response(ConversionResult result) {
        String filename = result.filename();
        if (!SAFE_FILENAME.matcher(filename).matches() || !filename.endsWith("." + result.format().extension())) {
            throw new ConversionException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Generated filename is unsafe");
        }
        ContentDisposition disposition = ContentDisposition.attachment().filename(filename).build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(result.format().mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).contentLength(result.bytes().length).body(result.bytes());
    }
}
