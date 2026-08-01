package dev.subnetory.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.dto.RequiredPasswordChangeRequest;
import dev.subnetory.dto.TokenRequest;
import dev.subnetory.exception.ConflictException;
import dev.subnetory.exception.PasswordChangeRequiredException;
import dev.subnetory.repository.RevokedTokenRepository;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.JwtLogoutDecoder;
import dev.subnetory.security.JwtTokenService;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.service.UserTokenInvalidationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class AuthControllerMandatoryPasswordChangeTest {

    @Mock AuthenticationManager authenticationManager;
    @Mock JwtTokenService jwtTokenService;
    @Mock LoginRateLimiter loginRateLimiter;
    @Mock ClientIpResolver clientIpResolver;
    @Mock AuthAuditService authAuditService;
    @Mock RevokedTokenRepository revokedTokenRepository;
    @Mock JwtLogoutDecoder jwtLogoutDecoder;
    @Mock UserTokenInvalidationService userTokenInvalidationService;
    @Mock MandatoryPasswordChangeService passwordChangeService;
    @Mock UserAdminService userAdminService;
    @Mock MfaLoginChallengeService mfaLoginChallengeService;

    AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(
                authenticationManager,
                jwtTokenService,
                loginRateLimiter,
                clientIpResolver,
                authAuditService,
                revokedTokenRepository,
                jwtLogoutDecoder,
                userTokenInvalidationService,
                passwordChangeService,
                userAdminService,
                mfaLoginChallengeService);
    }

    @Test
    void validCredentialsWithRequiredChange_doNotIssueJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Authentication authentication = authentication();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(passwordChangeService.isRequired("admin")).thenReturn(true);

        assertThatThrownBy(() -> controller.token(
                        new TokenRequest("admin", "temporary-password"),
                        request))
                .isInstanceOf(PasswordChangeRequiredException.class);

        verify(jwtTokenService, never()).generateToken(any());
    }

    @Test
    void validCredentialsAfterPasswordChange_issueJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Authentication authentication = authentication();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(passwordChangeService.isRequired("admin")).thenReturn(false);
        when(jwtTokenService.generateToken(authentication)).thenReturn("jwt-token");
        when(jwtTokenService.getExpirationMinutes()).thenReturn(60L);

        var response = controller.token(
                new TokenRequest("admin", "new-password"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isEqualTo("jwt-token");
        assertThat(response.getBody().expiresInSeconds()).isEqualTo(3600L);
    }

    // -------------------------------------------------------
    // POST /api/v1/auth/change-password-required
    // -------------------------------------------------------

    @Test
    void requiredPasswordChange_withValidCredentials_delegatesAndReturns204() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "JUnit");

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication());
        when(passwordChangeService.isRequired("admin")).thenReturn(true);

        var response = controller.changeRequiredPassword(
                new RequiredPasswordChangeRequest("admin", "temporary-password", "NewStrongPass123!"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(userAdminService).changeOwnPassword(
                "admin", "temporary-password", "NewStrongPass123!", "127.0.0.1", "JUnit");
        verify(jwtTokenService, never()).generateToken(any());
    }

    @Test
    void requiredPasswordChange_withoutPendingChange_returns409WithoutTouchingPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication());
        when(passwordChangeService.isRequired("admin")).thenReturn(false);

        assertThatThrownBy(() -> controller.changeRequiredPassword(
                        new RequiredPasswordChangeRequest("admin", "current", "NewStrongPass123!"),
                        request))
                .isInstanceOf(ConflictException.class);

        verify(userAdminService, never()).changeOwnPassword(
                any(), any(), any(), any(), any());
    }

    @Test
    void requiredPasswordChange_withInvalidCredentials_neverTouchesPassword() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(loginRateLimiter.recordFailure("127.0.0.1"))
                .thenReturn(new LoginRateLimiter.RateLimitDecision(false, false, java.time.Duration.ZERO));
        when(authenticationManager.authenticate(any()))
                .thenThrow(new org.springframework.security.authentication.BadCredentialsException("bad"));

        assertThatThrownBy(() -> controller.changeRequiredPassword(
                        new RequiredPasswordChangeRequest("admin", "wrong", "NewStrongPass123!"),
                        request))
                .isInstanceOf(org.springframework.security.core.AuthenticationException.class);

        verify(userAdminService, never()).changeOwnPassword(
                any(), any(), any(), any(), any());
    }

    private Authentication authentication() {
        return new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of());
    }
}
