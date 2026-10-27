package com.apexbank.shared.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(ApexBankProperties.class)
public class TimeConfig {

    @Bean
    public Clock clock(){
        return Clock.systemUTC();
    }
}
