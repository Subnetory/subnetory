package dev.subnetory.repository;

import dev.subnetory.domain.RevokedToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

/**
 * Repository de la denylist JWT.
 */
public interface RevokedTokenRepository extends JpaRepository<RevokedToken, String> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO revoked_tokens (jti, username, expires_at, reason)
            VALUES (:jti, :username, :expiresAt, :reason)
            ON CONFLICT (jti) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("jti") String jti,
                       @Param("username") String username,
                       @Param("expiresAt") OffsetDateTime expiresAt,
                       @Param("reason") String reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RevokedToken token where token.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
