package dev.subnetory.security;

import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import dev.subnetory.web.MfaChallengeWebController;
import dev.subnetory.web.form.MfaConfirmForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression du correctif audit 04/08/2026 (faille HAUTE, contournement du
 * rate limiting MFA Web) : verifie sur les deux composants reels impliques
 * (un vrai {@link LoginRateLimiter}, pas un mock) qu'alterner un login
 * mot de passe valide ({@link RateLimitingAuthenticationSuccessHandler}) et
 * un code MFA invalide ({@link MfaChallengeWebController}) n'echappe plus au
 * verrouillage anti-bruteforce, meme repete au-dela du seuil.
 *
 * <p>Avant correctif, {@code RateLimitingAuthenticationSuccessHandler}
 * remettait le compteur a zero a chaque nouveau login reussi, meme MFA
 * requis non encore verifie — ce test aurait echoue en boucle infinie
 * (jamais verrouille) sur l'ancien comportement.</p>
 */
@ExtendWith(MockitoExtension.class)
class MfaLoginBypassRegressionTest {

    private static final String IP = "127.0.0.1";
    private static final String USERNAME = "jdoe";

    @Mock ClientIpResolver clientIpResolver;
    @Mock AuthAuditService authAuditService;
    @Mock MandatoryPasswordChangeService passwordChangeService;
    @Mock MfaLoginChallengeService mfaLoginChallengeService;

    LoginRateLimiter loginRateLimiter;
    RateLimitingAuthenticationSuccessHandler successHandler;
    MfaChallengeWebController mfaController;
    Authentication authentication;

    @BeforeEach
    void setUp() {
        loginRateLimiter = new LoginRateLimiter();
        successHandler = new RateLimitingAuthenticationSuccessHandler(
                loginRateLimiter, clientIpResolver, authAuditService, passwordChangeService, mfaLoginChallengeService);

        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        mfaController = new MfaChallengeWebController(
                mfaLoginChallengeService, loginRateLimiter, clientIpResolver, authAuditService, messageSource);

        authentication = new UsernamePasswordAuthenticationToken(USERNAME, "n/a", List.of());
        when(clientIpResolver.resolve(any())).thenReturn(IP);
        when(mfaLoginChallengeService.isRequired(USERNAME)).thenReturn(true);
        when(mfaLoginChallengeService.verify(anyString(), anyString())).thenReturn(false);
    }

    @Test
    void repeatedValidPasswordLoginNeverResetsThePendingMfaCounter() throws Exception {
        // LOCK_THRESHOLD - 1 cycles "login valide -> code MFA invalide" :
        // ne doit jamais verrouiller avant le dernier essai.
        for (int i = 0; i < LoginRateLimiter.LOCK_THRESHOLD - 1; i++) {
            passwordLoginSucceeds();
            assertThat(loginRateLimiter.isLocked(IP, USERNAME))
                    .as("ne doit pas etre verrouille apres %d essai(s) MFA invalide(s)", i)
                    .isFalse();

            String view = invalidMfaAttempt();
            assertThat(view).isEqualTo("auth/login-mfa");
        }

        // Un login "valide" de plus : si le bug etait encore present, il
        // remettrait le compteur MFA a zero et le seuil ne serait jamais
        // atteint, quel que soit le nombre de cycles.
        passwordLoginSucceeds();
        assertThat(loginRateLimiter.isLocked(IP, USERNAME)).isFalse();

        // Le LOCK_THRESHOLD-eme essai MFA invalide doit desormais verrouiller.
        String view = invalidMfaAttempt();

        assertThat(view).isEqualTo("redirect:/login?locked");
        assertThat(loginRateLimiter.isLocked(IP, USERNAME))
                .as("le compte doit finir verrouille malgre les re-connexions repetees")
                .isTrue();
    }

    private void passwordLoginSucceeds() throws Exception {
        successHandler.onAuthenticationSuccess(
                new MockHttpServletRequest(), new MockHttpServletResponse(), authentication);
    }

    private String invalidMfaAttempt() {
        MfaConfirmForm form = new MfaConfirmForm();
        form.setCode("000000");
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(form, "form");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);

        return mfaController.verify(
                form, binding, authentication, request, session, new ExtendedModelMap(), Locale.FRENCH);
    }
}
