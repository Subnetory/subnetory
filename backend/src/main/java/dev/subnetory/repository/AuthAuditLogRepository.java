package dev.subnetory.repository;

import java.time.OffsetDateTime;
import java.util.List;

import dev.subnetory.domain.AuthAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository du journal d audit d authentification.
 */
public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLog, Long> {

    Page<AuthAuditLog> findByUsernameContainingIgnoreCaseOrTargetUsernameContainingIgnoreCase(
            String username,
            String targetUsername,
            Pageable pageable);

    Page<AuthAuditLog> findByEventType(String eventType, Pageable pageable);

    List<AuthAuditLog> findAllByOrderByCreatedAtDesc();

    List<AuthAuditLog> findByEventTypeOrderByCreatedAtDesc(String eventType);

    List<AuthAuditLog> findByUsernameContainingIgnoreCaseOrTargetUsernameContainingIgnoreCaseOrderByCreatedAtDesc(
            String username,
            String targetUsername);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AuthAuditLog log where log.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") OffsetDateTime cutoff);
}