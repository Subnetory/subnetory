package dev.subnetory.api.v1;

import dev.subnetory.dto.SeriesTemplateDuplicateRequest;
import dev.subnetory.dto.SeriesTemplateExpandRequest;
import dev.subnetory.dto.SeriesTemplateExpansionResponse;
import dev.subnetory.dto.SeriesTemplateRequest;
import dev.subnetory.dto.SeriesTemplateResponse;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.SeriesTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Templates de reservation en serie (preparation d'un cluster ou d'une infrastructure) :
 * presets livres, globaux et en lecture seule, et templates personnalises par contexte.
 */
@RestController
@RequestMapping("/api/v1/series-templates")
@Tag(name = "Series templates",
        description = "Templates de reservation en serie : presets et templates par contexte")
public class SeriesTemplateController {

    private final SeriesTemplateService service;
    private final AuthAuditService authAuditService;

    public SeriesTemplateController(SeriesTemplateService service, AuthAuditService authAuditService) {
        this.service = service;
        this.authAuditService = authAuditService;
    }

    @GetMapping
    @Operation(summary = "Lister les templates",
            description = "Presets livres (key builtin:<cle>, lecture seule) puis templates "
                    + "personnalises (key custom:<id>). contextId (optionnel) restreint les "
                    + "templates personnalises a ce contexte ; sinon tous les contextes autorises.")
    public List<SeriesTemplateResponse> list(@RequestParam(required = false) Long contextId) {
        return service.list(contextId);
    }

    @GetMapping("/{key}")
    @Operation(summary = "Detail d'un template",
            description = "key : builtin:<cle> (preset) ou custom:<id> (template personnalise).")
    public SeriesTemplateResponse get(@PathVariable String key) {
        return service.get(key);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Creer un template personnalise",
            description = "Rôles requis : ADMIN ou IP. Le nom est unique (casse ignoree) dans le "
                    + "contexte. 400 si la definition est invalide (format de nom, variables), "
                    + "409 si le nom existe deja.")
    public SeriesTemplateResponse create(@Valid @RequestBody SeriesTemplateRequest request,
                                         Authentication auth) {
        SeriesTemplateResponse created = service.create(request);
        authAuditService.recordSeriesTemplateCreated(auth.getName(), created.id(),
                created.contextId(), created.name());
        return created;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Modifier un template personnalise",
            description = "Rôles requis : ADMIN ou IP. Les presets sont en lecture seule "
                    + "(les dupliquer d'abord). Le contexte d'un template ne peut pas changer.")
    public SeriesTemplateResponse update(@PathVariable Long id,
                                         @Valid @RequestBody SeriesTemplateRequest request,
                                         Authentication auth) {
        SeriesTemplateResponse updated = service.update(id, request);
        authAuditService.recordSeriesTemplateUpdated(auth.getName(), updated.id(),
                updated.contextId(), updated.name());
        return updated;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Supprimer un template personnalise",
            description = "Rôles requis : ADMIN ou IP. Les presets ne peuvent pas etre supprimes.")
    public void delete(@PathVariable Long id, Authentication auth) {
        SeriesTemplateResponse deleted = service.delete(id);
        authAuditService.recordSeriesTemplateDeleted(auth.getName(), deleted.id(),
                deleted.contextId(), deleted.name());
    }

    @PostMapping("/{key}/duplicate")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Dupliquer un template",
            description = "Rôles requis : ADMIN ou IP. Copie un preset (builtin:<cle>) ou un "
                    + "template personnalise (custom:<id>) en template personnalise du contexte "
                    + "choisi, sous un nouveau nom.")
    public SeriesTemplateResponse duplicate(@PathVariable String key,
                                            @Valid @RequestBody SeriesTemplateDuplicateRequest request,
                                            Authentication auth) {
        SeriesTemplateResponse created = service.duplicate(key, request);
        authAuditService.recordSeriesTemplateCreated(auth.getName(), created.id(),
                created.contextId(), created.name());
        return created;
    }

    @PostMapping("/expand")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Appliquer un template a un sous-reseau (apercu)",
            description = "Rôles requis : ADMIN ou IP. Calcule, a partir du template, du nombre "
                    + "de noeuds, du nom de cluster et de l'IP de depart, la demande de series "
                    + "enchainees (propriete request, reutilisable telle quelle sur "
                    + "/addresses/series/groups) et son apercu (propriete preview), sans rien "
                    + "enregistrer. gap, step et occupiedMode, absents, suivent le template.")
    public SeriesTemplateExpansionResponse expand(@Valid @RequestBody SeriesTemplateExpandRequest request) {
        return service.expand(request);
    }
}
