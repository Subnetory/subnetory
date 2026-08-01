package dev.subnetory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Declencheur planifie de purge du journal d'audit d'authentification.
 *
 * Sprint 2.14 / T2.
 */
@Component
@ConditionalOnProperty(
        prefix = "subnetory.audit.retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class AuthAuditRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditRetentionScheduler.class);

    private final AuthAuditRetentionService retentionService;
    private final int retentionDays;

    public AuthAuditRetentionScheduler(AuthAuditRetentionService retentionService,
                                       @Value("${subnetory.audit.retention.days:90}") int retentionDays) {
        this.retentionService = retentionService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${subnetory.audit.retention.cron:0 30 2 * * *}")
    public void purgeOldAuthAuditLogs() {
        int deleted = retentionService.purgeOlderThanDays(retentionDays);

        if (deleted > 0) {
            log.info("Purged {} authentication audit log entries older than {} days.", deleted, retentionDays);
        }
    }
}