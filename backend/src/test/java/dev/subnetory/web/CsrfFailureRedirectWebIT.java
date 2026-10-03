package dev.subnetory.web;

import dev.subnetory.backup.RestoreMaintenanceGate;
import dev.subnetory.config.SecurityConfig;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A page opened before a restart (sessions are in memory) or after a session
 * expiry carries a stale CSRF token. Without an authenticated session a browser
 * must land on the login page with a message, not on a bare 403.
 */
@WebMvcTest(NetworkContextWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class CsrfFailureRedirectWebIT {

    @Autowired
    MockMvc mvc;

    @MockitoBean NetworkContextService contextService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;
    @MockitoBean AuthAuditService authAuditService;
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;
    @MockitoBean RestoreMaintenanceGate restoreMaintenanceGate;

    @BeforeEach
    void setUp() {
        // The restore-maintenance filter runs before the CSRF filter and rejects
        // every mutation (503) when the gate does not admit it.
        when(restoreMaintenanceGate.tryAdmitMutation()).thenReturn(true);
    }

    @Test
    void loginPost_withoutCsrfToken_browser_redirectsToExpired() throws Exception {
        mvc.perform(post("/login")
                        .accept(MediaType.TEXT_HTML)
                        .param("username", "u")
                        .param("password", "p"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void loginPost_withStaleCsrfToken_browser_redirectsToExpired() throws Exception {
        mvc.perform(post("/login")
                        .with(csrf().useInvalidToken())
                        .accept(MediaType.TEXT_HTML)
                        .param("username", "u")
                        .param("password", "p"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void logoutPost_withStaleCsrfToken_anonymousBrowser_redirectsToLogout() throws Exception {
        mvc.perform(post("/logout")
                        .with(csrf().useInvalidToken())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?logout"));
    }

    @Test
    void loginPost_withoutCsrfToken_noAcceptHeader_redirectsToExpired() throws Exception {
        mvc.perform(post("/login")
                        .param("username", "u"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void post_withoutCsrfToken_authenticatedBrowser_stays403() throws Exception {
        mvc.perform(post("/network/contexts/1/delete")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void post_withStaleCsrfToken_authenticatedBrowser_stays403() throws Exception {
        mvc.perform(post("/network/contexts/1/delete")
                        .with(csrf().useInvalidToken())
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());
    }
}
