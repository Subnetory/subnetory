package dev.subnetory.dto;

import dev.subnetory.util.SeriesPlanner;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Demande de reservation d'adresses en serie (apercu ou creation).
 *
 * <p>Les champs optionnels ont des valeurs par defaut : pas 1, compteur de depart 1,
 * compteur sur 2 chiffres, aucun prefixe/suffixe, comportement {@code block}.</p>
 *
 * <p>{@code occupiedMode} : {@code block} (defaut) refuse toute la serie si une
 * adresse est deja utilisee ; {@code skip} saute les adresses utilisees.</p>
 */
public record SeriesReservationRequest(
        @NotNull(message = "Subnet ID is required")
        Long subnetId,

        @NotBlank(message = "Start IP address is required")
        @Pattern(
            regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
            message = "Start address must be a valid IPv4 address (without prefix)"
        )
        String startIp,

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
        String suffix,

        @Pattern(regexp = "^(block|skip)$", message = "occupiedMode must be one of: block, skip")
        String occupiedMode,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        Boolean temporary
) {

    public SeriesReservationRequest {
        if (step == null) step = 1;
        if (startCounter == null) startCounter = 1L;
        if (counterWidth == null) counterWidth = 2;
        if (prefix == null) prefix = "";
        if (suffix == null) suffix = "";
        if (occupiedMode == null) occupiedMode = "block";
        if (temporary == null) temporary = Boolean.FALSE;
    }

    /** Parametres de calcul correspondants (les valeurs absentes sont deja completees). */
    public SeriesPlanner.Spec toSpec() {
        return new SeriesPlanner.Spec(
                startIp,
                count == null ? 0 : count,
                step,
                prefix,
                startCounter,
                counterWidth,
                suffix,
                "skip".equals(occupiedMode)
                        ? SeriesPlanner.OccupiedMode.SKIP
                        : SeriesPlanner.OccupiedMode.BLOCK);
    }
}
