package dev.subnetory.security;

import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.WebAttributes;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handler de succes d'authentification Web.
 *
 * Objectif :
 * - remettre a zero le compteur de rate limiting pour l'IP cliente ;
 * - journaliser LOGIN_SUCCESS ;
 * - conserver le comportement actuel : redirection vers "/" apres login.
 *
 * <p>Correctif audit 04/08/2026, faille HAUTE — contournement du rate
 * limiting MFA : ce handler est invoque par Spring Security immediatement
 * apres validation du mot de passe, avant tout second facteur. Il remettait
 * jusqu'ici inconditionnellement a zero le compteur {@link LoginRateLimiter}
 * et journalisait {@code LOGIN_SUCCESS}, meme quand le compte a le MFA actif
 * et que la connexion n'est donc pas terminee. Un attaquant connaissant le
 * mot de passe pouvait alors alterner « re-soumission du formulaire de
 * login » (reset gratuit du compteur ici) et « un essai de code TOTP »
 * ({@code MfaChallengeWebController#verify}, qui incremente le compteur sur
 * un code invalide), sans jamais atteindre le seuil de verrouillage sur le
 * second facteur. Desormais, si le MFA est requis pour ce compte, le reset
 * et l'audit de succes sont differes jusqu'a la verification effective du
 * second facteur ({@link MfaLoginChallengeService}), exactement le meme
 * principe deja applique cote API (voir {@code AuthController#token},
 * commentaire "on ne recompte/n'audite le succes qu'apres ce second
 * facteur").</p>
 */
@Component
public class RateLimitingAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuthAuditService authAuditService;
    private final MandatoryPasswordChangeService passwordChangeService;
    private final MfaLoginChallengeService mfaLoginChallengeService;

    public RateLimitingAuthenticationSuccessHandler(
            LoginRateLimiter loginRateLimiter,
            ClientIpResolver clientIpResolver,
            AuthAuditService authAuditService,
            MandatoryPasswordChangeService passwordChangeService,
            MfaLoginChallengeService mfaLoginChallengeService) {
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.authAuditService = authAuditService;
        this.passwordChangeService = passwordChangeService;
        this.mfaLoginChallengeService = mfaLoginChallengeService;
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication)
            throws IOException, ServletException {

        String ipAddress = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");
        String username = authentication.getName();

        if (!mfaLoginChallengeService.isRequired(username)) {
            loginRateLimiter.recordSuccess(ipAddress, username);
            authAuditService.recordLoginSuccess(username, ipAddress, userAgent);
        }

        if (passwordChangeService.isRequired(username)) {
            if (request.getSession(false) != null) {
                request.getSession(false).removeAttribute(
                        WebAttributes.AUTHENTICATION_EXCEPTION);
            }
            getRedirectStrategy().sendRedirect(
                    request,
                    response,
                    MandatoryPasswordChangeService.REQUIRED_CHANGE_PATH);
            return;
        }

        super.onAuthenticationSuccess(request, response, authentication);
    }
}