package dev.subnetory.security;

import dev.subnetory.service.MandatoryPasswordChangeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Bloque toutes les fonctions Web tant que le mot de passe temporaire
 * ou initial du compte local n'a pas ete remplace.
 */
public class MandatoryPasswordChangeFilter extends OncePerRequestFilter {

    private final MandatoryPasswordChangeService passwordChangeService;

    public MandatoryPasswordChangeFilter(
            MandatoryPasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (passwordChangeService != null
                && isAuthenticated(authentication)
                && passwordChangeService.isRequired(authentication.getName())
                && !isAllowedRequest(request)) {
            response.sendRedirect(
                    request.getContextPath()
                            + MandatoryPasswordChangeService.REQUIRED_CHANGE_PATH);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private boolean isAllowedRequest(HttpServletRequest request) {
        String path = request.getServletPath();

        return MandatoryPasswordChangeService.REQUIRED_CHANGE_PATH.equals(path)
                || "/logout".equals(path)
                || "/error".equals(path)
                || path.startsWith("/error/")
                || path.startsWith("/assets/");
    }
}
