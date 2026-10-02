package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;

public record MfaDisableRequest(
        String currentPassword,
        @NotBlank String code
) {}
