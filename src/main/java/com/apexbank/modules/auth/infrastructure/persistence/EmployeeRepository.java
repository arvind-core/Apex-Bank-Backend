package com.apexbank.modules.auth.infrastructure.persistence;

import com.apexbank.modules.auth.domain.Employee;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByUserAccountId(UUID userAccountId);

    Optional<Employee> findByEmployeeNumber(String employeeNumber);

    Optional<Employee> findByEmailIgnoreCase(String email);

    boolean existsByEmployeeNumber(String employeeNumber);

    boolean existsByEmailIgnoreCase(String email);
}