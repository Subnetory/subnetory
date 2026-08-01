package dev.subnetory.dto;

import java.util.List;

/** Codes de recuperation MFA en clair, affiches une seule fois. */
public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {}
