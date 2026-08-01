package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @NotBlank(message = "IP address is required")
        @Pattern(
            regexp = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$",
            message = "Address must be a valid IPv4 address (without prefix)"
        )
        String address,

        @Pattern(
            regexp = "^$|^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$",
            message = "MAC address must be in format aa:bb:cc:dd:ee:ff"
        )
        String mac,

        @Size(max = 100)
        String hostname,

        @Size(max = 500)
        String description,

        @NotNull(message = "Subnet ID is required")
        Long subnetId,

        boolean temporary,

        /**
         * Source de découverte. Valeurs acceptées : manual, api, csv, nmap, arp-scan, dns.
         * Si absent, la valeur "manual" est utilisée par défaut.
         */
        @Pattern(
            regexp = "^(manual|api|csv|nmap|arp-scan|dns)$",
            message = "discovery_source must be one of: manual, api, csv, nmap, arp-scan, dns"
        )
        String discoverySource
) {}
