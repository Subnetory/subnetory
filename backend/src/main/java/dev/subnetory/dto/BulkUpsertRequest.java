package dev.subnetory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * DTO pour POST /api/v1/addresses/bulk-upsert.
 *
 * <p>Si {@code override=false} (défaut) : les entrées existantes ne sont pas modifiées
 * (seul {@code last_seen_at} est mis à jour).</p>
 * <p>Si {@code override=true} : les champs fournis écrasent les valeurs existantes.</p>
 */
public record BulkUpsertRequest(
        @NotEmpty(message = "Addresses list must not be empty")
        @Valid
        List<BulkUpsertEntry> addresses,

        boolean override
) {
    public record BulkUpsertEntry(
            @NotNull(message = "IP address is required")
            @Pattern(
                regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
                message = "Address must be a valid IPv4 address"
            )
            String address,

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
}
