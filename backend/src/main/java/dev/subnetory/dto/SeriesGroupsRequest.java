package dev.subnetory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Reservation de plusieurs series enchainees (apercu ou creation), par exemple
 * les adresses d'un cluster : tous les hotes, puis toutes les CVM, puis tous les IPMI.
 *
 * <p>Les groupes sont places l'un apres l'autre a partir de {@code startIp} : chaque
 * groupe demarre juste apres la derniere adresse du precedent, plus {@code gap}
 * adresses laissees libres. {@code occupiedMode} ({@code block} par defaut ou
 * {@code skip}) s'applique a tous les groupes. Tout est cree dans une seule
 * transaction.</p>
 */
public record SeriesGroupsRequest(
        @NotNull(message = "Subnet ID is required")
        Long subnetId,

        @NotBlank(message = "Start IP address is required")
        @Pattern(
            regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
            message = "Start address must be a valid IPv4 address (without prefix)"
        )
        String startIp,

        @Min(value = 0, message = "Gap must be at least 0")
        @Max(value = 256, message = "Gap must be at most 256")
        Integer gap,

        @Pattern(regexp = "^(block|skip)$", message = "occupiedMode must be one of: block, skip")
        String occupiedMode,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        Boolean temporary,

        @NotEmpty(message = "At least one group is required")
        @Size(max = 10, message = "At most 10 groups are allowed")
        List<@Valid @NotNull(message = "Group must not be null") Group> groups
) {

    public SeriesGroupsRequest {
        if (gap == null) gap = 0;
        if (occupiedMode == null) occupiedMode = "block";
        if (temporary == null) temporary = Boolean.FALSE;
    }

    /**
     * Un groupe d'adresses a noms incrementes.
     *
     * @param label        libelle libre affiche dans l'apercu (ex. HOST, CVM, IPMI)
     * @param count        nombre d'adresses du groupe
     * @param step         ecart entre deux adresses du groupe (1 par defaut)
     * @param prefix       debut du nom
     * @param startCounter premiere valeur du compteur (1 par defaut)
     * @param counterWidth nombre de chiffres du compteur (2 par defaut)
     * @param suffix       fin du nom
     */
    public record Group(
            @Size(max = 50, message = "Label must be at most 50 characters")
            String label,

            @NotNull(message = "Count is required")
            @Min(value = 1, message = "Count must be at least 1")
            @Max(value = 256, message = "Count must be at most 256")
            Integer count,

            @Min(value = 1, message = "Step must be at least 1")
            @Max(value = 256, message = "Step must be at most 256")
            Integer step,

            @Size(max = 90, message = "Prefix must be at most 90 characters")
            String prefix,

            @Min(value = 0, message = "Start counter must be at least 0")
            @Max(value = 999_999_999L, message = "Start counter must be at most 999999999")
            Long startCounter,

            @Min(value = 1, message = "Counter width must be at least 1")
            @Max(value = 9, message = "Counter width must be at most 9")
            Integer counterWidth,

            @Size(max = 90, message = "Suffix must be at most 90 characters")
            String suffix
    ) {
        public Group {
            if (step == null) step = 1;
            if (startCounter == null) startCounter = 1L;
            if (counterWidth == null) counterWidth = 2;
            if (prefix == null) prefix = "";
            if (suffix == null) suffix = "";
        }
    }
}
