package dev.subnetory.exception;

/**
 * Le compte est authentifie, mais son mot de passe temporaire ou
 * initial doit etre remplace avant tout acces API.
 */
public class PasswordChangeRequiredException extends RuntimeException {

    public PasswordChangeRequiredException() {
        super(
                "The initial password must be changed through the "
                        + "Subnetory web interface before API access is allowed.");
    }
}
