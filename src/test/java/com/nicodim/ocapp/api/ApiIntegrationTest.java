package com.nicodim.ocapp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.nicodim.ocapp.support.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;

    @Test void realAdviceOwnsJacksonUnknownFieldErrors() throws Exception {
        mvc.perform(post("/MakePDF").header(CorrelationIdFilter.HEADER, "integration-123")
                .contentType("application/json").content("{\"url\":\"https://example.org\",\"extra\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(header().string(CorrelationIdFilter.HEADER, "integration-123"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.correlationId").value("integration-123"));
    }
}
