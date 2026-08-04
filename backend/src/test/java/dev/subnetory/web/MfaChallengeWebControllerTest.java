package dev.subnetory.web;

import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.MfaChallengeFilter;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MfaLoginChallengeService;
import dev.subnetory.web.form.MfaConfirmForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaChallengeWebControllerTest {

    private static final Locale LOCALE = Locale.FRENCH;

    @Mock MfaLoginChallengeService mfaLoginChallengeService;
    @Mock LoginRateLimiter loginRateLimiter;
    @Mock ClientIpResolver clientIpResolver;
    @Mock AuthAuditService authAuditService;

    MfaChallengeWebController controller;
    Authentication authentication;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        controller = new MfaChallengeWebController(
                mfaLoginChallengeService, loginRateLimiter, clientIpResolver, authAuditService, messageSource);
        authentication = new UsernamePasswordAuthenticationToken("jdoe", "n/a", List.of());
    }

    @Test
    void challenge_notRequired_redirectsHome() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(false);

        String view = controller.challenge(authentication, new MockHttpSession(), new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void challenge_alreadyVerifiedInSession_redirectsHome() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED, Boolean.TRUE);

        String view = controller.challenge(authentication, session, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void challenge_required_showsForm() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.challenge(authentication, new MockHttpSession(), model);

        assertThat(view).isEqualTo("auth/login-mfa");
        assertThat(model.get("form")).isInstanceOf(MfaConfirmForm.class);
    }

    @Test
    void verify_validCode_marksSessionAndRedirectsHome() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(mfaLoginChallengeService.verify("jdoe", "123456")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");
        MockHttpSession session = new MockHttpSession();

        String view = controller.verify(form, binding, authentication, request, session, new ExtendedModelMap(), LOCALE);

        assertThat(view).isEqualTo("redirect:/");
        assertThat(session.getAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED)).isEqualTo(Boolean.TRUE);
        verify(loginRateLimiter).recordSuccess("127.0.0.1", "jdoe");
        // Correctif audit 04/08/2026, faille HAUTE : l'audit LOGIN_SUCCESS a
        // desormais lieu ici (une fois le second facteur verifie), plus dans
        // RateLimitingAuthenticationSuccessHandler.
        verify(authAuditService).recordLoginSuccess("jdoe", "127.0.0.1", null);
    }

    @Test
    void verify_invalidCode_redisplaysFormWithErrorAndRecordsFailure() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(mfaLoginChallengeService.verify("jdoe", "000000")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(loginRateLimiter.recordFailure("127.0.0.1", "jdoe"))
                .thenReturn(new LoginRateLimiter.RateLimitDecision(false, false, Duration.ZERO));
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("000000");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");
        MockHttpSession session = new MockHttpSession();
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.verify(form, binding, authentication, request, session, model, LOCALE);

        assertThat(view).isEqualTo("auth/login-mfa");
        assertThat(model.get("flashError")).isNotNull();
        assertThat(session.getAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED)).isNull();
        verify(authAuditService).recordMfaChallengeFailed("jdoe", "127.0.0.1", null);
    }

    @Test
    void verify_tooManyFailures_invalidatesSessionAndRedirectsToLockedLogin() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        when(mfaLoginChallengeService.verify("jdoe", "000000")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(loginRateLimiter.recordFailure("127.0.0.1", "jdoe"))
                .thenReturn(new LoginRateLimiter.RateLimitDecision(false, true, Duration.ofMinutes(15)));
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("000000");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");

        String view = controller.verify(form, binding, authentication, request, session, new ExtendedModelMap(), LOCALE);

        assertThat(view).isEqualTo("redirect:/login?locked");
        assertThat(session.isInvalid()).isTrue();
        verify(authAuditService).recordLoginLocked(
                org.mockito.ArgumentMatchers.eq("jdoe"), org.mockito.ArgumentMatchers.eq("127.0.0.1"), any(), any());
        verify(authAuditService, never()).recordMfaChallengeFailed(any(), any(), any());
    }

    @Test
    void verify_ipAlreadyLocked_invalidatesSessionAndRedirectsWithoutCallingMfaService() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        when(clientIpResolver.resolve(request)).thenReturn("127.0.0.1");
        when(loginRateLimiter.isLocked("127.0.0.1", "jdoe")).thenReturn(true);
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");

        String view = controller.verify(form, binding, authentication, request, session, new ExtendedModelMap(), LOCALE);

        assertThat(view).isEqualTo("redirect:/login?locked");
        assertThat(session.isInvalid()).isTrue();
        verify(mfaLoginChallengeService, never()).verify(any(), any());
    }

    @Test
    void verify_notRequired_redirectsHomeWithoutTouchingRateLimiter() {
        when(mfaLoginChallengeService.isRequired("jdoe")).thenReturn(false);
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("123456");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");

        String view = controller.verify(
                form, binding, authentication, new MockHttpServletRequest(), new MockHttpSession(),
                new ExtendedModelMap(), LOCALE);

        assertThat(view).isEqualTo("redirect:/");
        verify(loginRateLimiter, never()).isLocked(any(), any());
    }
}
