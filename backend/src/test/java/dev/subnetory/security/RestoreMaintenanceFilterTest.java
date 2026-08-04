package dev.subnetory.security;

import dev.subnetory.backup.RestoreMaintenanceGate;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Correctif securite MOYENNE (audit 04/08/2026) : voir
 * {@link RestoreMaintenanceGate} pour le contexte complet.
 */
class RestoreMaintenanceFilterTest {

    private final RestoreMaintenanceGate gate = new RestoreMaintenanceGate();
    private final RestoreMaintenanceFilter filter =
            new RestoreMaintenanceFilter(gate, JsonMapper.builder().build());

    @Test
    void gateInactive_mutatingRequestPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/subnets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }

    @Test
    void gateActive_mutatingRequest_returns503AndNeverReachesChain() throws Exception {
        gate.begin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/subnets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void gateActive_getRequest_stillPassesThrough() throws Exception {
        gate.begin();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subnets");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain, times(1)).doFilter(request, response);
    }

    /**
     * Correctif securite MOYENNE (04/08/2026, second audit externe) :
     * {@code POST /login} exemptait auparavant l'authentification —
     * desormais bloque comme n'importe quelle autre mutation, une nouvelle
     * authentification ecrivant elle aussi en base (journal d'audit,
     * compteur anti-bruteforce) pendant une restauration en cours.
     */
    @Test
    void gateActive_loginRequest_isBlockedLikeAnyOtherMutation() throws Exception {
        gate.begin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(request, response);
    }

    /**
     * Meme motif que ci-dessus pour {@code /api/v1/auth/token} : ecrit dans
     * le journal d'audit d'authentification et peut consommer un code MFA,
     * ce n'est pas un endpoint en lecture seule.
     */
    @Test
    void gateActive_authTokenRequest_isBlockedLikeAnyOtherMutation() throws Exception {
        gate.begin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(request, response);
    }

    /**
     * Nouveau test (04/08/2026, second audit externe) : {@code /logout}
     * (Web) et {@code /api/v1/auth/logout}/{@code /logout-all} (API)
     * ecrivent egalement en base (revocation JWT, horodatage
     * d'invalidation) — bloques comme toute autre mutation, sans exception.
     */
    @Test
    void gateActive_logoutRequest_isBlockedLikeAnyOtherMutation() throws Exception {
        gate.begin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void gateActive_apiLogoutRequest_isBlockedLikeAnyOtherMutation() throws Exception {
        gate.begin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(chain, never()).doFilter(request, response);
    }
}
