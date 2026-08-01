package dev.subnetory.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RevokedTokenPurgeSchedulerTest {

    @Test
    void purgeExpiredRevokedTokens_callsPurgeService() {
        RevokedTokenPurgeService service = mock(RevokedTokenPurgeService.class);
        when(service.purgeExpiredTokens()).thenReturn(2);
        RevokedTokenPurgeScheduler scheduler = new RevokedTokenPurgeScheduler(service);

        scheduler.purgeExpiredRevokedTokens();

        verify(service).purgeExpiredTokens();
    }
}
