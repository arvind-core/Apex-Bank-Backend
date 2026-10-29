package com.apexbank.shared.exception;

public record FieldErrorDetail(
        String field,
        String code,
        String message
) {
}