package dev.subnetory.service;

import dev.subnetory.backup.BackupExecutionService;
import dev.subnetory.domain.BackupRun;
import dev.subnetory.repository.BackupRunRepository;
import dev.subnetory.service.BackupConfigurationService.EffectiveBackupSettings;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 audit, 31/07/2026 — sondage a frequence fixe qui relit l'etat
 * persiste en base a chaque tick (cf. commentaire de classe de
 * {@link BackupSchedulerService}). On verifie ici la logique de decision
 * (declencher ou non), pas l'ordonnancement Spring lui-meme.
 */
class BackupSchedulerServiceTest {

    private final BackupConfigurationService configurationService = mock(BackupConfigurationService.class);
    private final BackupExecutionService executionService = mock(BackupExecutionService.class);
    private final BackupRunRepository backupRunRepository = mock(BackupRunRepository.class);
    private final BackupSchedulerService scheduler =
            new BackupSchedulerService(configurationService, executionService, backupRunRepository);

    @Test
    void checkAndRunIfDue_doesNothingWhenDisabled() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(false, "0 0 2 * * *", 14));

        scheduler.checkAndRunIfDue();

        verify(executionService, never()).triggerScheduledBackup();
        verify(backupRunRepository, never()).findFirstByOrderByStartedAtDesc();
    }

    @Test
    void checkAndRunIfDue_doesNothingWhenOperationAlreadyInProgress() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 2 * * *", 14));
        when(executionService.isOperationInProgress()).thenReturn(true);

        scheduler.checkAndRunIfDue();

        verify(executionService, never()).triggerScheduledBackup();
    }

    @Test
    void checkAndRunIfDue_doesNothingWhenCronExpressionIsInvalid() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "not a cron", 14));
        when(executionService.isOperationInProgress()).thenReturn(false);

        scheduler.checkAndRunIfDue();

        verify(executionService, never()).triggerScheduledBackup();
    }

    @Test
    void checkAndRunIfDue_triggersWhenNextFireTimeHasPassedSinceLastRun() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 2 * * *", 14));
        when(executionService.isOperationInProgress()).thenReturn(false);

        BackupRun lastRun = new BackupRun();
        lastRun.setStartedAt(OffsetDateTime.now().minusDays(3));
        when(backupRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(lastRun));

        scheduler.checkAndRunIfDue();

        verify(executionService).triggerScheduledBackup();
    }

    @Test
    void checkAndRunIfDue_doesNotTriggerWhenLastRunIsMoreRecentThanNextFireTime() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 2 * * *", 14));
        when(executionService.isOperationInProgress()).thenReturn(false);

        // Une sauvegarde vient d'avoir lieu a l'instant : la prochaine echeance
        // cron (le prochain 02:00:00) est necessairement dans le futur par
        // rapport a "maintenant", quel que soit le moment ou ce test s'execute
        // (CronExpression#next renvoie toujours une date strictement posterieure
        // a la reference passee). Contrairement a un ecart fixe dans le passe
        // (ex. "il y a 5 minutes"), qui created une fenetre de flakiness
        // quotidienne autour de 02:00-02:05, cette reference proche de "now"
        // est sans zone d'ambiguite.
        BackupRun lastRun = new BackupRun();
        lastRun.setStartedAt(OffsetDateTime.now());
        when(backupRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(lastRun));

        scheduler.checkAndRunIfDue();

        verify(executionService, never()).triggerScheduledBackup();
    }

    @Test
    void checkAndRunIfDue_usesOneYearAgoAsReferenceWhenNoRunHasEverHappened() {
        when(configurationService.effectiveSettings())
                .thenReturn(new EffectiveBackupSettings(true, "0 0 2 * * *", 14));
        when(executionService.isOperationInProgress()).thenReturn(false);
        when(backupRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());

        scheduler.checkAndRunIfDue();

        verify(executionService).triggerScheduledBackup();
    }
}
