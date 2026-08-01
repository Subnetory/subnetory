package dev.subnetory.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VlanRequest(
        @Size(max = 100)
        String name,

        @NotNull(message = "VLAN ID is required")
        @Min(value = 0, message = "VLAN ID must be between 0 and 4094")
        @Max(value = 4094, message = "VLAN ID must be between 0 and 4094")
        Integer vid,

        @NotNull(message = "Site ID is required")
        Long siteId
) {}
