package dev.subnetory.api.v1;

import dev.subnetory.dto.SeriesReservationRequest;
import dev.subnetory.dto.SeriesReservationResponse;
import dev.subnetory.dto.SeriesRowsRequest;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.SeriesReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reservation d'adresses IPv4 en serie avec nommage incremente.
 *
 * <p>Controleur distinct d'{@link AddressController} : la reservation en serie a son
 * propre chemin d'ecriture atomique ({@link SeriesReservationService}), different du
 * bulk-upsert (import, scans) qui traite chaque entree separement.</p>
 */
@RestController
@RequestMapping("/api/v1/addresses/series")
@Tag(name = "Addresses", description = "Recherche, gestion, import et export des adresses IP")
public class SeriesReservationController {

    private final SeriesReservationService seriesReservationService;
    private final AuthAuditService authAuditService;

    public SeriesReservationController(SeriesReservationService seriesReservationService,
                                       AuthAuditService authAuditService) {
        this.seriesReservationService = seriesReservationService;
        this.authAuditService = authAuditService;
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Apercu d'une reservation en serie",
            description = "Rôles requis : ADMIN ou IP. Calcule la serie (adresses et noms "
                    + "d'hote) et signale pour chaque ligne son statut (AVAILABLE, OCCUPIED, "
                    + "RESERVED pour la passerelle, OUT_OF_RANGE) sans rien enregistrer. "
                    + "occupiedMode : block (defaut) ou skip.")
    public SeriesReservationResponse preview(@Valid @RequestBody SeriesReservationRequest request) {
        return seriesReservationService.preview(request);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reserver une serie d'adresses IP",
            description = "Rôles requis : ADMIN ou IP. Cree toute la serie dans une seule "
                    + "transaction (tout ou rien). Repond 409 avec le detail de chaque ligne "
                    + "(propriete preview) si une adresse est deja utilisee, correspond a la "
                    + "passerelle ou sort du sous-reseau : rien n'est cree. Une adresse "
                    + "existante n'est jamais modifiee.")
    public SeriesReservationResponse create(@Valid @RequestBody SeriesReservationRequest request,
                                            Authentication auth) {
        SeriesReservationResponse result = seriesReservationService.create(request, auth.getName());
        audit(auth.getName(), request.subnetId(), result);
        return result;
    }

    @PostMapping("/rows/preview")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @Operation(summary = "Controle d'une liste explicite d'adresses",
            description = "Rôles requis : ADMIN ou IP. Pour une série ajustée ligne par ligne : "
                    + "signale pour chaque ligne AVAILABLE, OCCUPIED, RESERVED (passerelle), "
                    + "OUT_OF_RANGE, INVALID (IPv4 mal formée) ou DUPLICATE, sans rien enregistrer.")
    public SeriesReservationResponse previewRows(@Valid @RequestBody SeriesRowsRequest request) {
        return seriesReservationService.previewRows(request);
    }

    @PostMapping("/rows")
    @PreAuthorize("hasAnyRole('ADMIN', 'IP')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reserver une liste explicite d'adresses IP",
            description = "Rôles requis : ADMIN ou IP. Cree toutes les lignes dans une seule "
                    + "transaction (tout ou rien). Repond 409 avec le detail de chaque ligne "
                    + "(propriete preview) si une ligne n'est pas libre : rien n'est cree. "
                    + "Une adresse existante n'est jamais modifiee.")
    public SeriesReservationResponse createRows(@Valid @RequestBody SeriesRowsRequest request,
                                                Authentication auth) {
        SeriesReservationResponse result = seriesReservationService.createRows(request, auth.getName());
        audit(auth.getName(), request.subnetId(), result);
        return result;
    }

    private void audit(String username, Long subnetId, SeriesReservationResponse result) {
        List<SeriesReservationResponse.Entry> rows = result.rows();
        authAuditService.recordAddressSeriesReserved(username, subnetId, result.network(),
                rows.size(), rows.get(0).ip(), rows.get(rows.size() - 1).ip());
    }
}
