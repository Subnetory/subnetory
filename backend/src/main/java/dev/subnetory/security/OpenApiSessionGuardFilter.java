package dev.subnetory.security;

import dev.subnetory.service.MandatoryPasswordChangeService;
import dev.subnetory.service.MfaLoginChallengeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Garde de la chaine OpenAPI / Swagger UI pour les requetes authentifiees par
 * session web (sans en-tete Authorization).
 *
 * <p>Une session peut exister avant la fin du parcours de connexion (second
 * facteur MFA non verifie, changement de mot de passe obligatoire en attente).
 * Les filtres equivalents de la chaine Web bloquent ces sessions ; cette chaine
 * ne les porte pas, donc ce filtre ecarte l'authentification de session tant
 * que ces etapes ne sont pas terminees. Le contexte est alors traite comme
 * anonyme et la requete est refusee (401 / redirection /login).</p>
 *
 * <p>Les authentifications par JWT Bearer (deja validees par le filtre
 * resource server) ne sont pas concernees. Le choix repose sur le type
 * d'authentification, jamais sur un en-tete de la requete, qui est controle
 * par l'appelant.</p>
 */
public class OpenApiSessionGuardFilter extends OncePerRequestFilter {

    private final MandatoryPasswordChangeService passwordChangeService;
    private final MfaLoginChallengeService mfaLoginChallengeService;

    public OpenApiSessionGuardFilter(MandatoryPasswordChangeService passwordChangeService,
                                     MfaLoginChallengeService mfaLoginChallengeService) {
        this.passwordChangeService = passwordChangeService;
        this.mfaLoginChallengeService = mfaLoginChallengeService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        // Decision based on the authentication type, never on a request header:
        // a header is attacker-controlled and would let a half-authenticated
        // web session skip this guard. A validated JWT has already been
        // processed by the resource-server filter and is left untouched.
        if (isAuthenticated(authentication)
                && !(authentication instanceof JwtAuthenticationToken)
                && isLoginIncomplete(request, authentication.getName())) {
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLoginIncomplete(HttpServletRequest request, String username) {
        if (passwordChangeService != null && passwordChangeService.isRequired(username)) {
            return true;
        }
        return mfaLoginChallengeService != null
                && mfaLoginChallengeService.isRequired(username)
                && !isMfaVerified(request);
    }

    private boolean isMfaVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(MfaChallengeFilter.SESSION_MFA_VERIFIED));
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
