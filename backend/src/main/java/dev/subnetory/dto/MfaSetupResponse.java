package dev.subnetory.dto;

/**
 * Reponse au demarrage d'un enrolement MFA (Sprint 2.37 / F8).
 *
 * <p>{@code secret} est fourni pour la saisie manuelle si le QR code ne peut
 * pas etre scanne ; il n'est pas encore persiste cote serveur tant que
 * {@code POST /api/v1/profile/mfa/enable} n'a pas confirme un premier code.</p>
 */
public record MfaSetupResponse(String secret, String qrCodeDataUri) {}
