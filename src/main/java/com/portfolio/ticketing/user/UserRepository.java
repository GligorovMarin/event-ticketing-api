package com.portfolio.ticketing.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Database access for users. This is only an INTERFACE: Spring Data generates the implementation at startup.
 *
 * <p>{@link JpaRepository} already provides save, findById, findAll, delete, count ... The two extra
 * methods below are "derived queries": Spring reads the method NAME and builds the SQL
 * ({@code findByEmail} becomes {@code SELECT ... FROM users WHERE email = ?}).
 *
 * <p>Used by: auth/AuthService.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
