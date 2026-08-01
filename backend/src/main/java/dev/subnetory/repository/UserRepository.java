package dev.subnetory.repository;

import dev.subnetory.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsernameIgnoreCase(String username);

    /**
     * Compte les utilisateurs ayant le rôle donné ET enabled=true.
     * Utilisé par UserAdminService pour les règles anti-lockout sur ROLE_ADMIN.
     */
    @Query("""
        SELECT COUNT(u) FROM User u
        JOIN u.roles r
        WHERE r.name = :roleName AND u.enabled = true
        """)
    long countActiveByRoleName(@Param("roleName") String roleName);

    @Query("""
        SELECT c.id FROM User u
        JOIN u.allowedContexts c
        WHERE u.username = :username AND u.enabled = true
        ORDER BY c.id
        """)
    List<Long> findAllowedContextIds(@Param("username") String username);
}
