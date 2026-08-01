package dev.subnetory.service;

import dev.subnetory.repository.RevokedTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Service testable de purge des tokens JWT revoques expires.
 */
@Service
public class RevokedTokenPurgeService {

    private final RevokedTokenRepository repository;

    public RevokedTokenPurgeService(RevokedTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int purgeExpiredBefore(OffsetDateTime cutoff) {
        if (cutoff == null) {
            throw new IllegalArgumentException("cutoff must not be null");
        }

        return repository.deleteByExpiresAtBefore(cutoff);
    }

    @Transactional
    public int purgeExpiredTokens() {
        return purgeExpiredBefore(OffsetDateTime.now());
    }
}
