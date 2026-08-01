package dev.subnetory.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Filtre de rate limiting generalise sur l'API (Sprint 2.36 / F7).
 *
 * <p>Positionne en amont de la chaine OAuth2 resource server dans
 * {@code apiFilterChain} (voir {@code SecurityConfig}) : une IP au-dela du
 * seuil recoit un 429 avant meme la validation du JWT, evitant un cout de
 * traitement inutile.</p>
 *
 * <p>Les sondes de disponibilite ({@code /actuator/health} et
 * {@code /actuator/health/**}) sont explicitement exemptees : elles ne
 * doivent jamais etre limitees (orchestrateurs, health checks Docker/K8s).</p>
 */
@Component
public class ApiRateLimitingFilter extends OncePerRequestFilter {

    private static final String ERROR_TYPE = "https://subnetory.dev/errors/rate-limited";
    private static final String HEALTH_PREFIX = "/actuator/health";

    private final ApiRateLimiter apiRateLimiter;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public ApiRateLimitingFilter(ApiRateLimiter apiRateLimiter,
                                  ClientIpResolver clientIpResolver,
                                  ObjectMapper objectMapper,
                                  @Value("${subnetory.security.api-rate-limit.enabled:true}") boolean enabled) {
        this.apiRateLimiter = apiRateLimiter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        if (!enabled || isExempt(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String ipAddress = clientIpResolver.resolve(request);
        ApiRateLimiter.Decision decision = apiRateLimiter.recordRequest(ipAddress);

        if (decision.limited()) {
            writeTooManyRequests(response, decision.retryAfterSeconds());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExempt(HttpServletRequest request) {
        // getRequestURI() est utilise plutot que getServletPath() : ce dernier
        // depend du mapping du DispatcherServlet et se comporte differemment
        // entre un conteneur reel et les requetes simulees par MockMvc dans
        // les tests d'integration. getRequestURI() est stable dans les deux cas.
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith(HEALTH_PREFIX);
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType("application/problem+json");

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests. Try again later.");
        problemDetail.setType(URI.create(ERROR_TYPE));
        problemDetail.setTitle("Rate Limited");
        problemDetail.setProperty("timestamp", Instant.now());

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
