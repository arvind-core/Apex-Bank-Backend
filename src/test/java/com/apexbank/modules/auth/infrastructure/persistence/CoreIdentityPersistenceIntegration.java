package com.apexbank.modules.auth.infrastructure.persistence;

import com.apexbank.TestcontainersConfiguration;
import com.apexbank.modules.auth.domain.Employee;
import com.apexbank.modules.auth.domain.EmployeeStatus;
import com.apexbank.modules.auth.domain.UserAccount;
import com.apexbank.modules.auth.domain.UserAccountStatus;
import com.apexbank.modules.auth.domain.UserRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CoreIdentityPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void flywayV2MigrationIsAppliedAndSchemaValidates() {
        Boolean v2Applied = jdbcClient
                .sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM flyway_schema_history
                            WHERE version = '2'
                              AND script = 'V2__create_core_identity_tables.sql'
                              AND success = true
                        )
                        """)
                .query(Boolean.class)
                .single();

        assertThat(v2Applied).isTrue();
    }

    @Test
    void persistsAndLoadsUserAccountWithRoleAndStatus() {
        UserAccount account = UserAccount.create(
                "cust.arvind",
                "$argon2id$v=19$m=65536,t=3,p=4$samplehash",
                UserRole.CUSTOMER,
                NOW
        );
        Instant loginAt = NOW.plusSeconds(120);
        account.recordLogin(loginAt);
        account.lock(loginAt.plusSeconds(30));

        UserAccount saved = userAccountRepository.saveAndFlush(account);
        assertThat(saved.getId()).isNotNull();

        UserAccount found = userAccountRepository.findByLoginId("cust.arvind").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getRole()).isEqualTo(UserRole.CUSTOMER);
        assertThat(found.getStatus()).isEqualTo(UserAccountStatus.LOCKED);
        assertThat(found.getLastLoginAt()).isEqualTo(loginAt);
        assertThat(found.getCreatedAt()).isEqualTo(NOW);
        assertThat(found.getUpdatedAt()).isEqualTo(loginAt.plusSeconds(30));
        assertThat(userAccountRepository.existsByLoginId("cust.arvind")).isTrue();
        assertThat(userAccountRepository.existsByRole(UserRole.CUSTOMER)).isTrue();
    }

    @Test
    void enforcesUniqueLoginIdOnUserAccount() {
        userAccountRepository.saveAndFlush(UserAccount.create(
                "duplicate.login",
                "$argon2id$v=19$hash1",
                UserRole.CUSTOMER,
                NOW
        ));

        UserAccount duplicate = UserAccount.create(
                "duplicate.login",
                "$argon2id$v=19$hash2",
                UserRole.MANAGER,
                NOW
        );

        assertThatThrownBy(() -> userAccountRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void persistsAndLoadsEmployeeWithDeactivationSchedule() {
        UserAccount managerAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "mgr.indore.01",
                "$argon2id$v=19$hash",
                UserRole.MANAGER,
                NOW
        ));

        Employee employee = Employee.create(
                managerAccount,
                "EMP-1001",
                "Arvind Dhakar",
                "Arvind.Dhakar@ApexBank.com",
                NOW
        );
        Instant deadline = NOW.plusSeconds(30L * 24 * 3600);
        employee.scheduleDeactivation(deadline, NOW.plusSeconds(60));

        Employee saved = employeeRepository.saveAndFlush(employee);
        assertThat(saved.getId()).isNotNull();

        Employee byEmpNo = employeeRepository.findByEmployeeNumber("EMP-1001").orElseThrow();
        assertThat(byEmpNo.getId()).isEqualTo(saved.getId());
        assertThat(byEmpNo.getUserAccount().getId()).isEqualTo(managerAccount.getId());
        assertThat(byEmpNo.getFullName()).isEqualTo("Arvind Dhakar");
        assertThat(byEmpNo.getEmail()).isEqualTo("arvind.dhakar@apexbank.com");
        assertThat(byEmpNo.getStatus()).isEqualTo(EmployeeStatus.DEACTIVATION_SCHEDULED);
        assertThat(byEmpNo.getDeactivationScheduledAt()).isEqualTo(deadline);

        assertThat(employeeRepository.findByUserAccountId(managerAccount.getId())).isPresent();
        assertThat(employeeRepository.findByEmailIgnoreCase("ARVIND.DHAKAR@APEXBANK.COM")).isPresent();
        assertThat(employeeRepository.existsByEmployeeNumber("EMP-1001")).isTrue();
        assertThat(employeeRepository.existsByEmailIgnoreCase("ARVIND.DHAKAR@APEXBANK.COM")).isTrue();
    }

    @Test
    void enforcesUniqueEmployeeNumber() {
        UserAccount firstAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "mgr.one",
                "$argon2id$v=19$hash1",
                UserRole.MANAGER,
                NOW
        ));
        UserAccount secondAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "mgr.two",
                "$argon2id$v=19$hash2",
                UserRole.MANAGER,
                NOW
        ));

        employeeRepository.saveAndFlush(Employee.create(
                firstAccount,
                "EMP-2001",
                "First Manager",
                "first.mgr@apexbank.com",
                NOW
        ));

        Employee duplicateEmpNumber = Employee.create(
                secondAccount,
                "EMP-2001",
                "Second Manager",
                "second.mgr@apexbank.com",
                NOW
        );

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(duplicateEmpNumber))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesUniqueEmployeeToUserAccountRelationship() {
        UserAccount sharedAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "admin.shared",
                "$argon2id$v=19$hash",
                UserRole.ADMIN,
                NOW
        ));

        employeeRepository.saveAndFlush(Employee.create(
                sharedAccount,
                "EMP-3001",
                "First Admin Record",
                "admin1@apexbank.com",
                NOW
        ));

        Employee secondEmployeeForSameUser = Employee.create(
                sharedAccount,
                "EMP-3002",
                "Second Admin Record",
                "admin2@apexbank.com",
                NOW
        );

        assertThatThrownBy(() -> employeeRepository.saveAndFlush(secondEmployeeForSameUser))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesCaseInsensitiveUniqueEmailOnEmployeeInDatabase() {
        UserAccount firstAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "mgr.email.1",
                "$argon2id$v=19$hash1",
                UserRole.MANAGER,
                NOW
        ));
        UserAccount secondAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "mgr.email.2",
                "$argon2id$v=19$hash2",
                UserRole.ADMIN,
                NOW
        ));

        employeeRepository.saveAndFlush(Employee.create(
                firstAccount,
                "EMP-4001",
                "Manager One",
                "security.officer@apexbank.com",
                NOW
        ));

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO employee (
                            id, user_account_id, employee_number, full_name, email, status, created_at, updated_at
                        ) VALUES (
                            :id, :userAccountId, 'EMP-4002', 'Admin Two', 'SECURITY.OFFICER@APEXBANK.COM', 'ACTIVE', :now, :now
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("userAccountId", secondAccount.getId())
                .param("now", java.sql.Timestamp.from(NOW))
                .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesForeignKeyWhenUserAccountDoesNotExist() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO employee (
                            id, user_account_id, employee_number, full_name, email, status, created_at, updated_at
                        ) VALUES (
                            :id, :missingUserAccountId, 'EMP-5001', 'Orphan Employee', 'orphan@apexbank.com', 'ACTIVE', :now, :now
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("missingUserAccountId", UUID.randomUUID())
                .param("now", java.sql.Timestamp.from(NOW))
                .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void preventsDeletingUserAccountReferencedByEmployee() {
        UserAccount adminAccount = userAccountRepository.saveAndFlush(UserAccount.create(
                "admin.protected",
                "$argon2id$v=19$hash",
                UserRole.ADMIN,
                NOW
        ));
        employeeRepository.saveAndFlush(Employee.create(
                adminAccount,
                "EMP-5002",
                "Protected Admin",
                "protected.admin@apexbank.com",
                NOW
        ));

        assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM user_account WHERE id = :id")
                .param("id", adminAccount.getId())
                .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesDatabaseRoleCheckConstraint() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO user_account (
                            id, login_id, password_hash, role, status, created_at, updated_at
                        ) VALUES (
                            :id, 'bad.role.user', 'hash', 'SUPERUSER', 'ACTIVE', :now, :now
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("now", java.sql.Timestamp.from(NOW))
                .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void enforcesDatabaseNotNullConstraintOnLoginId() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO user_account (
                            id, login_id, password_hash, role, status, created_at, updated_at
                        ) VALUES (
                            :id, NULL, 'hash', 'CUSTOMER', 'ACTIVE', :now, :now
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("now", java.sql.Timestamp.from(NOW))
                .update()
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}