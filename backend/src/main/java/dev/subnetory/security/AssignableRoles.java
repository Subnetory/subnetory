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
