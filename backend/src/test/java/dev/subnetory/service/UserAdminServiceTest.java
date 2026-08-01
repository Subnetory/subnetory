package dev.subnetory.service;

import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;

    UserAdminService service;

    Role roleAdmin;
    Role roleReadOnly;
    Role roleNetwork;
    Role roleIp;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(userRepository, roleRepository);

        roleAdmin   = buildRole(1L, "ROLE_ADMIN");
        roleReadOnly = buildRole(4L, "ROLE_READ_ONLY");
        roleNetwork = buildRole(2L, "ROLE_NETWORK");
        roleIp      = buildRole(3L, "ROLE_IP");
    }

    @Test
    void findAssignableRoles_includesReadOnlyRole() {
        when(roleRepository.findAll()).thenReturn(List.of(roleAdmin, roleReadOnly, roleNetwork, roleIp));

        List<Role> result = service.findAssignableRoles();

        assertThat(result)
                .extracting(Role::getName)
                .containsExactly("ROLE_ADMIN", "ROLE_READ_ONLY", "ROLE_NETWORK", "ROLE_IP");
    }

    // ── updateRoles — cas nominaux ─────────────────────────────────────────

    @Test
    void updateRoles_valid_assignsNewRoles() {
        User target = buildUser(10L, "jdoe", Set.of(roleIp));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(roleNetwork));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.updateRoles(10L, Set.of(2L), "admin");

        assertThat(result.getRoles()).containsExactly(roleNetwork);
        verify(userRepository).save(target);
    }

    @Test
    void updateRoles_addRoleToExistingAdmin_succeeds() {
        User target = buildUser(10L, "jdoe", Set.of(roleAdmin));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(1L)).thenReturn(Optional.of(roleAdmin));
        when(roleRepository.findById(2L)).thenReturn(Optional.of(roleNetwork));
        // Target is admin but still keeps ROLE_ADMIN → no lockout check needed
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.updateRoles(10L, Set.of(1L, 2L), "admin");

        assertThat(result.getRoles()).containsExactlyInAnyOrder(roleAdmin, roleNetwork);
    }

    @Test
    void updateRoles_removeAdminWhenMultipleAdmins_succeeds() {
        User target = buildUser(10L, "jdoe", Set.of(roleAdmin));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(roleIp));
        when(userRepository.countActiveByRoleName("ROLE_ADMIN")).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.updateRoles(10L, Set.of(3L), "admin");

        assertThat(result.getRoles()).containsExactly(roleIp);
    }

    // ── updateRoles — règles anti-lockout ──────────────────────────────────

    @Test
    void updateRoles_emptyRoles_throwsAdminLockout() {
        User target = buildUser(10L, "jdoe", Set.of(roleIp));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.updateRoles(10L, Set.of(), "admin"))
                .isInstanceOf(AdminLockoutException.class)
                .hasMessageContaining("au moins un role");

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateRoles_nullRoles_throwsAdminLockout() {
        User target = buildUser(10L, "jdoe", Set.of(roleIp));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.updateRoles(10L, null, "admin"))
                .isInstanceOf(AdminLockoutException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateRoles_removeAdminFromLastActiveAdmin_throwsAdminLockout() {
        User target = buildUser(10L, "admin", Set.of(roleAdmin));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(3L)).thenReturn(Optional.of(roleIp));
        when(userRepository.countActiveByRoleName("ROLE_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.updateRoles(10L, Set.of(3L), "admin"))
                .isInstanceOf(AdminLockoutException.class)
                .hasMessageContaining("dernier administrateur");

        verify(userRepository, never()).save(any());
    }

    @Test
    void updateRoles_unknownRoleId_throwsResourceNotFound() {
        User target = buildUser(10L, "jdoe", Set.of(roleIp));
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(roleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRoles(10L, Set.of(99L), "admin"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository, never()).save(any());
    }

    // ── setEnabled — activation ────────────────────────────────────────────

    @Test
    void enable_disabledUser_succeeds() {
        User target = buildUser(10L, "jdoe", Set.of(roleIp));
        target.setEnabled(false);
        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.setEnabled(10L, true, "admin");

        assertThat(result.isEnabled()).isTrue();
        verify(userRepository).save(target);
    }

    // ── setEnabled — désactivation — cas nominaux ──────────────────────────

    @Test
    void disable_regularUser_succeeds() {
        User current = buildUser(1L, "admin", Set.of(roleAdmin));
        User target  = buildUser(10L, "jdoe", Set.of(roleIp));

        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(current));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.setEnabled(10L, false, "admin");

        assertThat(result.isEnabled()).isFalse();
        verify(userRepository).save(target);
    }

    @Test
    void disable_adminWhenMultipleAdmins_succeeds() {
        User current = buildUser(1L, "admin1", Set.of(roleAdmin));
        User target  = buildUser(10L, "admin2", Set.of(roleAdmin));

        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("admin1")).thenReturn(Optional.of(current));
        when(userRepository.countActiveByRoleName("ROLE_ADMIN")).thenReturn(2L);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.setEnabled(10L, false, "admin1");

        assertThat(result.isEnabled()).isFalse();
    }

    // ── setEnabled — désactivation — règles anti-lockout ──────────────────

    @Test
    void disable_ownAccount_throwsAdminLockout() {
        User current = buildUser(1L, "admin", Set.of(roleAdmin));
        when(userRepository.findById(1L)).thenReturn(Optional.of(current));
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.setEnabled(1L, false, "admin"))
                .isInstanceOf(AdminLockoutException.class)
                .hasMessageContaining("votre propre compte");

        verify(userRepository, never()).save(any());
    }

    @Test
    void disable_lastActiveAdmin_throwsAdminLockout() {
        User current = buildUser(1L, "superadmin", Set.of(roleAdmin));
        User target  = buildUser(10L, "admin", Set.of(roleAdmin));

        when(userRepository.findById(10L)).thenReturn(Optional.of(target));
        when(userRepository.findByUsername("superadmin")).thenReturn(Optional.of(current));
        when(userRepository.countActiveByRoleName("ROLE_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.setEnabled(10L, false, "superadmin"))
                .isInstanceOf(AdminLockoutException.class)
                .hasMessageContaining("dernier administrateur");

        verify(userRepository, never()).save(any());
    }

    // ── Utilitaires ────────────────────────────────────────────────────────

    private User buildUser(Long id, String username, Set<Role> roles) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setAuthType("LOCAL");
        user.setEnabled(true);
        user.setRoles(new HashSet<>(roles));
        return user;
    }

    private Role buildRole(Long id, String name) {
        Role role = new Role(name);
        role.setId(id);
        return role;
    }
}
