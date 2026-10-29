package com.apexbank.shared.security;

import java.util.Map;

import com.apexbank.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import({TestcontainersConfiguration.class, SecurityFoundationTest.TestSecurityEndpointsConfig.class})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest
class SecurityFoundationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHealthIsPubliclyAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void unauthenticatedRequestToProtectedApiReturnsUnified401JsonAndNoSessionCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/customers/test-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(cookie().doesNotExist("JSESSIONID"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource."))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader("X-Trace-Id");
        assertThat(result.getResponse().getContentAsString()).contains(headerTraceId);
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customerAccessingAdminOrManagerEndpointReturnsUnified403Json() throws Exception {
        MvcResult adminResult = mockMvc.perform(get("/api/v1/admin/test-probe"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this operation."))
                .andExpect(jsonPath("$.fieldErrors").isEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();

        String headerTraceId = adminResult.getResponse().getHeader("X-Trace-Id");
        assertThat(adminResult.getResponse().getContentAsString()).contains(headerTraceId);

        mockMvc.perform(get("/api/v1/manager/test-probe"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void methodLevelPreAuthorizeDeniesUnauthorizedRoleWithUnified403Json() throws Exception {
        mockMvc.perform(post("/api/v1/customers/admin-only-method"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanAccessAdminEndpointAndActuatorMetricsWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/admin/test-mutation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ADMIN"));

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void actuatorMetricsIsForbiddenForCustomerRole() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @TestConfiguration
    static class TestSecurityEndpointsConfig {

        @RestController
        static class TestSecurityProbeController {

            @GetMapping("/api/v1/customers/test-probe")
            Map<String, String> customerProbe() {
                return Map.of("scope", "CUSTOMER");
            }

            @PostMapping("/api/v1/customers/admin-only-method")
            @PreAuthorize("hasRole('ADMIN')")
            Map<String, String> adminOnlyMethod() {
                return Map.of("scope", "METHOD_ADMIN");
            }

            @GetMapping("/api/v1/manager/test-probe")
            Map<String, String> managerProbe() {
                return Map.of("scope", "MANAGER");
            }

            @GetMapping("/api/v1/admin/test-probe")
            Map<String, String> adminProbe() {
                return Map.of("scope", "ADMIN");
            }

            @PostMapping("/api/v1/admin/test-mutation")
            Map<String, String> adminMutation() {
                return Map.of("scope", "ADMIN");
            }
        }
    }
}