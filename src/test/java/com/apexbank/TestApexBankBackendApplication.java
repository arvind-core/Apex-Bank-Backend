package com.apexbank;

import org.springframework.boot.SpringApplication;

public class TestApexBankBackendApplication {
    public static void main(String[] args){
        SpringApplication.from(ApexBankBackendApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
