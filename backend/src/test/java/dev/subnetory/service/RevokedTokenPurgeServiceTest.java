package dev.subnetory.service;

import dev.subnetory.repository.RevokedTokenRepository;
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
class RevokedTokenPurgeServiceTest {

    @Mock
    RevokedTokenRepository repository;

    @InjectMocks
    RevokedTokenPurgeService service;

    @Test
    void purgeExpiredBefore_deletesTokensExpiredBeforeCutoff() {
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        when(repository.deleteByExpiresAtBefore(cutoff)).thenReturn(4);

        int deleted = service.purgeExpiredBefore(cutoff);

        assertEquals(4, deleted);
        verify(repository).deleteByExpiresAtBefore(cutoff);
    }

    @Test
    void purgeExpiredBefore_rejectsNullCutoff() {
        assertThrows(IllegalArgumentException.class, () -> service.purgeExpiredBefore(null));
    }
}
