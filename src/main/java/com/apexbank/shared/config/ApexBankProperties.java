package com.apexbank.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.ZoneId;

@Validated
@ConfigurationProperties(prefix = "apexbank")
public record ApexBankProperties (
        @NotNull ZoneId businessTimeZone,
        @NotBlank String defaultCurrency
){

}