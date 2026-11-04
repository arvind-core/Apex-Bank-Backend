package com.apexbank.modules.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "user_account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserAccount {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "login_id", nullable = false, unique = true, length = 64)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private UserAccountStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private UserAccount(
            String loginId,
            String passwordHash,
            UserRole role,
            UserAccountStatus status,
            Instant createdAt
    ) {
        this.loginId = normalizeRequiredText(loginId, "loginId");
        this.passwordHash = requireNonBlank(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        Instant timestamp = Objects.requireNonNull(createdAt, "now must not be null");
        this.createdAt = timestamp;
        this.updatedAt = timestamp;
    }

    public static UserAccount create(
            String loginId,
            String passwordHash,
            UserRole role,
            Instant now
    ) {
        return new UserAccount(
                loginId,
                passwordHash,
                role,
                UserAccountStatus.ACTIVE,
                now
        );
    }

    public void recordLogin(Instant loginAt) {
        Instant timestamp = Objects.requireNonNull(loginAt, "loginAt must not be null");
        this.lastLoginAt = timestamp;
        this.updatedAt = timestamp;
    }

    public void lock(Instant now) {
        this.status = UserAccountStatus.LOCKED;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void activate(Instant now) {
        this.status = UserAccountStatus.ACTIVE;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void deactivate(Instant now) {
        this.status = UserAccountStatus.INACTIVE;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value.trim();
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserAccount other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}