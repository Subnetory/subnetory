package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;

public record MfaDisableRequest(
        @NotBlank String currentPassword,
        @NotBlank String code
) {}
