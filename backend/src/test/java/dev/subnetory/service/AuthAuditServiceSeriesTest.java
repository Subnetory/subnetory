package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import dev.subnetory.domain.AuthAuditLog;
import dev.subnetory.repository.AuthAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthAuditServiceSeriesTest {

    @Mock
    AuthAuditLogRepository repository;

    @Test
    void recordAddressSeriesReserved_persistsSeriesDetails() {
        AuthAuditService service = new AuthAuditService(repository);

        service.recordAddressSeriesReserved("alice", 42L, "10.10.10.0/24", 3, "10.10.10.21", "10.10.10.23");

        ArgumentCaptor<AuthAuditLog> captor = ArgumentCaptor.forClass(AuthAuditLog.class);
        verify(repository).save(captor.capture());
        AuthAuditLog log = captor.getValue();
        assertThat(log.getEventType()).isEqualTo(AuthAuditService.ADDRESS_SERIES_RESERVED);
        assertThat(log.getUsername()).isEqualTo("alice");
        assertThat(log.isSuccess()).isTrue();
        assertThat(log.getMessage()).contains(
                "subnetId=42", "network=10.10.10.0/24", "count=3", "first=10.10.10.21", "last=10.10.10.23");
    }
}
