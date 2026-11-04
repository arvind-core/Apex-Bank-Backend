package com.apexbank.modules.auth.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreIdentityDomainTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-11-03T11:00:00Z");

    @Test
    void enumsDefineExactDocumentedConstants() {
        assertThat(UserRole.values()).containsExactly(
                UserRole.CUSTOMER,
                UserRole.MANAGER,
                UserRole.ADMIN
        );

        assertThat(UserAccountStatus.values()).containsExactly(
                UserAccountStatus.ACTIVE,
                UserAccountStatus.LOCKED,
                UserAccountStatus.INACTIVE
        );

        assertThat(EmployeeStatus.values()).containsExactly(
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DEACTIVATION_SCHEDULED,
                EmployeeStatus.INACTIVE
        );
    }

    @Test
    void userAccountCreationNormalizesLoginIdAndInitializesActiveState() {
        UserAccount account = UserAccount.create(
                "  admin.master  ",
                "$argon2id$v=19$m=65536,t=3,p=4$hashvalue",
                UserRole.ADMIN,
                CREATED_AT
        );

        assertThat(account.getId()).isNull();
        assertThat(account.getLoginId()).isEqualTo("admin.master");
        assertThat(account.getPasswordHash()).isEqualTo("$argon2id$v=19$m=65536,t=3,p=4$hashvalue");
        assertThat(account.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(account.getStatus()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(account.getLastLoginAt()).isNull();
        assertThat(account.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(account.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void userAccountSupportsLoginRecordingAndStatusTransitions() {
        UserAccount account = UserAccount.create(
                "customer01",
                "$argon2id$v=19$hash",
                UserRole.CUSTOMER,
                CREATED_AT
        );

        account.recordLogin(UPDATED_AT);
        assertThat(account.getLastLoginAt()).isEqualTo(UPDATED_AT);
        assertThat(account.getUpdatedAt()).isEqualTo(UPDATED_AT);

        Instant lockedAt = UPDATED_AT.plusSeconds(60);
        account.lock(lockedAt);
        assertThat(account.getStatus()).isEqualTo(UserAccountStatus.LOCKED);
        assertThat(account.getUpdatedAt()).isEqualTo(lockedAt);

        Instant reactivatedAt = lockedAt.plusSeconds(60);
        account.activate(reactivatedAt);
        assertThat(account.getStatus()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(account.getUpdatedAt()).isEqualTo(reactivatedAt);

        Instant deactivatedAt = reactivatedAt.plusSeconds(60);
        account.deactivate(deactivatedAt);
        assertThat(account.getStatus()).isEqualTo(UserAccountStatus.INACTIVE);
        assertThat(account.getUpdatedAt()).isEqualTo(deactivatedAt);
    }

    @Test
    void userAccountRejectsNullOrBlankRequiredFields() {
        assertThatThrownBy(() -> UserAccount.create("   ", "hash", UserRole.CUSTOMER, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loginId");

        assertThatThrownBy(() -> UserAccount.create("user1", "  ", UserRole.CUSTOMER, CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passwordHash");

        assertThatThrownBy(() -> UserAccount.create("user1", "hash", null, CREATED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("role");

        assertThatThrownBy(() -> UserAccount.create("user1", "hash", UserRole.CUSTOMER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now");
    }

    @Test
    void employeeCreationNormalizesFieldsAndRequiresEmployeeRole() {
        UserAccount managerAccount = UserAccount.create(
                "mgr.indore",
                "$argon2id$v=19$hash",
                UserRole.MANAGER,
                CREATED_AT
        );

        Employee employee = Employee.create(
                managerAccount,
                "  EMP-0001  ",
                "  Arvind Dhakar  ",
                "  Arvind.Dhakar@ApexBank.com  ",
                CREATED_AT
        );

        assertThat(employee.getId()).isNull();
        assertThat(employee.getUserAccount()).isSameAs(managerAccount);
        assertThat(employee.getEmployeeNumber()).isEqualTo("EMP-0001");
        assertThat(employee.getFullName()).isEqualTo("Arvind Dhakar");
        assertThat(employee.getEmail()).isEqualTo("arvind.dhakar@apexbank.com");
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(employee.getDeactivationScheduledAt()).isNull();
        assertThat(employee.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(employee.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void employeeRejectsCustomerUserAccount() {
        UserAccount customerAccount = UserAccount.create(
                "cust.one",
                "$argon2id$v=19$hash",
                UserRole.CUSTOMER,
                CREATED_AT
        );

        assertThatThrownBy(() -> Employee.create(
                customerAccount,
                "EMP-9999",
                "Customer Person",
                "customer@example.com",
                CREATED_AT
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANAGER or ADMIN");
    }

    @Test
    void employeeSupportsDeactivationSchedulingAndReactivation() {
        UserAccount managerAccount = UserAccount.create(
                "mgr.bhopal",
                "$argon2id$v=19$hash",
                UserRole.MANAGER,
                CREATED_AT
        );
        Employee employee = Employee.create(
                managerAccount,
                "EMP-0002",
                "Rajesh Sharma",
                "rajesh.sharma@apexbank.com",
                CREATED_AT
        );

        Instant deadline = CREATED_AT.plusSeconds(30L * 24 * 3600);
        employee.scheduleDeactivation(deadline, UPDATED_AT);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.DEACTIVATION_SCHEDULED);
        assertThat(employee.getDeactivationScheduledAt()).isEqualTo(deadline);
        assertThat(employee.getUpdatedAt()).isEqualTo(UPDATED_AT);

        Instant reassignedAt = UPDATED_AT.plusSeconds(3600);
        employee.activate(reassignedAt);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(employee.getDeactivationScheduledAt()).isNull();
        assertThat(employee.getUpdatedAt()).isEqualTo(reassignedAt);

        Instant deactivatedAt = reassignedAt.plusSeconds(3600);
        employee.deactivate(deactivatedAt);
        assertThat(employee.getStatus()).isEqualTo(EmployeeStatus.INACTIVE);
        assertThat(employee.getDeactivationScheduledAt()).isNull();
        assertThat(employee.getUpdatedAt()).isEqualTo(deactivatedAt);
    }
}