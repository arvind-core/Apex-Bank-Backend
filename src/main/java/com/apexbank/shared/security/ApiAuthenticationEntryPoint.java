package com.apexbank.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.apexbank.shared.exception.ApiErrorResponse;
import com.apexbank.shared.logging.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        String traceId = resolveTraceId(request);
        log.warn("Unauthenticated request rejected [path={}, traceId={}]", request.getRequestURI(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "AUTHENTICATION_REQUIRED",
                "Authentication is required to access this resource.",
                null,
                List.of(),
                traceId
        );

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), body);
    }

    private String resolveTraceId(HttpServletRequest request) {
        String mdcTraceId = MDC.get(TraceIdFilter.MDC_TRACE_ID_KEY);
        if (mdcTraceId != null && !mdcTraceId.isBlank()) {
            return mdcTraceId;
        }
        Object attrTraceId = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        if (attrTraceId instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }
        return UUID.randomUUID().toString();
    }

}