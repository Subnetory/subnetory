package dev.subnetory.security;

import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.MandatoryPasswordChangeService;
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
 */
@Component
public class RateLimitingAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final AuthAuditService authAuditService;
    private final MandatoryPasswordChangeService passwordChangeService;

    public RateLimitingAuthenticationSuccessHandler(
            LoginRateLimiter loginRateLimiter,
            ClientIpResolver clientIpResolver,
            AuthAuditService authAuditService,
            MandatoryPasswordChangeService passwordChangeService) {
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.authAuditService = authAuditService;
        this.passwordChangeService = passwordChangeService;
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

        loginRateLimiter.recordSuccess(ipAddress);
        authAuditService.recordLoginSuccess(username, ipAddress, userAgent);

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