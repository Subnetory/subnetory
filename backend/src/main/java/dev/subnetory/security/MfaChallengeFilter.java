package dev.subnetory.security;

import dev.subnetory.service.MfaLoginChallengeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bloque toutes les fonctions Web tant que le second facteur MFA n'a pas ete
 * verifie pour la session courante, pour les comptes ayant le MFA active.
 *
 * Sprint 2.37 / Lot 3. Meme patron que {@link MandatoryPasswordChangeFilter} :
 * ajoute juste apres celui-ci dans {@code webFilterChain} (le changement de
 * mot de passe obligatoire est traite en priorite).
 */
public class MfaChallengeFilter extends OncePerRequestFilter {

    public static final String MFA_CHALLENGE_PATH = "/login/mfa";
    public static final String SESSION_MFA_VERIFIED = "mfaVerified";

    private final MfaLoginChallengeService mfaLoginChallengeService;

    public MfaChallengeFilter(MfaLoginChallengeService mfaLoginChallengeService) {
        this.mfaLoginChallengeService = mfaLoginChallengeService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (mfaLoginChallengeService != null
                && isAuthenticated(authentication)
                && mfaLoginChallengeService.isRequired(authentication.getName())
                && !isMfaVerified(request)
                && !isAllowedRequest(request)) {
            response.sendRedirect(request.getContextPath() + MFA_CHALLENGE_PATH);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private boolean isMfaVerified(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(SESSION_MFA_VERIFIED));
    }

    private boolean isAllowedRequest(HttpServletRequest request) {
        String path = request.getServletPath();

        return MFA_CHALLENGE_PATH.equals(path)
                || "/logout".equals(path)
                || "/error".equals(path)
                || path.startsWith("/error/")
                || path.startsWith("/assets/");
    }
}
