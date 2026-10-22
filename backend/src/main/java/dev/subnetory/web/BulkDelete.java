package dev.subnetory.web;

import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Aides communes a la suppression en masse depuis les listes Web
 * (adresses, sous-reseaux, VLAN, sites).
 */
final class BulkDelete {

    /** Plafond de securite par requete. */
    static final int MAX = 500;

    private BulkDelete() {}

    /** Vrai si l'utilisateur courant possede l'un des roles (ex. {@code ROLE_ADMIN}). */
    static boolean hasAnyRole(Authentication auth, String... roles) {
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> List.of(roles).contains(a.getAuthority()));
    }

    /**
     * Identifiants distincts selectionnes, ou {@code null} (avec message flash d'erreur)
     * si la selection est vide ou depasse {@link #MAX}.
     */
    static Set<Long> select(List<Long> ids, RedirectAttributes flash,
                            MessageSource messages, Locale locale) {
        if (ids == null || ids.isEmpty()) {
            flash.addFlashAttribute("flashError",
                    messages.getMessage("flash.bulkDelete.none", null, locale));
            return null;
        }
        Set<Long> distinct = new LinkedHashSet<>(ids);
        if (distinct.size() > MAX) {
            flash.addFlashAttribute("flashError",
                    messages.getMessage("flash.bulkDelete.tooMany", new Object[] {MAX}, locale));
            return null;
        }
        return distinct;
    }

    /** Message flash de resultat : succes complet, ou partiel si certains elements n'ont pas pu etre supprimes. */
    static void flashResult(int deleted, int failed, RedirectAttributes flash,
                            MessageSource messages, Locale locale) {
        if (failed > 0) {
            flash.addFlashAttribute("flashError",
                    messages.getMessage("flash.bulkDelete.partial", new Object[] {deleted, failed}, locale));
        } else {
            flash.addFlashAttribute("flashSuccess",
                    messages.getMessage("flash.bulkDelete.success", new Object[] {deleted}, locale));
        }
    }
}
