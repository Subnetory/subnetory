package dev.subnetory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declencheur planifie de purge des tokens JWT revoques expires.
 */
@Component
@ConditionalOnProperty(
        prefix = "subnetory.jwt.revocation.purge",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class RevokedTokenPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevokedTokenPurgeScheduler.class);

    private final RevokedTokenPurgeService purgeService;

    public RevokedTokenPurgeScheduler(RevokedTokenPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @Scheduled(cron = "${subnetory.jwt.revocation.purge.cron:0 30 3 * * *}")
    public void purgeExpiredRevokedTokens() {
        int deleted = purgeService.purgeExpiredTokens();

        if (deleted > 0) {
            log.info("Purged {} expired revoked JWT tokens.", deleted);
        }
    }
}
