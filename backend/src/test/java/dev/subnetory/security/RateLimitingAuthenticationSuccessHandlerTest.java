package dev.subnetory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class RateLimitingAuthenticationSuccessHandlerTest {

    @Mock LoginRateLimiter loginRateLimiter;
    @Mock ClientIpResolver clientIpResolver;
    @Mock AuthAuditService authAuditService;
    @Mock MandatoryPasswordChangeService passwordChangeService;
    @Mock MfaLoginChallengeService mfaLoginChallengeService;

    RateLimitingAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RateLimitingAuthenticationSuccessHandler(
                loginRateLimiter,
                clientIpResolver,
                authAuditService,
                passwordChangeService,
                mfaLoginChallengeService);
    }

    @Test
    void requiredChange_redirectsImmediatelyToMandatoryPage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(mfaLoginChallengeService.isRequired("admin")).thenReturn(false);
        when(passwordChangeService.isRequired("admin")).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl())
                .endsWith("/profile/change-password-required");
        verify(loginRateLimiter).recordSuccess("127.0.0.1", "admin");
        verify(authAuditService).recordLoginSuccess(
                "admin", "127.0.0.1", null);
    }

    @Test
    void normalLogin_redirectsToDashboard() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(mfaLoginChallengeService.isRequired("admin")).thenReturn(false);
        when(passwordChangeService.isRequired("admin")).thenReturn(false);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).endsWith("/");
        verify(loginRateLimiter).recordSuccess("127.0.0.1", "admin");
        verify(authAuditService).recordLoginSuccess("admin", "127.0.0.1", null);
    }

    /**
     * Regression (audit 04/08/2026, faille HAUTE) : quand le MFA est requis,
     * ce handler ne doit ni reinitialiser le compteur de rate limiting, ni
     * journaliser LOGIN_SUCCESS — la connexion n'est pas terminee tant que
     * le second facteur n'a pas ete verifie (voir
     * MfaChallengeWebController#verify, qui effectue desormais ces deux
     * actions a la place, apres validation effective du code).
     */
    @Test
    void mfaRequired_doesNotResetRateLimiterOrAuditSuccessYet() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication authentication = authentication();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(mfaLoginChallengeService.isRequired("admin")).thenReturn(true);
        when(passwordChangeService.isRequired("admin")).thenReturn(false);

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).endsWith("/");
        verify(loginRateLimiter, never()).recordSuccess(anyString(), anyString());
        verify(authAuditService, never()).recordLoginSuccess(anyString(), anyString(), any());
    }

    private Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of());
    }
}
