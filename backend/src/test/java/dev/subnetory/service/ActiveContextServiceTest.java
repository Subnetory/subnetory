package dev.subnetory.service;

import dev.subnetory.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActiveContextServiceTest {

    private final ContextAccessService contextAccessService = mock(ContextAccessService.class);
    private final ActiveContextService activeContextService = new ActiveContextService(contextAccessService);

    @Test
    void selectStoresOnlyAuthorizedContext() {
        MockHttpSession session = new MockHttpSession();

        activeContextService.select(session, 7L);

        verify(contextAccessService).requireAccess(7L);
        assertThat(session.getAttribute(ActiveContextService.SESSION_KEY)).isEqualTo(7L);
    }

    @Test
    void selectRejectsUnauthorizedContextAndKeepsPreviousSelection() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ActiveContextService.SESSION_KEY, 2L);
        doThrow(new ResourceNotFoundException("NetworkContext", 9L))
                .when(contextAccessService).requireAccess(9L);

        assertThatThrownBy(() -> activeContextService.select(session, 9L))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(session.getAttribute(ActiveContextService.SESSION_KEY)).isEqualTo(2L);
    }

    @Test
    void getClearsStoredContextWhenAccessIsRevoked() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ActiveContextService.SESSION_KEY, 3L);
        when(contextAccessService.canAccess(3L)).thenReturn(false);

        Long activeContextId = activeContextService.get(session);

        assertThat(activeContextId).isNull();
        assertThat(session.getAttribute(ActiveContextService.SESSION_KEY)).isNull();
    }
}
