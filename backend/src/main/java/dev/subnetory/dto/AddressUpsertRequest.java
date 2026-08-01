package dev.subnetory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO pour PUT /api/v1/addresses/by-ip/{ip} (upsert par IP).
 *
 * <p>{@code subnetId} est obligatoire pour la création.
 * Sur une IP existante, seul {@code last_seen_at} est mis à jour
 * sauf si {@code override=true}.</p>
 */
public record AddressUpsertRequest(
        @NotNull(message = "Subnet ID is required")
        Long subnetId,

        @Pattern(
            regexp = "^$|^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$",
            message = "MAC address must be in format aa:bb:cc:dd:ee:ff"
        )
        String mac,

        @Size(max = 100)
        String hostname,

        @Size(max = 500)
        String description,

        boolean temporary,

        @Pattern(
            regexp = "^(manual|api|csv|nmap|arp-scan|dns)$",
            message = "discovery_source must be one of: manual, api, csv, nmap, arp-scan, dns"
        )
        String discoverySource
) {}
