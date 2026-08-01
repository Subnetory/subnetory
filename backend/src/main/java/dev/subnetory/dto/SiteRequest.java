package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SiteRequest(
        @NotBlank(message = "Site name is required")
        @Size(max = 100)
        String name,

        @NotBlank(message = "Site code is required")
        @Size(max = 20)
        @Pattern(regexp = "^[A-Z0-9_-]+$", message = "Code must be uppercase letters, digits, _ or -")
        String code,

        @NotNull(message = "Context ID is required")
        Long contextId
) {}
