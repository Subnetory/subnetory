package dev.subnetory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Application d'un template a un sous-reseau : nombre de noeuds, nom du cluster, IP de
 * depart... {@code gap}, {@code step} et {@code occupiedMode}, s'ils sont absents, suivent
 * les valeurs du template. {@code roles} (template {@code per-group}) donne le sous-reseau et
 * l'IP de depart de chaque role ; {@code startIp} y est facultatif.
 */
public record SeriesTemplateExpandRequest(
        @NotNull(message = "Subnet ID is required")
        Long subnetId,

        @NotBlank(message = "Template key is required")
        @Size(max = 140, message = "Template key is too long")
        String key,

        @Min(value = 1, message = "Nodes must be at least 1")
        @Max(value = 256, message = "Nodes must be at most 256")
        Integer nodes,

        @Size(max = 40, message = "Cluster must be at most 40 characters")
        String cluster,

        @Size(max = 40, message = "Site must be at most 40 characters")
        String site,

        @Pattern(
            regexp = "^$|^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
            message = "Start address must be a valid IPv4 address (without prefix)"
        )
        String startIp,

        @Min(value = 0, message = "Gap must be at least 0")
        @Max(value = 256, message = "Gap must be at most 256")
        Integer gap,

        @Min(value = 1, message = "Step must be at least 1")
        @Max(value = 256, message = "Step must be at most 256")
        Integer step,

        @Pattern(regexp = "^(block|skip)$", message = "occupiedMode must be one of: block, skip")
        String occupiedMode,

        @Size(max = 500, message = "Description must be at most 500 characters")
        String description,

        Boolean temporary,

        @Size(max = 10, message = "At most 10 role targets are allowed")
        List<@Valid @NotNull(message = "Role target must not be null") RoleTarget> roles
) {

    /** Demande sans cible par role. */
    public SeriesTemplateExpandRequest(Long subnetId, String key, Integer nodes, String cluster,
                                       String site, String startIp, Integer gap, Integer step,
                                       String occupiedMode, String description, Boolean temporary) {
        this(subnetId, key, nodes, cluster, site, startIp, gap, step, occupiedMode, description,
                temporary, null);
    }

    /**
     * Destination d'un role (template {@code per-group}) : son sous-reseau et son IP de depart.
     *
     * @param role     role du groupe tel que defini dans le template (HOST, IPMI...)
     * @param subnetId sous-reseau du role (celui de la demande s'il est absent)
     * @param startIp  IP de depart ; absente, le groupe suit le groupe precedent du meme
     *                 sous-reseau ou prend le premier bloc libre du sous-reseau
     */
    public record RoleTarget(
            @NotBlank(message = "Role is required")
            @Size(max = 30, message = "Role must be at most 30 characters")
            String role,

            Long subnetId,

            @Pattern(
                regexp = "^$|^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
                message = "Role start address must be a valid IPv4 address (without prefix)"
            )
            String startIp
    ) {
        public RoleTarget {
            if (startIp != null && startIp.isBlank()) startIp = null;
        }
    }
}
