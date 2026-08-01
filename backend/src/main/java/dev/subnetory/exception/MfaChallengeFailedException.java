package dev.subnetory.exception;

/**
 * Le compte authentifie (identifiants corrects) a le MFA active et un code a
 * ete fourni, mais il est invalide (TOTP incorrect/expire ou code de
 * recuperation deja utilise/inconnu).
 *
 * <p>Sprint 2.37 / Lot 3 : reponse 401 dediee ({@code MFA_INVALID}) sur
 * {@code POST /api/v1/auth/token}. Voir {@link MfaRequiredException} pour le
 * cas ou aucun code n'est fourni.</p>
 */
public class MfaChallengeFailedException extends RuntimeException {

    public MfaChallengeFailedException() {
        super("Invalid MFA code.");
    }

    public MfaChallengeFailedException(String message) {
        super(message);
    }
}
