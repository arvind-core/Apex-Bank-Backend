package com.apexbank.shared.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_ATTRIBUTE = "com.apexbank.traceId";

    private static final Pattern SAFE_TRACE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9-_]{1,64}$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveOrGenerateTraceId(request.getHeader(TRACE_ID_HEADER));

        MDC.put(MDC_TRACE_ID_KEY, traceId);
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_TRACE_ID_KEY);
        }
    }

    private String resolveOrGenerateTraceId(String candidate) {
        if (candidate != null && SAFE_TRACE_ID_PATTERN.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

}