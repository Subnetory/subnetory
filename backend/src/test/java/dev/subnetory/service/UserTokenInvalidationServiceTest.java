package dev.subnetory.service;

import dev.subnetory.repository.UserTokenInvalidationRepository;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Correctif securite MOYENNE (audit 04/08/2026) : couvre
 * {@link UserTokenInvalidationService#invalidateAllTokens}, appelee apres une
 * restauration reussie ({@code dev.subnetory.backup.BackupExecutionService#restore}).
 * Le comportement mono-utilisateur ({@link UserTokenInvalidationService#invalidateTokens})
 * est deja couvert par {@code UserTokenInvalidationRepositoryIT}.
 */
class UserTokenInvalidationServiceTest {

    private final UserTokenInvalidationRepository repository = mock(UserTokenInvalidationRepository.class);
    private final UserTokenInvalidationService service = new UserTokenInvalidationService(repository);

    @Test
    void invalidateAllTokens_delegatesToBulkUpsertWithGivenActorAndReason() {
        when(repository.upsertNotBeforeForAllUsers(any(), any(), any())).thenReturn(2);

        OffsetDateTime notBefore = service.invalidateAllTokens("admin", "POST_RESTORE");

        assertThat(notBefore).isNotNull();
        verify(repository).upsertNotBeforeForAllUsers(eq(notBefore), eq("admin"), eq("POST_RESTORE"));
    }

    @Test
    void invalidateAllTokens_blankActor_fallsBackToSystem() {
        service.invalidateAllTokens("  ", "POST_RESTORE");

        verify(repository).upsertNotBeforeForAllUsers(any(), eq(UserTokenInvalidationService.ACTOR_SYSTEM), eq("POST_RESTORE"));
    }

    @Test
    void invalidateAllTokens_blankReason_fallsBackToDefaultReason() {
        service.invalidateAllTokens("admin", null);

        verify(repository).upsertNotBeforeForAllUsers(
                any(), eq("admin"), eq(UserTokenInvalidationService.REASON_POST_RESTORE));
    }
}
