package dev.subnetory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class OpenApiSessionGuardFilterTest {

    @Mock
    MandatoryPasswordChangeService passwordChangeService;

    @Mock
    MfaLoginChallengeService mfaLoginChallengeService;

    OpenApiSessionGuardFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OpenApiSessionGuardFilter(passwordChangeService, mfaLoginChallengeService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("jdoe", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginComplete_keepsAuthentication() throws Exception {
        assertThat(authenticationSeenByChain(new MockHttpServletRequest("GET", "/v3/api-docs")))
                .isNotNull();
    }

    @Test
    void mfaRequired_notVerified_dropsAuthentication() throws Exception {
        lenient().when(passwordChangeService.isRequired("jdoe")).thenReturn(false);
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);

        assertThat(authenticationSeenByChain(new MockHttpServletRequest("GET", "/v3/api-docs")))
                .isNull();
    }

    @Test
    void mfaRequired_verified_keepsAuthentication() throws Exception {
        lenient().when(passwordChangeService.isRequired("jdoe")).thenReturn(false);
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED, true);
        request.setSession(session);

        assertThat(authenticationSeenByChain(request)).isNotNull();
    }

    @Test
    void passwordChangeRequired_dropsAuthentication() throws Exception {
        when(passwordChangeService.isRequired("jdoe")).thenReturn(true);

        assertThat(authenticationSeenByChain(new MockHttpServletRequest("GET", "/v3/api-docs")))
                .isNull();
    }

    @Test
    void jwtAuthentication_isNotAffected() throws Exception {
        org.springframework.security.oauth2.jwt.Jwt jwt =
                org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
                        .header("alg", "none")
                        .subject("jdoe")
                        .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        request.addHeader("Authorization", "Bearer token");

        assertThat(authenticationSeenByChain(request)).isNotNull();
    }

    @Test
    void anyAuthorizationHeader_doesNotBypassGuard_mfaNotVerified() throws Exception {
        lenient().when(passwordChangeService.isRequired("jdoe")).thenReturn(false);
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        request.addHeader("Authorization", "Basic Zm9vOmJhcg==");

        assertThat(authenticationSeenByChain(request)).isNull();
    }

    @Test
    void anyAuthorizationHeader_doesNotBypassGuard_passwordChangeRequired() throws Exception {
        when(passwordChangeService.isRequired("jdoe")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v3/api-docs");
        request.addHeader("Authorization", "Bearer not-a-real-token");

        assertThat(authenticationSeenByChain(request)).isNull();
    }

    private Authentication authenticationSeenByChain(MockHttpServletRequest request)
            throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) ->
                seen.set(SecurityContextHolder.getContext().getAuthentication());
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen.get();
    }
}
