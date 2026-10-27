package com.apexbank.shared.config;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApexBankPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class
            ))
            .withUserConfiguration(TimeConfig.class);

    @Test
    void bindsValidConfigurationAndRegistersUtcClock() {
        contextRunner
                .withPropertyValues(
                        "apexbank.business-time-zone=Asia/Kolkata",
                        "apexbank.default-currency=INR"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApexBankProperties.class);
                    assertThat(context).hasSingleBean(Clock.class);

                    ApexBankProperties props = context.getBean(ApexBankProperties.class);
                    assertThat(props.businessTimeZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
                    assertThat(props.defaultCurrency()).isEqualTo("INR");

                    Clock clock = context.getBean(Clock.class);
                    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
                });
    }

    @Test
    void failsStartupWhenBusinessTimeZoneIsMissing() {
        contextRunner
                .withPropertyValues("apexbank.default-currency=INR")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsStartupWhenDefaultCurrencyIsBlank() {
        contextRunner
                .withPropertyValues(
                        "apexbank.business-time-zone=Asia/Kolkata",
                        "apexbank.default-currency=   "
                )
                .run(context -> assertThat(context).hasFailed());
    }

}