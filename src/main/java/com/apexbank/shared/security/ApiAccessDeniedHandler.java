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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        String traceId = resolveTraceId(request);
        log.warn("Access denied [path={}, traceId={}]", request.getRequestURI(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "ACCESS_DENIED",
                "You do not have permission to perform this operation.",
                null,
                List.of(),
                traceId
        );

        response.setStatus(HttpStatus.FORBIDDEN.value());
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