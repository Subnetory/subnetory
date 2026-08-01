package dev.subnetory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.service.MfaLoginChallengeService;
import jakarta.servlet.FilterChain;
import java.util.List;
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
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class MfaChallengeFilterTest {

    @Mock
    MfaLoginChallengeService mfaLoginChallengeService;

    MfaChallengeFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MfaChallengeFilter(mfaLoginChallengeService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mfaRequired_notYetVerified_redirectsToChallengePage() throws Exception {
        authenticate("jdoe");
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);

        MockHttpServletRequest request = request("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login/mfa");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void mfaRequired_allowsChallengePageItself() throws Exception {
        authenticate("jdoe");
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);

        MockHttpServletRequest request = request("GET", "/login/mfa");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void mfaRequired_allowsLogout() throws Exception {
        authenticate("jdoe");
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);

        MockHttpServletRequest request = request("POST", "/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void mfaRequired_allowsStaticAssets() throws Exception {
        authenticate("jdoe");
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);

        MockHttpServletRequest request = request("GET", "/assets/css/app.css");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void mfaRequired_verifiedInSession_isNotBlockedAgain() throws Exception {
        authenticate("jdoe");
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);

        MockHttpServletRequest request = request("GET", "/");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED, Boolean.TRUE);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void mfaNotEnabled_canAccessDashboard() throws Exception {
        authenticate("jdoe");
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(false);

        MockHttpServletRequest request = request("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getRedirectedUrl()).isNull();
    }

    @Test
    void anonymousRequest_isNotBlocked() throws Exception {
        MockHttpServletRequest request = request("GET", "/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private MockHttpServletRequest request(String method, String servletPath) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, servletPath);
        request.setServletPath(servletPath);
        return request;
    }

    private void authenticate(String username) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        username, "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
