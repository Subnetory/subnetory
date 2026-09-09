package dev.subnetory.service;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.repository.AuthAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthAuditServiceScanTest {

    @Mock
    AuthAuditLogRepository repository;

    @Test
    void recordSubnetScanCompleted_persistsOperationalDetails() {
        AuthAuditService service = new AuthAuditService(repository);

        service.recordSubnetScanCompleted("alice", 42L, "10.0.0.0/30", 2, 1, 1, 0);

        AuthAuditLog log = captureSavedLog();
        assertThat(log.getEventType()).isEqualTo(AuthAuditService.SUBNET_SCAN_COMPLETED);
        assertThat(log.getUsername()).isEqualTo("alice");
        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getMessage())
                .contains("subnetId=42", "network=10.0.0.0/30", "hostsFound=2", "created=1", "updated=1", "errors=0");
    }

    @Test
    void recordSubnetScanFailed_persistsReasonWithoutMarkingSuccess() {
        AuthAuditService service = new AuthAuditService(repository);

        service.recordSubnetScanFailed("bob", 7L, "TIMEOUT", "Scan timed out");

        AuthAuditLog log = captureSavedLog();
        assertThat(log.getEventType()).isEqualTo(AuthAuditService.SUBNET_SCAN_FAILED);
        assertThat(log.getUsername()).isEqualTo("bob");
        assertThat(log.isSuccess()).isFalse();
        assertThat(log.getMessage()).contains("subnetId=7", "reason=TIMEOUT", "Scan timed out");
    }

    private AuthAuditLog captureSavedLog() {
        ArgumentCaptor<AuthAuditLog> captor = ArgumentCaptor.forClass(AuthAuditLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
