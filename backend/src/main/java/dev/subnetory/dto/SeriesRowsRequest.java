package dev.subnetory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Reservation atomique d'une liste explicite d'adresses (apercu ou creation).
 *
 * <p>Utilisee quand l'utilisateur a ajuste les lignes d'une serie calculee
 * (adresse ou nom modifie ligne par ligne) : chaque ligne porte son adresse, son
 * nom et ses attributs. Toutes les lignes sont creees ensemble ou aucune.</p>
 */
public record SeriesRowsRequest(
        @NotNull(message = "Subnet ID is required")
        Long subnetId,

        @NotEmpty(message = "At least one row is required")
        @Size(max = 256, message = "At most 256 rows are allowed")
        List<@Valid @NotNull(message = "Row must not be null") Row> rows
) {

    /**
     * Une ligne a reserver.
     *
     * @param ip          adresse IPv4 (sans prefixe)
     * @param hostname    nom d'hote (optionnel)
     * @param description description (optionnelle)
     * @param mac         adresse MAC (optionnelle)
     * @param temporary   marque l'adresse comme temporaire (faux par defaut)
     */
    public record Row(
            @NotBlank(message = "IP address is required")
            String ip,

            @Size(max = 100, message = "Hostname must be at most 100 characters")
            String hostname,

            @Size(max = 500, message = "Description must be at most 500 characters")
            String description,

            @Pattern(
                regexp = "^$|^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$",
                message = "MAC address must look like aa:bb:cc:dd:ee:ff"
            )
            String mac,

            Boolean temporary
    ) {}
}
