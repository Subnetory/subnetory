package dev.subnetory.api.v1;

import dev.subnetory.dto.VlanRequest;
import dev.subnetory.dto.VlanResponse;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.VlanService;
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
@RequestMapping("/api/v1/vlans")
@Tag(name = "VLAN", description = "Segments VLAN et rattachement aux sites")
public class VlanController {

    private final VlanService vlanService;
    private final AuthAuditService authAuditService;

    public VlanController(VlanService vlanService, AuthAuditService authAuditService) {
        this.vlanService = vlanService;
        this.authAuditService = authAuditService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Lister les VLAN",
            description = "Limité aux contextes autorisés via le site porteur. Le paramètre "
                    + "siteId filtre sur un site du périmètre. Tout rôle authentifié.")
    public Page<VlanResponse> listVlans(
            @RequestParam(required = false) Long siteId,
            @PageableDefault(size = 20, sort = "vid") Pageable pageable) {
        if (siteId != null) {
            return vlanService.findBySite(siteId, pageable);
        }
        return vlanService.findAll(pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Consulter un VLAN",
            description = "Répond 404 si le VLAN est hors du périmètre de contextes autorisés.")
    public ResponseEntity<VlanResponse> getVlan(@PathVariable Long id) {
        return ResponseEntity.ok(vlanService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Créer un VLAN",
            description = "Rôles requis : ADMIN ou NETWORK. L'identifiant VLAN (vid) doit "
                    + "être unique sur le site (0-4094).")
    public VlanResponse createVlan(@Valid @RequestBody VlanRequest request, Authentication auth) {
        VlanResponse created = vlanService.create(request);
        authAuditService.recordVlanCreated(auth.getName(), created.id(),
                "VLAN " + created.vid() + " (" + created.name() + ")");
        return created;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'NETWORK')")
    @Operation(summary = "Modifier un VLAN",
            description = "Rôles requis : ADMIN ou NETWORK. Changement de site refusé si le "
                    + "VLAN a encore des sous-réseaux (409).")
    public ResponseEntity<VlanResponse> updateVlan(
            @PathVariable Long id,
            @Valid @RequestBody VlanRequest request) {
        return ResponseEntity.ok(vlanService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un VLAN",
            description = "Rôle requis : ADMIN. Refusé si des sous-réseaux y sont "
                    + "encore rattachés (409).")
    public void deleteVlan(@PathVariable Long id, Authentication auth) {
        VlanResponse vlan = vlanService.findById(id);
        vlanService.delete(id);
        authAuditService.recordVlanDeleted(auth.getName(), id,
                "VLAN " + vlan.vid() + " (" + vlan.name() + ")");
    }
}
