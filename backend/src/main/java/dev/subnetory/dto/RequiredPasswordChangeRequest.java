package dev.subnetory.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Demande de remplacement d'un mot de passe temporaire via l'API,
 * sans JWT prealable. Utilisee par les comptes locaux dont le premier
 * changement de mot de passe est obligatoire (mustChangePassword).
 */
public record RequiredPasswordChangeRequest(
        @NotBlank String username,
        @NotBlank String currentPassword,
        @NotBlank String newPassword
) {}
