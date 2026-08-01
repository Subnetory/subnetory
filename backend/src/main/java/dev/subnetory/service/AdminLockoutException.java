package dev.subnetory.service;

/**
 * Levée par UserAdminService quand une opération violerait une règle anti-lockout.
 *
 * Règles protégées :
 *  - impossible de désactiver son propre compte ;
 *  - impossible de désactiver le dernier ADMIN actif ;
 *  - impossible de retirer ROLE_ADMIN au dernier ADMIN actif ;
 *  - impossible de sauvegarder un utilisateur sans aucun rôle.
 *
 * Le message est utilisé directement comme flashError côté GUI — il doit être
 * lisible par un utilisateur final, sans fuite technique.
 */
public class AdminLockoutException extends RuntimeException {

    public AdminLockoutException(String message) {
        super(message);
    }
}
