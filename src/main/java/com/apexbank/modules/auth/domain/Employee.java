package com.apexbank.modules.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "employee")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Employee {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_account_id", nullable = false, unique = true, updatable = false)
    private UserAccount userAccount;

    @Column(name = "employee_number", nullable = false, unique = true, updatable = false, length = 32)
    private String employeeNumber;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EmployeeStatus status;

    @Column(name = "deactivation_scheduled_at")
    private Instant deactivationScheduledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Employee(
            UserAccount userAccount,
            String employeeNumber,
            String fullName,
            String email,
            EmployeeStatus status,
            Instant createdAt
    ) {
        UserAccount account = Objects.requireNonNull(userAccount, "userAccount must not be null");
        if (account.getRole() != UserRole.MANAGER && account.getRole() != UserRole.ADMIN) {
            throw new IllegalArgumentException("Employee userAccount must have role MANAGER or ADMIN");
        }
        this.userAccount = account;
        this.employeeNumber = normalizeRequiredText(employeeNumber, "employeeNumber");
        this.fullName = normalizeRequiredText(fullName, "fullName");
        this.email = normalizeRequiredText(email, "email").toLowerCase(Locale.ROOT);
        this.status = Objects.requireNonNull(status, "status must not be null");
        Instant timestamp = Objects.requireNonNull(createdAt, "now must not be null");
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public static Employee create(
            UserAccount userAccount,
            String employeeNumber,
            String fullName,
            String email,
            Instant now
    ) {
        return new Employee(
                userAccount,
                employeeNumber,
                fullName,
                email,
                EmployeeStatus.ACTIVE,
                now
        );
    }

    public void scheduleDeactivation(Instant deadline, Instant now) {
        this.deactivationScheduledAt = Objects.requireNonNull(deadline, "deadline must not be null");
        this.status = EmployeeStatus.DEACTIVATION_SCHEDULED;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void activate(Instant now) {
        this.status = EmployeeStatus.ACTIVE;
        this.deactivationScheduledAt = null;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void deactivate(Instant now) {
        this.status = EmployeeStatus.INACTIVE;
        this.deactivationScheduledAt = null;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value.trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Employee other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}