package com.nicodim.ocapp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.nicodim.ocapp.support.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "converter.security.egress-mode=UNSAFE",
    "converter.security.unsafe-acknowledge-risk=true"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void servesBundledDefaultExportSource() throws Exception {
        mvc.perform(get("/test_report"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/html"))
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Nordic Vector Holding")));
    }

    @Test void realAdviceOwnsJacksonUnknownFieldErrors() throws Exception {
        mvc.perform(post("/MakePDF").header(CorrelationIdFilter.HEADER, "integration-123")
                .contentType("application/json").content("{\"url\":\"https://example.org\",\"extra\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(header().string(CorrelationIdFilter.HEADER, "integration-123"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.correlationId").value("integration-123"));
    }

    @Test void preservesFramework404MethodAndMediaTypeStatusesAndAllowHeader() throws Exception {
        mvc.perform(get("/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        mvc.perform(get("/MakePDF")).andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
            .andExpect(jsonPath("$.status").value(405));
        mvc.perform(post("/MakePDF").contentType("text/plain").content("x"))
            .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
    }
}
