package dev.subnetory.service;

import dev.subnetory.backup.RestoreMaintenanceGate;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevokedTokenPurgeSchedulerTest {

    @Test
    void purgeExpiredRevokedTokens_callsPurgeService() {
        RevokedTokenPurgeService service = mock(RevokedTokenPurgeService.class);
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        when(service.purgeExpiredTokens()).thenReturn(2);
        RevokedTokenPurgeScheduler scheduler = new RevokedTokenPurgeScheduler(service, gate);

        scheduler.purgeExpiredRevokedTokens();

        verify(service).purgeExpiredTokens();
    }

    /**
     * Correctif securite MOYENNE (04/08/2026, second audit externe) :
     * RestoreMaintenanceFilter ne couvre que les requetes HTTP, jamais les
     * taches planifiees — ce garde-fou complementaire evite d'ecrire
     * (purger) pendant une restauration en cours.
     */
    @Test
    void purgeExpiredRevokedTokens_restoreInProgress_skipsPurge() {
        RevokedTokenPurgeService service = mock(RevokedTokenPurgeService.class);
        RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
        gate.begin();
        RevokedTokenPurgeScheduler scheduler = new RevokedTokenPurgeScheduler(service, gate);

        scheduler.purgeExpiredRevokedTokens();

        verify(service, never()).purgeExpiredTokens();
    }
}
