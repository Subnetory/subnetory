package dev.subnetory.api.v1;

import dev.subnetory.dto.SiteRequest;
import dev.subnetory.dto.SiteResponse;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.SiteService;
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
@RequestMapping("/api/v1/sites")
@Tag(name = "Sites", description = "Sites rattachés aux contextes réseau")
public class SiteController {

    private final SiteService siteService;
    private final AuthAuditService authAuditService;

    public SiteController(SiteService siteService, AuthAuditService authAuditService) {
        this.siteService = siteService;
        this.authAuditService = authAuditService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lister les sites",
            description = "Limité aux contextes autorisés de l'utilisateur. Le paramètre "
                    + "contextId filtre sur un contexte du périmètre. Tout rôle authentifié.")
    public Page<SiteResponse> listSites(
            @RequestParam(required = false) Long contextId,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        if (contextId != null) {
            return siteService.findByContext(contextId, pageable);
        }
        return siteService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter un site",
            description = "Répond 404 si le site est hors du périmètre de contextes autorisés.")
    public ResponseEntity<SiteResponse> getSite(@PathVariable Long id) {
        return ResponseEntity.ok(siteService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un site",
            description = "Rôles requis : ADMIN ou NETWORK. Le contexte cible doit être "
                    + "dans le périmètre autorisé.")
    public SiteResponse createSite(@Valid @RequestBody SiteRequest request, Authentication auth) {
        SiteResponse created = siteService.create(request);
        authAuditService.recordSiteCreated(auth.getName(), created.id(), created.name());
        return created;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @Operation(summary = "Modifier un site",
            description = "Rôles requis : ADMIN ou NETWORK.")
    public ResponseEntity<SiteResponse> updateSite(
            @PathVariable Long id,
            @Valid @RequestBody SiteRequest request) {
        return ResponseEntity.ok(siteService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un site",
            description = "Rôle requis : ADMIN. Refusé si le site contient encore des "
                    + "VLAN ou sous-réseaux (409).")
    public void deleteSite(@PathVariable Long id, Authentication auth) {
        SiteResponse site = siteService.findById(id);
        siteService.delete(id);
        authAuditService.recordSiteDeleted(auth.getName(), id, site.name());
    }
}
