package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;

public record MfaEnableRequest(
        @NotBlank String secret,
        @NotBlank String code
) {}
