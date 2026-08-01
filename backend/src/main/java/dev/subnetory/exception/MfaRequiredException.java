package dev.subnetory.exception;

/**
 * Le compte authentifie (identifiants corrects) a le MFA active mais aucun
 * code TOTP/de recuperation n'a ete fourni.
 *
 * <p>Sprint 2.37 / Lot 3 : reponse 401 dediee ({@code MFA_REQUIRED}) sur
 * {@code POST /api/v1/auth/token}, distincte de {@link InvalidMfaCodeException}
 * (auto-service, 400) et de {@link MfaChallengeFailedException} (code fourni
 * mais invalide, 401).</p>
 */
public class MfaRequiredException extends RuntimeException {

    public MfaRequiredException() {
        super("MFA code required to complete authentication.");
    }

    public MfaRequiredException(String message) {
        super(message);
    }
}
