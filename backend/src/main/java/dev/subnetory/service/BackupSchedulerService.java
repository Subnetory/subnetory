package dev.subnetory.service;

import dev.subnetory.backup.BackupExecutionService;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.repository.BackupRunRepository;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Déclencheur planifié des sauvegardes (Phase 7 audit, 31/07/2026).
 *
 * <p>L'expression cron est modifiable à chaud par un administrateur (stockée
 * en base via {@link BackupConfigurationService}), contrairement aux autres
 * tâches planifiées de l'application ({@code @Scheduled(cron = "...")} figé
 * au démarrage). Un simple sondage à fréquence fixe est donc utilisé plutôt
 * qu'un {@code SchedulingConfigurer} avec ré-enregistrement dynamique du
 * trigger — plus simple à maintenir, largement suffisant pour une tâche
 * qui se déclenche au plus quelques fois par jour (précision à la minute).</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "subnetory.backup.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class BackupSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(BackupSchedulerService.class);

    private final BackupConfigurationService configurationService;
    private final BackupExecutionService executionService;
    private final BackupRunRepository backupRunRepository;

    public BackupSchedulerService(BackupConfigurationService configurationService,
                                  BackupExecutionService executionService,
                                  BackupRunRepository backupRunRepository) {
        this.configurationService = configurationService;
        this.executionService = executionService;
        this.backupRunRepository = backupRunRepository;
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void checkAndRunIfDue() {
        var settings = configurationService.effectiveSettings();
        if (!settings.enabled()) return;
        if (executionService.isOperationInProgress()) return;

        CronExpression cron;
        try {
            cron = CronExpression.parse(settings.cronExpression());
        } catch (IllegalArgumentException e) {
            log.warn("Expression cron de sauvegarde invalide, planification ignorée : {}", e.getMessage());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime reference = backupRunRepository.findFirstByOrderByStartedAtDesc()
                .map(BackupRun::getStartedAt)
                .orElse(now.minusYears(1));

        OffsetDateTime nextDue = cron.next(reference);
        if (nextDue != null && !nextDue.isAfter(now)) {
            log.info("Déclenchement de la sauvegarde planifiée (échéance : {}).", nextDue);
            executionService.triggerScheduledBackup();
        }
    }
}
