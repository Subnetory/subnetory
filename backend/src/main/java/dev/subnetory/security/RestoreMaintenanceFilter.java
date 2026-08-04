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
 * MOYENNE, audit 04/08/2026 ; exemptions retirees le 04/08/2026 suite a un
 * second audit externe) — voir {@link RestoreMaintenanceGate} pour le
 * contexte complet.
 *
 * <p>Rejette (503) toute requete de mutation (methode HTTP autre que GET/
 * HEAD/OPTIONS) tant qu'une restauration est active — SANS AUCUNE
 * exception, y compris pour {@code /api/v1/auth/token},
 * {@code /api/v1/auth/change-password-required},
 * {@code /api/v1/auth/logout}, {@code /api/v1/auth/logout-all} et le
 * formulaire {@code POST /login}. Ces endpoints exemptaient initialement
 * l'authentification au motif qu'elle "ne modifie aucune donnee metier",
 * mais ils ecrivent bel et bien en base (journal d'audit d'authentification,
 * etat MFA, compteur anti-bruteforce, mot de passe, jetons revoques,
 * horodatage d'invalidation) — {@code pg_restore --single-transaction} rend
 * ces ecritures inoffensives sur le fond (elles restent bloquees derriere
 * les verrous de la transaction de restauration plutot que de corrompre
 * quoi que ce soit), mais un appel qui reste en attente jusqu'a la fin
 * (potentiellement longue) de la restauration, sans jamais avoir ete
 * clairement rejete, est une mauvaise experience et un signal trompeur.
 * Un utilisateur deja connecte (session Web existante ou JWT deja emis)
 * n'est pas affecte : consulter l'application en lecture (GET) continue de
 * fonctionner normalement, seule une NOUVELLE authentification ou une
 * deconnexion doit attendre la fin de la restauration.</p>
 *
 * <p>Positionne dans toutes les chaines de securite qui acceptent des
 * mutations (API JWT, deconnexion API et Web Thymeleaf, voir
 * {@code SecurityConfig}) — la chaine OpenAPI/Swagger est en lecture seule
 * et n'a pas besoin d'etre couverte.</p>
 *
 * <p><strong>Limite connue et acceptee :</strong> ce filtre ne rejette que
 * les NOUVELLES requetes recues apres l'activation du mode maintenance. Une
 * requete de mutation deja acceptee (passee ce filtre) juste avant
 * l'activation continue de s'executer normalement jusqu'a son terme — elle
 * n'est pas interrompue ni "drainee". Un tel handler peut alors ecrire en
 * base pendant que {@code pg_restore --single-transaction} est en cours ; il
 * reste bloque derriere les verrous de la transaction de restauration (pas
 * de corruption), ce qui degrade au pire sa latence. Pour une application
 * mono-instance avec une fenetre de restauration typiquement breve, drainer
 * les requetes en vol (attendre leur fin, ou les annuler proprement, avant
 * de lancer {@code pg_restore}) a ete juge disproportionne au regard du
 * risque residuel — voir aussi le garde-fou dedie et documente pour le cas
 * particulier des scans Nmap ({@code ScanService#scan},
 * {@code ScanException.Reason#RESTORE_IN_PROGRESS}), le seul chemin
 * identifie ou une ecriture tardive apres une longue execution etait
 * silencieuse plutot que simplement ralentie.</p>
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

        if (!gate.isActive() || SAFE_METHODS.contains(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        writeServiceUnavailable(response);
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
