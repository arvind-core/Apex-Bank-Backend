package com.apexbank.modules.auth.infrastructure.persistence;

import com.apexbank.modules.auth.domain.UserAccount;
import com.apexbank.modules.auth.domain.UserRole;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByLoginId(String loginId);

    boolean existsByLoginId(String loginId);

    boolean existsByRole(UserRole role);
}