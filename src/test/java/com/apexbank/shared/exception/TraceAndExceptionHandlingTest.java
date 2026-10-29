package com.apexbank.shared.exception;

import java.math.BigDecimal;
import java.util.Map;

import com.apexbank.TestcontainersConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, TraceAndExceptionHandlingTest.TestDiagnosticControllerConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@WithMockUser
@SpringBootTest
class TraceAndExceptionHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void generatesTraceIdHeaderWhenMissing() throws Exception {
        mockMvc.perform(get("/api/v1/test-diagnostics/ok"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void preservesValidIncomingTraceIdAndReplacesUnsafeTraceId() throws Exception {
        mockMvc.perform(get("/api/v1/test-diagnostics/ok")
                        .header("X-Trace-Id", "req-trace-2026-abc_123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "req-trace-2026-abc_123"));

        MvcResult unsafeResult = mockMvc.perform(get("/api/v1/test-diagnostics/ok")
                        .header("X-Trace-Id", "bad\r\ninjected-header!@#"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andReturn();

        String sanitizedTraceId = unsafeResult.getResponse().getHeader("X-Trace-Id");
        assertThat(sanitizedTraceId)
                .isNotBlank()
                .isNotEqualTo("bad\r\ninjected-header!@#");
    }

    @Test
    void mapsApexBankExceptionToUnifiedErrorContract() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/test-diagnostics/business-error"))
                .andExpect(status().isConflict())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENSION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(
                        "The account cannot be suspended while an incompatible scheduled payment exists."))
                .andExpect(jsonPath("$.details.blockingScheduleCount").value(2))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(result.getResponse().getContentAsString()).contains(headerTraceId);
    }

    @Test
    void mapsValidationFailuresToFieldErrorsContract() throws Exception {
        String invalidPayload = """
				{
				  "accountNumber": "",
				  "amount": -50.00
				}
				""";

        mockMvc.perform(post("/api/v1/test-diagnostics/validate")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("One or more fields are invalid."))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void redactsUnexpectedServerExceptionsFromClientResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/test-diagnostics/unexpected-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred. Please try again later."))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody)
                .doesNotContain("select * from secret_table")
                .doesNotContain("postgres_secret_password");
    }

    @TestConfiguration
    static class TestDiagnosticControllerConfig {

        @RestController
        @RequestMapping("/api/v1/test-diagnostics")
        static class TestDiagnosticController {

            @GetMapping("/ok")
            Map<String, String> ok() {
                return Map.of("status", "OK");
            }

            @GetMapping("/business-error")
            void businessError() {
                throw new ApexBankException(
                        "ACCOUNT_SUSPENSION_NOT_ALLOWED",
                        HttpStatus.CONFLICT,
                        "The account cannot be suspended while an incompatible scheduled payment exists.",
                        Map.of("blockingScheduleCount", 2)
                );
            }

            @PostMapping("/validate")
            Map<String, String> validate(@Valid @RequestBody SampleTransferRequest request) {
                return Map.of("status", "VALID");
            }

            @GetMapping("/unexpected-error")
            void unexpectedError() {
                throw new IllegalStateException(
                        "Database query failed: [select * from secret_table] user=postgres_secret_password");
            }

        }

        record SampleTransferRequest(
                @NotBlank(message = "Account number is required.") String accountNumber,
                @Positive(message = "Amount must be greater than zero.") BigDecimal amount
        ) {
        }

    }

}