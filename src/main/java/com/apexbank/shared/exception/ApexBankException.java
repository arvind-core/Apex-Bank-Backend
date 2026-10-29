package com.apexbank.shared.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

@Getter
public class ApexBankException extends RuntimeException{
    private final String errorCode;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public ApexBankException(String errorCode, HttpStatus status, String message){
        this(errorCode, status, message, null);
    }

    public ApexBankException(String errorCode, HttpStatus status, String message, Map<String, Object> details
    ){
        super(message);
        this.errorCode = errorCode;
        this.status = status;
        this.details = details;
    }
}
