package dev.subnetory.service;

import dev.subnetory.repository.AuthAuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Service de retention du journal d'audit d'authentification.
 *
 * Sprint 2.14 / T2.
 */
@Service
public class AuthAuditRetentionService {

    private final AuthAuditLogRepository repository;

    public AuthAuditRetentionService(AuthAuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int purgeOlderThan(OffsetDateTime cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }

        return repository.deleteOlderThan(cutoff);
    }

    @Transactional
    public int purgeOlderThanDays(int retentionDays) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("retentionDays must be greater than or equal to 1");
        }

        return purgeOlderThan(OffsetDateTime.now().minusDays(retentionDays));
    }
}
