package dev.subnetory.security;

import dev.subnetory.service.AuthAuditService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handler d'echec d'authentification Web.
 *
 * A chaque echec :
 * - recupere l'adresse IP cliente ;
 * - incremente le compteur dans LoginRateLimiter ;
 * - applique un delai apres 5 echecs ;
 * - redirige vers /login?locked apres 10 echecs ;
 * - journalise LOGIN_FAILURE / LOGIN_LOCKED ;
 * - garde un message generique cote UI.
 */
@Component
public class RateLimitingAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private static final String DEFAULT_FAILURE_URL = "/login?error";
    private static final String LOCKED_FAILURE_URL = "/login?locked";

    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuthAuditService authAuditService;

    public RateLimitingAuthenticationFailureHandler(LoginRateLimiter loginRateLimiter,
                                                    ClientIpResolver clientIpResolver,
                                                    AuthAuditService authAuditService) {
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.authAuditService = authAuditService;
        setDefaultFailureUrl(DEFAULT_FAILURE_URL);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request,
                                        HttpServletResponse response,
                                        AuthenticationException exception)
            throws IOException, ServletException {

        String ipAddress = clientIpResolver.resolve(request);
        String username = request.getParameter("username");
        String userAgent = request.getHeader("User-Agent");

        LoginRateLimiter.RateLimitDecision decision = loginRateLimiter.recordFailure(ipAddress, username);

        if (decision.locked()) {
            authAuditService.recordLoginLocked(
                    username,
                    ipAddress,
                    userAgent,
                    "Trop de tentatives de connexion. Compte temporairement bloque cote IP.");
            getRedirectStrategy().sendRedirect(request, response, LOCKED_FAILURE_URL);
            return;
        }

        authAuditService.recordLoginFailure(
                username,
                ipAddress,
                userAgent,
                "Echec d'authentification.");

        if (decision.delayed()) {
            applyDelay(decision);
        }

        super.onAuthenticationFailure(request, response, exception);
    }

    private void applyDelay(LoginRateLimiter.RateLimitDecision decision) {
        try {
            Thread.sleep(decision.waitDuration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}