package com.apexbank.shared.exception;

import java.util.List;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        Map<String, Object> details,
        List<FieldErrorDetail> fieldErrors,
        String traceId
) {

    public ApiErrorResponse {
        fieldErrors = fieldErrors != null ? List.copyOf(fieldErrors) : List.of();
    }

}