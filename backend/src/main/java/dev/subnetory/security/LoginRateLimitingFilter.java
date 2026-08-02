package dev.subnetory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filtre de blocage avant authentification.
 *
 * Objectif :
 * - si une IP est deja verrouillee, bloquer POST /login avant meme
 *   que Spring Security tente l'authentification ;
 * - eviter qu'un mot de passe correct puisse passer pendant la periode
 *   de verrouillage.
 */
@Component
public class LoginRateLimitingFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/login";
    private static final String LOCKED_URL = "/login?locked";

    private final LoginRateLimiter loginRateLimiter;
    private final ClientIpResolver clientIpResolver;

    public LoginRateLimitingFilter(LoginRateLimiter loginRateLimiter,
                                   ClientIpResolver clientIpResolver) {
        this.loginRateLimiter = loginRateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        if (isLoginPost(request)) {
            String ipAddress = clientIpResolver.resolve(request);
            String username = request.getParameter("username");
            if (loginRateLimiter.isLocked(ipAddress, username)) {
                response.sendRedirect(request.getContextPath() + LOCKED_URL);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isLoginPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && LOGIN_PATH.equals(request.getServletPath());
    }
}