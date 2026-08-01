package dev.subnetory.service;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthAuditRetentionSchedulerTest {

    @Test
    void purgeOldAuthAuditLogs_callsRetentionService() {
        AuthAuditRetentionService service = mock(AuthAuditRetentionService.class);
        when(service.purgeOlderThanDays(90)).thenReturn(2);
        AuthAuditRetentionScheduler scheduler = new AuthAuditRetentionScheduler(service, 90);

        scheduler.purgeOldAuthAuditLogs();

        verify(service).purgeOlderThanDays(90);
    }
}