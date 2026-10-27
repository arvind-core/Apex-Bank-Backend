package com.apexbank;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
public class ApexBankBackendApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void contextProvidesUtcClockBean(){
        Clock clock = applicationContext.getBean(Clock.class);
        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void coreFoundationPropertiesAreConfigured(){
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,info,metrics");
        assertThat(environment.getProperty("apexbank.business-time-zone")).isEqualTo("Asia/Kolkata");
        assertThat(environment.getProperty("apexbank.default-currency")).isEqualTo("INR");
    }

    @Test
    void databaseIsPostgreSqlAndFlywayBaselineIsApplied(){
        String version = jdbcClient.sql("SELECT version()").query(String.class).single();
        assertThat(version).containsIgnoringCase("PostgreSQL");

        Integer appliedMigrations = jdbcClient
                .sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success = true")
                .query(Integer.class)
                .single();
        assertThat(appliedMigrations).isGreaterThanOrEqualTo(1);
    }
}
