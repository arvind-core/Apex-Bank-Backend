package com.apexbank.shared.exception;

import java.util.List;
import java.util.UUID;

import com.apexbank.shared.logging.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApexBankException.class)
    public ResponseEntity<ApiErrorResponse> handleApexBankException(
            ApexBankException ex,
            HttpServletRequest request
    ) {
        String traceId = resolveTraceId(request);
        log.warn("Business exception [code={}, status={}, path={}, traceId={}]: {}",
                ex.getErrorCode(), ex.getStatus().value(), request.getRequestURI(), traceId, ex.getMessage());

        ApiErrorResponse body = new ApiErrorResponse(
                ex.getErrorCode(),
                ex.getMessage(),
                ex.getDetails(),
                List.of(),
                traceId
        );
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String traceId = resolveTraceId(request);
        List<FieldErrorDetail> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldErrorDetail)
                .toList();

        log.warn("Request validation failed [path={}, fieldErrorCount={}, traceId={}]",
                request.getRequestURI(), fieldErrors.size(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "VALIDATION_FAILED",
                "One or more fields are invalid.",
                null,
                fieldErrors,
                traceId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleMalformedJson(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        String traceId = resolveTraceId(request);
        log.warn("Malformed request body [path={}, traceId={}]", request.getRequestURI(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "MALFORMED_REQUEST_BODY",
                "Request body is missing or malformed.",
                null,
                List.of(),
                traceId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(
            AuthenticationException ex,
            HttpServletRequest request
    ) {
        String traceId = resolveTraceId(request);
        log.warn("Authentication failed [path={}, traceId={}]", request.getRequestURI(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "AUTHENTICATION_REQUIRED",
                "Authentication is required to access this resource.",
                null,
                List.of(),
                traceId
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        String traceId = resolveTraceId(request);
        log.warn("Access denied [path={}, traceId={}]", request.getRequestURI(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "ACCESS_DENIED",
                "You do not have permission to perform this operation.",
                null,
                List.of(),
                traceId
        );
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(
            Exception ex,
            HttpServletRequest request
    ) {
        String traceId = resolveTraceId(request);
        log.error("Unhandled server exception [path={}, exceptionType={}, traceId={}]",
                request.getRequestURI(), ex.getClass().getName(), traceId);

        ApiErrorResponse body = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "An unexpected error occurred. Please try again later.",
                null,
                List.of(),
                traceId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private FieldErrorDetail toFieldErrorDetail(FieldError fieldError) {
        String rawCode = fieldError.getCode();
        String normalizedCode = rawCode != null
                ? rawCode.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase()
                : "INVALID_VALUE";
        String message = fieldError.getDefaultMessage() != null
                ? fieldError.getDefaultMessage()
                : "Invalid value.";
        return new FieldErrorDetail(fieldError.getField(), normalizedCode, message);
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