package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;

/** Requete generique portant un unique code MFA (TOTP ou recuperation). */
public record MfaChallengeRequest(
        @NotBlank String code
) {}
