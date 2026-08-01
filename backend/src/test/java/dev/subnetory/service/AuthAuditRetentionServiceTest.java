package dev.subnetory.service;

import dev.subnetory.repository.AuthAuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthAuditRetentionServiceTest {

    @Mock
    AuthAuditLogRepository repository;

    @InjectMocks
    AuthAuditRetentionService service;

    @Test
    void purgeOlderThan_deletesEntriesOlderThanCutoff() {
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(repository.deleteOlderThan(cutoff)).thenReturn(3);

        int deleted = service.purgeOlderThan(cutoff);

        assertEquals(3, deleted);
        verify(repository).deleteOlderThan(cutoff);
    }

    @Test
    void purgeOlderThan_rejectsNullCutoff() {
        assertThrows(IllegalArgumentException.class, () -> service.purgeOlderThan(null));
    }

    @Test
    void purgeOlderThanDays_rejectsInvalidRetentionDays() {
        assertThrows(IllegalArgumentException.class, () -> service.purgeOlderThanDays(0));
        assertThrows(IllegalArgumentException.class, () -> service.purgeOlderThanDays(-1));
    }
}
