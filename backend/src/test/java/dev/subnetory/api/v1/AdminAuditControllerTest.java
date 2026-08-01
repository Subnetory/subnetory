package dev.subnetory.api.v1;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.service.AuthAuditRetentionService;
import dev.subnetory.service.AuthAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminAuditControllerTest {

    private final AuthAuditService authAuditService = mock(AuthAuditService.class);
    private final AuthAuditRetentionService retentionService = mock(AuthAuditRetentionService.class);
    private final AdminAuditController controller = new AdminAuditController(authAuditService, retentionService);

    @Test
    void listMapsAuditLogsForApiAutomation() {
        AuthAuditLog log = new AuthAuditLog();
        log.setEventType("LOGIN_SUCCESS");
        log.setUsername("admin");
        log.setSuccess(true);
        when(authAuditService.findAuditLogs(null, null, PageRequest.of(0, 50)))
                .thenReturn(new PageImpl<>(List.of(log), PageRequest.of(0, 50), 1));

        var response = controller.list(null, null, PageRequest.of(0, 50));

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().getFirst().eventType()).isEqualTo("LOGIN_SUCCESS");
    }

    @Test
    void purge_delegatesToRetentionServiceAndReturnsDeletedCount() {
        when(retentionService.purgeOlderThan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(7);

        var response = controller.purge(new dev.subnetory.dto.AuditPurgeRequest(java.time.LocalDate.of(2026, 1, 1)));

        assertThat(response.deletedCount()).isEqualTo(7);
    }
}
