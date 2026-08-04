package dev.subnetory.service;

import dev.subnetory.backup.RestoreMaintenanceGate;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthAuditRetentionSchedulerTest {

    @Test
    void purgeOldAuthAuditLogs_callsRetentionService() {
        AuthAuditRetentionService service = mock(AuthAuditRetentionService.class);
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        when(service.purgeOlderThanDays(90)).thenReturn(2);
        AuthAuditRetentionScheduler scheduler = new AuthAuditRetentionScheduler(service, gate, 90);

        scheduler.purgeOldAuthAuditLogs();

        verify(service).purgeOlderThanDays(90);
    }

    /**
     * Correctif securite MOYENNE (04/08/2026, second audit externe) :
     * RestoreMaintenanceFilter ne couvre que les requetes HTTP, jamais les
     * taches planifiees — ce garde-fou complementaire evite d'ecrire
     * (purger) pendant une restauration en cours.
     */
    @Test
    void purgeOldAuthAuditLogs_restoreInProgress_skipsPurge() {
        AuthAuditRetentionService service = mock(AuthAuditRetentionService.class);
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.begin();
        AuthAuditRetentionScheduler scheduler = new AuthAuditRetentionScheduler(service, gate, 90);

        scheduler.purgeOldAuthAuditLogs();

        verify(service, never()).purgeOlderThanDays(anyInt());
    }
}
