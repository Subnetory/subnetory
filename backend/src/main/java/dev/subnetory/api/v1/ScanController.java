package dev.subnetory.api.v1;

import dev.subnetory.scan.ScanException;
import dev.subnetory.scan.ScanRequest;
import dev.subnetory.scan.ScanResponse;
import dev.subnetory.scan.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;

/**
 * Endpoint de scan réseau à la demande.
 *
 * <p>Le scan est synchrone et limité aux subnets /24 (254 hôtes max).</p>
 */
@RestController
@RequestMapping("/api/v1/subnets")
@Tag(name = "Subnet scan", description = "Scan réseau contrôlé depuis les sous-réseaux")
public class ScanController {

    private static final String ERROR_BASE = "https://subnetory.dev/errors/";

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    /**
     * Lance un scan Nmap sur le subnet identifié.
     *
     * <p>Le body est optionnel. Si absent, les valeurs par défaut s'appliquent :
     * {@code method=nmap, override=false, resolveDns=true, arpPing=true, timing=normal}.</p>
     *
     * <p>Codes HTTP retournés :</p>
     * <ul>
     *   <li>200 — scan terminé avec succès</li>
     *   <li>400 — subnet trop grand (> /24)</li>
     *   <li>400 — options de scan invalides</li>
     *   <li>401 — non authentifié</li>
     *   <li>403 — rôle insuffisant</li>
     *   <li>404 — subnet inexistant</li>
     *   <li>408 — délai d'exécution dépassé</li>
     *   <li>503 — nmap non installé ou inaccessible</li>
     * </ul>
     */
    @PostMapping("/{id}/scan")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @Operation(summary = "Lancer un scan de sous-réseau",
            description = "Exécute un scan Nmap contrôlé. Les options exposées par l'interface graphique sont disponibles dans le body API : résolution DNS, DNS dédiés, ARP, rythme et écrasement des données existantes.")
    public ResponseEntity<?> scanSubnet(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ScanRequest request,
            Authentication auth) {

        // Body optionnel : utiliser les valeurs par défaut si absent
        if (request == null) {
            request = new ScanRequest("nmap", false);
        }

        try {
            ScanResponse response = scanService.scan(id, request, auth.getName());
            return ResponseEntity.ok(response);
        } catch (ScanException e) {
            return ResponseEntity
                    .status(httpStatusFor(e.getReason()))
                    .body(problemDetailFor(e));
        }
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private static int httpStatusFor(ScanException.Reason reason) {
        return switch (reason) {
            case SUBNET_TOO_LARGE -> HttpStatus.BAD_REQUEST.value();
            case INVALID_OPTIONS -> HttpStatus.BAD_REQUEST.value();
            case TIMEOUT -> HttpStatus.REQUEST_TIMEOUT.value();
            case TOOL_NOT_AVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE.value();
            case EXECUTION_FAILED, PARSE_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR.value();
        };
    }

    private static ProblemDetail problemDetailFor(ScanException e) {
        HttpStatus status = HttpStatus.resolve(httpStatusFor(e.getReason()));
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        pd.setType(URI.create(ERROR_BASE + "scan-" + e.getReason().name().toLowerCase().replace('_', '-')));
        pd.setTitle(switch (e.getReason()) {
            case SUBNET_TOO_LARGE    -> "Subnet Too Large";
            case INVALID_OPTIONS     -> "Invalid Scan Options";
            case TIMEOUT             -> "Scan Timeout";
            case TOOL_NOT_AVAILABLE  -> "Scan Tool Not Available";
            case EXECUTION_FAILED    -> "Scan Execution Failed";
            case PARSE_ERROR         -> "Scan Output Parse Error";
        });
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
