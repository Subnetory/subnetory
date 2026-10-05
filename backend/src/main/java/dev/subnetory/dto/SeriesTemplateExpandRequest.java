package dev.subnetory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Application d'un template a un sous-reseau : nombre de noeuds, nom du cluster, IP de
 * depart... {@code gap}, {@code step} et {@code occupiedMode}, s'ils sont absents, suivent
 * les valeurs du template.
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

        @NotBlank(message = "Start IP address is required")
        @Pattern(
            regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
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

        Boolean temporary
) {}
