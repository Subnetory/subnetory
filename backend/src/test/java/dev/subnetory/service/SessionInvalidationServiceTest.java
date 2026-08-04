package dev.subnetory.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistryImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctif securite MOYENNE (audit 04/08/2026) : voir
 * {@link SessionInvalidationService} pour le contexte complet — drainage des
 * sessions Web apres une restauration reussie.
 */
class SessionInvalidationServiceTest {

    @Test
    void expireAllSessions_marksEverySessionOfEveryPrincipalAsExpired() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        registry.registerNewSession("session-alice-1", "alice");
        registry.registerNewSession("session-alice-2", "alice");
        registry.registerNewSession("session-bob-1", "bob");
        SessionInvalidationService service = new SessionInvalidationService(registry);

        int expired = service.expireAllSessions();

        assertThat(expired).isEqualTo(3);
        assertThat(allSessionsExpired(registry, "alice")).isTrue();
        assertThat(allSessionsExpired(registry, "bob")).isTrue();
    }

    @Test
    void expireAllSessions_noSessions_returnsZero() {
        SessionRegistryImpl registry = new SessionRegistryImpl();
        SessionInvalidationService service = new SessionInvalidationService(registry);

        assertThat(service.expireAllSessions()).isZero();
    }

    private boolean allSessionsExpired(SessionRegistryImpl registry, Object principal) {
        for (SessionInformation session : registry.getAllSessions(principal, true)) {
            if (!session.isExpired()) {
                return false;
            }
        }
        return true;
    }
}
