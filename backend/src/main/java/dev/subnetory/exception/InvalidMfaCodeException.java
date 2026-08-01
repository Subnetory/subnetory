package dev.subnetory.exception;

/**
 * Le code MFA (TOTP ou code de recuperation) fourni est invalide ou absent.
 *
 * <p>Utilisee par les actions self-service deja authentifiees (activation,
 * desactivation, regeneration des codes de recuperation) : 400, l'utilisateur
 * est deja connu, il s'agit d'une validation d'entree. Pour le defi MFA au
 * login (Web et API), voir {@link MfaRequiredException} et
 * {@link MfaChallengeFailedException} (401, coherent avec la politique
 * anti-enumeration du login).</p>
 */
public class InvalidMfaCodeException extends RuntimeException {

    public InvalidMfaCodeException() {
        super("Code MFA invalide.");
    }

    public InvalidMfaCodeException(String message) {
        super(message);
    }
}
