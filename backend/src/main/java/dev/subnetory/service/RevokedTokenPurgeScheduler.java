package dev.subnetory.service;

import dev.subnetory.backup.RestoreMaintenanceGate;
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
    private final RestoreMaintenanceGate restoreMaintenanceGate;

    public RevokedTokenPurgeScheduler(RevokedTokenPurgeService purgeService,
                                      RestoreMaintenanceGate restoreMaintenanceGate) {
        this.purgeService = purgeService;
        this.restoreMaintenanceGate = restoreMaintenanceGate;
    }

    @Scheduled(cron = "${subnetory.jwt.revocation.purge.cron:0 30 3 * * *}")
    public void purgeExpiredRevokedTokens() {
        // Correctif securite MOYENNE (04/08/2026, second audit externe) :
        // meme motif que AuthAuditRetentionScheduler — RestoreMaintenanceFilter
        // ne couvre que les requetes HTTP, jamais les taches planifiees.
        if (restoreMaintenanceGate.isActive()) {
            log.info("Skipping scheduled revoked-token purge: a restore is in progress.");
            return;
        }

        int deleted = purgeService.purgeExpiredTokens();

        if (deleted > 0) {
            log.info("Purged {} expired revoked JWT tokens.", deleted);
        }
    }
}
