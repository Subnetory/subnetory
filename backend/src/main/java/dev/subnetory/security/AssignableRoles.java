package dev.subnetory.security;

import dev.subnetory.domain.Role;

import java.util.List;
import java.util.Set;

public final class AssignableRoles {

    public static final String ADMIN = "ROLE_ADMIN";
    public static final String READ_ONLY = "ROLE_READ_ONLY";
    public static final String NETWORK = "ROLE_NETWORK";
    public static final String IP = "ROLE_IP";
    /**
     * Acces limite aux operations de sauvegarde (audit 01/08/2026, cf.
     * backend/docs/DB_PASSWORD_ROTATION_FEASIBILITY.md) : sans le reste de
     * l'administration (comptes, LDAP, journal d'audit), reserve a ADMIN.
     *
     * <p>Correctif securite ELEVEE (audit 04/08/2026) : import et
     * restauration ({@code AdminBackupController}/{@code AdminBackupWebController})
     * sont desormais reserves a {@link #ADMIN} malgre la regle d'URL/classe
     * {@code hasAnyRole('ADMIN', 'BACKUP')} — un compte {@code ROLE_BACKUP}
     * seul pouvait sinon importer puis restaurer un dump arbitraire,
     * equivalent de fait a un acces administrateur complet des donnees.</p>
     *
     * <p>Correctif securite MOYENNE (second audit externe, 04/08/2026) :
     * la modification de la configuration de sauvegarde, la purge en masse
     * et la suppression individuelle de l'historique sont ELLES AUSSI
     * desormais reservees a {@link #ADMIN} — un compte {@code ROLE_BACKUP}
     * compromis pouvait sinon desactiver la sauvegarde planifiee ou detruire
     * l'integralite de l'historique et des fichiers {@code .dump} sur
     * disque, sabotant silencieusement la capacite de reprise apres
     * sinistre. {@code ROLE_BACKUP} ne conserve que le strict necessaire
     * pour un compte de service ou un operateur dedie : lecture de la
     * configuration, declenchement manuel, consultation de l'historique et
     * telechargement (ce javadoc affirmait encore le perimetre precedent,
     * plus large — corrige au troisieme audit externe, constat F-01,
     * 04/08/2026, apres que l'IHM et {@code API_FIRST_PARITY.md} aient ete
     * releves comme egalement en retard sur le meme point).</p>
     */
    public static final String BACKUP = "ROLE_BACKUP";

    private static final List<String> ORDERED_NAMES = List.of(ADMIN, READ_ONLY, NETWORK, IP, BACKUP);
    private static final Set<String> NAMES = Set.of(ADMIN, READ_ONLY, NETWORK, IP, BACKUP);

    private AssignableRoles() {
    }

    public static boolean contains(String roleName) {
        return NAMES.contains(roleName);
    }

    public static List<String> orderedNames() {
        return ORDERED_NAMES;
    }

    public static List<Role> filter(List<Role> roles) {
        return roles.stream()
                .filter(role -> contains(role.getName()))
                .sorted(java.util.Comparator.comparingInt(role -> ORDERED_NAMES.indexOf(role.getName())))
                .toList();
    }
}
