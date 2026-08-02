package dev.subnetory.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.dto.TokenRequest;
import dev.subnetory.exception.MfaChallengeFailedException;
import dev.subnetory.exception.MfaRequiredException;
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
import java.time.Duration;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthControllerMfaTest {

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

    private Authentication authentication() {
        return new UsernamePasswordAuthenticationToken("jdoe", "n/a", List.of());
    }

    @Test
    void mfaEnabled_noCodeProvided_throwsMfaRequiredAndRecordsFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication());
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(loginRateLimiter.recordFailure("127.0.0.1", "jdoe"))
                .thenReturn(new LoginRateLimiter.RateLimitDecision(false, false, Duration.ZERO));

        assertThatThrownBy(() -> controller.token(
                        new TokenRequest("jdoe", "GoodPass123!"),
                        request))
                .isInstanceOf(MfaRequiredException.class);

        verify(mfaLoginChallengeService, never()).verify(any(), any());
        verify(authAuditService).recordMfaChallengeFailed("jdoe", "127.0.0.1", null);
        verify(jwtTokenService, never()).generateToken(any());
        verify(loginRateLimiter, never()).recordSuccess(any(), any());
    }

    @Test
    void mfaEnabled_invalidCode_throwsMfaChallengeFailedAndRecordsFailure() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication());
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(mfaLoginChallengeService.verify("jdoe", "000000")).thenReturn(false);
        when(loginRateLimiter.recordFailure("127.0.0.1", "jdoe"))
                .thenReturn(new LoginRateLimiter.RateLimitDecision(false, false, Duration.ZERO));

        assertThatThrownBy(() -> controller.token(
                        new TokenRequest("jdoe", "GoodPass123!", "000000"),
                        request))
                .isInstanceOf(MfaChallengeFailedException.class);

        verify(authAuditService).recordMfaChallengeFailed("jdoe", "127.0.0.1", null);
        verify(jwtTokenService, never()).generateToken(any());
    }

    @Test
    void mfaEnabled_tooManyChallengeFailures_locksAndReturns429() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication());
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(mfaLoginChallengeService.verify("jdoe", "000000")).thenReturn(false);
        when(loginRateLimiter.recordFailure("127.0.0.1", "jdoe"))
                .thenReturn(new LoginRateLimiter.RateLimitDecision(false, true, Duration.ofMinutes(15)));

        assertThatThrownBy(() -> controller.token(
                        new TokenRequest("jdoe", "GoodPass123!", "000000"),
                        request))
                .isInstanceOf(ResponseStatusException.class);

        verify(authAuditService).recordLoginLocked(
                org.mockito.ArgumentMatchers.eq("jdoe"),
                org.mockito.ArgumentMatchers.eq("127.0.0.1"),
                any(),
                any());
        verify(authAuditService, never()).recordMfaChallengeFailed(any(), any(), any());
        verify(jwtTokenService, never()).generateToken(any());
    }

    @Test
    void mfaEnabled_validCode_issuesJwt() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Authentication authentication = authentication();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(mfaLoginChallengeService.verify("jdoe", "123456")).thenReturn(true);
        when(passwordChangeService.isRequired("jdoe")).thenReturn(false);
        when(jwtTokenService.generateToken(authentication)).thenReturn("jwt-token");
        when(jwtTokenService.getExpirationMinutes()).thenReturn(60L);

        var response = controller.token(
                new TokenRequest("jdoe", "GoodPass123!", "123456"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().accessToken()).isEqualTo("jwt-token");
        verify(loginRateLimiter).recordSuccess("127.0.0.1", "jdoe");
        verify(authAuditService).recordLoginSuccess("jdoe", "127.0.0.1", null);
        verify(authAuditService, never()).recordMfaChallengeFailed(any(), any(), any());
    }

    @Test
    void mfaNotEnabled_ignoresTotpCodeField() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Authentication authentication = authentication();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(authenticationManager.authenticate(any())).thenReturn(authentication);
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(false);
        when(passwordChangeService.isRequired("jdoe")).thenReturn(false);
        when(jwtTokenService.generateToken(authentication)).thenReturn("jwt-token");
        when(jwtTokenService.getExpirationMinutes()).thenReturn(60L);

        var response = controller.token(
                new TokenRequest("jdoe", "GoodPass123!"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(mfaLoginChallengeService, never()).verify(any(), any());
    }
}
