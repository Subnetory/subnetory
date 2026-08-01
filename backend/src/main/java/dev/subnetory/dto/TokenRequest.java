package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code totpCode} est optionnel (Sprint 2.37 / Lot 3) : requis uniquement
 * si le compte a le MFA active, code TOTP ou de recuperation. Le constructeur
 * a deux arguments est conserve pour la compatibilite avec les appels
 * existants (comptes sans MFA).
 */
public record TokenRequest(
    @NotBlank String username,
    @NotBlank String password,
    String totpCode
) {
    public TokenRequest(String username, String password) {
        this(username, password, null);
    }
}
