package dev.subnetory.api.v1;

import dev.subnetory.dto.NetworkContextRequest;
import dev.subnetory.dto.NetworkContextResponse;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.NetworkContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/contexts")
@Tag(name = "Contexts", description = "Clients, environnements et périmètres réseau")
public class NetworkContextController {

    private final NetworkContextService contextService;
    private final AuthAuditService authAuditService;

    public NetworkContextController(NetworkContextService contextService, AuthAuditService authAuditService) {
        this.contextService = contextService;
        this.authAuditService = authAuditService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lister les contextes autorisés",
            description = "Retourne uniquement les contextes du périmètre de l'utilisateur. "
                    + "Un administrateur voit tous les contextes. Tout rôle authentifié.")
    public Page<NetworkContextResponse> listContexts(
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return contextService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter un contexte",
            description = "Répond 404 si le contexte n'existe pas ou n'est pas dans le "
                    + "périmètre autorisé de l'utilisateur. Tout rôle authentifié.")
    public ResponseEntity<NetworkContextResponse> getContext(@PathVariable Long id) {
        return ResponseEntity.ok(contextService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un contexte",
            description = "Rôle requis : ADMIN.")
    public NetworkContextResponse createContext(@Valid @RequestBody NetworkContextRequest request, Authentication auth) {
        NetworkContextResponse created = contextService.create(request);
        authAuditService.recordContextCreated(auth.getName(), created.id(), created.name());
        return created;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Modifier un contexte",
            description = "Rôle requis : ADMIN.")
    public ResponseEntity<NetworkContextResponse> updateContext(
            @PathVariable Long id,
            @Valid @RequestBody NetworkContextRequest request) {
        return ResponseEntity.ok(contextService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un contexte",
            description = "Rôle requis : ADMIN. Refusé si le contexte contient encore "
                    + "des sites, sous-réseaux ou adresses (409).")
    public void deleteContext(@PathVariable Long id, Authentication auth) {
        NetworkContextResponse context = contextService.findById(id);
        contextService.delete(id);
        authAuditService.recordContextDeleted(auth.getName(), id, context.name());
    }
}
