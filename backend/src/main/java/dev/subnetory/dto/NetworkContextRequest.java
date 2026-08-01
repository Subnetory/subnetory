package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NetworkContextRequest(
        @NotBlank(message = "Context name is required")
        @Size(max = 100)
        String name,

        @Size(max = 500)
        String description
) {}
