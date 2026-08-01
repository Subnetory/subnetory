package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SubnetRequest(
        /**
         * Réseau CIDR, ex: "192.168.1.0/24".
         * Le type PostgreSQL `cidr` normalise automatiquement l'adresse réseau.
         */
        @NotBlank(message = "Network CIDR is required")
        @Pattern(
            regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)/(\\d|[12]\\d|3[012])$",
            message = "Network must be a valid IPv4 CIDR (e.g. 192.168.1.0/24)"
        )
        String network,

        @Size(max = 500)
        String description,

        /** Passerelle optionnelle, ex: "192.168.1.1". */
        @Pattern(
            regexp = "^$|^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
            message = "Gateway must be a valid IPv4 address"
        )
        String gateway,

        @NotNull(message = "Context ID is required")
        Long contextId,

        @NotNull(message = "Site ID is required")
        Long siteId,

        Long vlanId,
        Long parentId
) {}
