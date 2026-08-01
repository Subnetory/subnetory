package dev.subnetory.repository;

import dev.subnetory.domain.UserTokenInvalidation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface UserTokenInvalidationRepository extends JpaRepository<UserTokenInvalidation, String> {

    @Query(value = "SELECT not_before FROM user_token_invalidations WHERE username = :username", nativeQuery = true)
    Optional<Instant> findNotBeforeByUsername(@Param("username") String username);

    @Modifying
    @Transactional
    @Query(value = ""
        + "INSERT INTO user_token_invalidations (username, not_before, invalidated_at, invalidated_by, reason) "
        + "VALUES (:username, :notBefore, now(), :invalidatedBy, :reason) "
        + "ON CONFLICT (username) DO UPDATE SET "
        + "not_before = EXCLUDED.not_before, "
        + "invalidated_at = now(), "
        + "invalidated_by = EXCLUDED.invalidated_by, "
        + "reason = EXCLUDED.reason"
        + "", nativeQuery = true)
    int upsertNotBefore(
        @Param("username") String username,
        @Param("notBefore") OffsetDateTime notBefore,
        @Param("invalidatedBy") String invalidatedBy,
        @Param("reason") String reason);
}
