package dev.subnetory.service;

import dev.subnetory.backup.RestoreMaintenanceGate;
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
    private final RestoreMaintenanceGate restoreMaintenanceGate;
    private final int retentionDays;

    public AuthAuditRetentionScheduler(AuthAuditRetentionService retentionService,
                                       RestoreMaintenanceGate restoreMaintenanceGate,
                                       @Value("${subnetory.audit.retention.days:90}") int retentionDays) {
        this.retentionService = retentionService;
        this.restoreMaintenanceGate = restoreMaintenanceGate;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${subnetory.audit.retention.cron:0 30 2 * * *}")
    public void purgeOldAuthAuditLogs() {
        // Correctif securite MOYENNE (04/08/2026, second audit externe) :
        // RestoreMaintenanceFilter ne couvre que les requetes HTTP, jamais
        // les taches planifiees, qui continuaient donc d'ecrire (purger)
        // pendant une restauration en cours. Report simple au prochain
        // declenchement plutot qu'une file d'attente : la purge est une
        // tache d'hygiene non urgente, un decalage de quelques minutes/heures
        // est sans consequence.
        if (restoreMaintenanceGate.isActive()) {
            log.info("Skipping scheduled auth audit log purge: a restore is in progress.");
            return;
        }

        int deleted = retentionService.purgeOlderThanDays(retentionDays);

        if (deleted > 0) {
            log.info("Purged {} authentication audit log entries older than {} days.", deleted, retentionDays);
        }
    }
}