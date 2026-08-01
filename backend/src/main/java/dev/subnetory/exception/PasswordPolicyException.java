package dev.subnetory.exception;

/**
 * Exception levee lorsqu'un mot de passe ne respecte pas la politique de Subnetory.
 *
 * <p>Le message est destine a l'utilisateur final : il doit etre clair,
 * sans jargon technique, et indiquer precisement la regle violee.</p>
 *
 * <p>Utilisee par {@code PasswordPolicyService} et propagee vers les controllers
 * Web (affichage dans le formulaire) et l'API REST (400 Bad Request).</p>
 */
public class PasswordPolicyException extends RuntimeException {

    public PasswordPolicyException(String message) {
        super(message);
    }
}
