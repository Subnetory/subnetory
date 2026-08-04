package dev.subnetory.security;

import tools.jackson.databind.ObjectMapper;
import dev.subnetory.backup.RestoreMaintenanceGate;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mode maintenance applicatif pendant une restauration (correctif securite
 * MOYENNE, audit 04/08/2026) — voir {@link RestoreMaintenanceGate} pour le
 * contexte complet.
 *
 * <p>Rejette (503) toute requete de mutation (methode HTTP autre que GET/
 * HEAD/OPTIONS) tant qu'une restauration est active, a l'exception de
 * l'authentification (login/logout/token) — se deconnecter ou obtenir un
 * nouveau jeton ne modifie aucune donnee metier et ne doit jamais rester
 * bloque par une restauration en cours.</p>
 *
 * <p>Positionne dans les deux chaines de securite qui acceptent des
 * mutations (API JWT et Web Thymeleaf, voir {@code SecurityConfig}) — la
 * chaine OpenAPI/Swagger est en lecture seule et n'a pas besoin d'etre
 * couverte.</p>
 */
@Component
public class RestoreMaintenanceFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS =
            Set.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(), HttpMethod.OPTIONS.name());

    private static final String ERROR_TYPE = "https://subnetory.dev/errors/restore-maintenance";

    private final RestoreMaintenanceGate gate;
    private final ObjectMapper objectMapper;

    public RestoreMaintenanceFilter(RestoreMaintenanceGate gate, ObjectMapper objectMapper) {
        this.gate = gate;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        if (!gate.isActive() || SAFE_METHODS.contains(request.getMethod()) || isExempt(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeServiceUnavailable(response);
    }

    private boolean isExempt(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        String contextPath = request.getContextPath();
        String path = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                ? uri.substring(contextPath.length())
                : uri;
        return path.startsWith("/api/v1/auth/") || path.equals("/login") || path.equals("/logout");
    }

    private void writeServiceUnavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Retry-After", "30");
        response.setContentType("application/problem+json");

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Une restauration de sauvegarde est en cours : les modifications sont temporairement "
                        + "refusees. Reessayez dans quelques instants.");
        problemDetail.setType(URI.create(ERROR_TYPE));
        problemDetail.setTitle("Restauration en cours");
        problemDetail.setProperty("timestamp", Instant.now());

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
