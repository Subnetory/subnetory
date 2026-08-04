package dev.subnetory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Correctif securite MOYENNE (audit externe 04/08/2026) : voir la javadoc
 * de {@link TrustAwareForwardedHeaderFilter} pour le contexte complet
 * (remplacement de l'auto-configuration Spring Boot de
 * {@code ForwardedHeaderFilter}, inconditionnelle et donc exploitable pour
 * usurper {@code getRemoteAddr()}/{@code isSecure()}).
 */
class TrustAwareForwardedHeaderFilterTest {

    @Test
    void trustedDirectPeer_appliesForwardedHeadersToDownstreamRequest() throws Exception {
        ClientIpResolver resolver = mock(ClientIpResolver.class);
        when(resolver.isRequestFromTrustedProxy("10.0.0.5")).thenReturn(true);
        TrustAwareForwardedHeaderFilter filter = new TrustAwareForwardedHeaderFilter(resolver);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subnets");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Forwarded-For", "198.51.100.9");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<HttpServletRequest> captor = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain, times(1)).doFilter(captor.capture(), org.mockito.ArgumentMatchers.any());
        HttpServletRequest downstream = captor.getValue();

        // La delegation vers ForwardedHeaderFilter a bien reecrit la requete
        // vue par la suite de la chaine (remoteAddr + scheme "https").
        assertThat(downstream.getRemoteAddr()).isEqualTo("198.51.100.9");
        assertThat(downstream.isSecure()).isTrue();
    }

    @Test
    void untrustedDirectPeer_passesRawRequestThroughUnmodified() throws Exception {
        ClientIpResolver resolver = mock(ClientIpResolver.class);
        when(resolver.isRequestFromTrustedProxy("203.0.113.7")).thenReturn(false);
        TrustAwareForwardedHeaderFilter filter = new TrustAwareForwardedHeaderFilter(resolver);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/subnets");
        request.setRemoteAddr("203.0.113.7");
        // Un attaquant tentant de forger son IP via l'en-tete : sans proxy
        // de confiance verifie, cet en-tete ne doit jamais etre applique.
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // Aucune delegation a ForwardedHeaderFilter : la requete brute
        // (originale, non enveloppee) est transmise telle quelle.
        verify(chain, times(1)).doFilter(request, response);
        assertThat(request.getRemoteAddr()).isEqualTo("203.0.113.7");
    }
}
