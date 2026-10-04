package com.nicodim.ocapp.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nicodim.ocapp.conversion.ConversionOperations;
import com.nicodim.ocapp.conversion.ConversionResult;
import com.nicodim.ocapp.conversion.OutputFormat;
import com.nicodim.ocapp.support.CorrelationIdFilter;
import com.nicodim.ocapp.support.ConversionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class ConversionControllerTest {
    private static final String DEFAULT_EXPORT_SOURCE = "http://localhost:8088/test_report";
    private ConversionOperations operations;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        operations = org.mockito.Mockito.mock(ConversionOperations.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new ConversionController(operations))
            .setControllerAdvice(new ApiExceptionHandler()).setValidator(validator)
            .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
            .addFilters(new CorrelationIdFilter()).build();
    }

    @Test void returnsEachFormatWithSafeDownloadHeadersForDefaultExportSource() throws Exception {
        expectSuccess("/MakeMHTML", OutputFormat.MHTML, "example.mhtml");
        expectSuccess("/MakeSnapshot", OutputFormat.MHTML, "example.mhtml");
        expectSuccess("/MakePDF", OutputFormat.PDF, "example.pdf");
        expectSuccess("/MakePPTX", OutputFormat.PPTX, "example.pptx");
    }

    @Test void rejectsMissingBlankOversizedAndMalformedRequestAsProblemJson() throws Exception {
        for (String body : new String[]{"{}", "{\"url\":\" \"}", "{", "{\"url\":\"https://example.org\",\"extra\":true}", "{\"url\":\"" + "x".repeat(4097) + "\"}"}) {
            mvc.perform(post("/MakeSnapshot").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(jsonPath("$.correlationId").isNotEmpty());
        }
    }

    @Test void mapsCapacityErrorAndRetryAfter() throws Exception {
        when(operations.convert(any(), any())).thenThrow(new ConversionException(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED", "busy"));
        mvc.perform(post("/MakePPTX").contentType("application/json").content("{\"url\":\"https://example.org\"}"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "1")).andExpect(jsonPath("$.code").value("CAPACITY_EXCEEDED"));
    }

    @Test void replacesUnsafeCorrelationIdAndDoesNotReflectItInProblem() throws Exception {
        mvc.perform(post("/MakeSnapshot").header(CorrelationIdFilter.HEADER, "bad\tvalue").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(header().string(CorrelationIdFilter.HEADER, org.hamcrest.Matchers.not("bad\tvalue")))
            .andExpect(jsonPath("$.correlationId", org.hamcrest.Matchers.not("bad\tvalue")));
    }

    @Test void unsafeFilenameBecomesSafeInternalProblem() throws Exception {
        when(operations.convert(any(), any())).thenReturn(new ConversionResult(new byte[]{1}, OutputFormat.PDF, "bad\r\nX-Evil: yes.pdf"));
        mvc.perform(post("/MakePDF").contentType("application/json").content("{\"url\":\"https://example.org\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(header().doesNotExist("X-Evil"));
    }

    @Test void unexpectedExceptionsAreSanitized() throws Exception {
        when(operations.convert(any(), any())).thenThrow(new IllegalStateException("secret /tmp/path"));
        mvc.perform(post("/MakePDF").contentType("application/json").content("{\"url\":\"https://example.org\"}"))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.detail").value("An internal error occurred"));
    }

    private void expectSuccess(String path, OutputFormat format, String filename) throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        when(operations.convert(DEFAULT_EXPORT_SOURCE, format)).thenReturn(new ConversionResult(bytes, format, filename));
        mvc.perform(post(path).contentType("application/json").header(CorrelationIdFilter.HEADER, "test-123")
                .content("{\"url\":\"" + DEFAULT_EXPORT_SOURCE + "\"}"))
            .andExpect(status().isOk()).andExpect(content().contentType(format.mediaType()))
            .andExpect(header().string(CorrelationIdFilter.HEADER, "test-123"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + filename + "\""))
            .andExpect(header().longValue("Content-Length", bytes.length)).andExpect(content().bytes(bytes));
    }
}
