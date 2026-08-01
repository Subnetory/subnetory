package dev.subnetory.security;

import dev.subnetory.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pour l'ajout de ROLE_BACKUP (audit 01/08/2026, suite a l'etude
 * de faisabilite backend/docs/DB_PASSWORD_ROTATION_FEASIBILITY.md) : verrou
 * explicite sur la liste des roles assignables, pour que tout ajout futur
 * soit conscient plutot qu'accidentel.
 */
class AssignableRolesTest {

    @Test
    void orderedNames_containsExactlyTheFiveAssignableRoles() {
        assertThat(AssignableRoles.orderedNames())
                .containsExactly("ROLE_ADMIN", "ROLE_READ_ONLY", "ROLE_NETWORK", "ROLE_IP", "ROLE_BACKUP");
    }

    @Test
    void contains_backupRole_isTrue() {
        assertThat(AssignableRoles.contains("ROLE_BACKUP")).isTrue();
    }

    @Test
    void contains_unknownRole_isFalse() {
        // ROLE_STOCK etc. existent en base (fork Adrezo) mais ne sont pas
        // assignables via l'IHM/API Subnetory.
        assertThat(AssignableRoles.contains("ROLE_STOCK")).isFalse();
    }

    @Test
    void filter_keepsOnlyAssignableRolesInDefinedOrder() {
        Role admin = new Role("ROLE_ADMIN");
        Role backup = new Role("ROLE_BACKUP");
        Role stock = new Role("ROLE_STOCK");
        Role ip = new Role("ROLE_IP");

        List<Role> filtered = AssignableRoles.filter(List.of(stock, backup, ip, admin));

        assertThat(filtered).extracting(Role::getName)
                .containsExactly("ROLE_ADMIN", "ROLE_IP", "ROLE_BACKUP");
    }
}
