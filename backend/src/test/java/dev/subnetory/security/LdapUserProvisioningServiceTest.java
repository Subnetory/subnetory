package dev.subnetory.security;

import dev.subnetory.config.LdapProperties;
import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;
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
class LdapUserProvisioningServiceTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock DirContextOperations dirContextOperations;

    LdapProperties ldapProperties;
    LdapUserProvisioningService service;

    Role roleIp;

    @BeforeEach
    void setUp() {
        ldapProperties = new LdapProperties();
        ldapProperties.setDefaultRole("ROLE_IP");
        service = new LdapUserProvisioningService(userRepository, roleRepository, ldapProperties);

        roleIp = new Role("ROLE_IP");
    }

    // ── Provisioning — nouvel utilisateur LDAP ─────────────────────────────

    @Test
    void mapUserFromContext_newLdapUser_createsUserWithDefaultRole() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(roleIp));

        User savedUser = buildLdapUser("jdoe");
        when(userRepository.saveAndFlush(any(User.class))).thenReturn(savedUser);

        UserDetails result = service.mapUserFromContext(
                dirContextOperations, "jdoe", Collections.emptyList());

        assertThat(result.getUsername()).isEqualTo("jdoe");
        assertThat(result.getPassword()).isEqualTo("");
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_IP");

        verify(userRepository).saveAndFlush(any(User.class));
    }

    @Test
    void mapUserFromContext_newLdapUser_createsUserWithMultipleDefaultRoles() {
        Role roleNetwork = new Role("ROLE_NETWORK");
        LdapUserProvisioningService multiRoleService = new LdapUserProvisioningService(
                userRepository,
                roleRepository,
                () -> List.of("ROLE_NETWORK", "ROLE_IP"));

        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_NETWORK")).thenReturn(Optional.of(roleNetwork));
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(roleIp));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserDetails result = multiRoleService.mapUserFromContext(
                dirContextOperations, "jdoe", Collections.emptyList());

        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_NETWORK", "ROLE_IP");
    }

    @Test
    void mapUserFromContext_newLdapUser_passwordIsNeverStored() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(roleIp));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            assertThat(u.getPassword()).isNull();
            assertThat(u.getAuthType()).isEqualTo("LDAP");
            return u;
        });

        service.mapUserFromContext(dirContextOperations, "jdoe", Collections.emptyList());
        verify(userRepository).saveAndFlush(any(User.class));
    }

    // ── Utilisateur LDAP existant ──────────────────────────────────────────

    @Test
    void mapUserFromContext_existingLdapUser_loadsExistingRoles() {
        Role roleAdmin = new Role("ROLE_ADMIN");
        Role roleNetwork = new Role("ROLE_NETWORK");

        User existingUser = buildLdapUser("jdoe");
        existingUser.setRoles(new HashSet<>(Set.of(roleAdmin, roleNetwork)));

        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(existingUser));

        UserDetails result = service.mapUserFromContext(
                dirContextOperations, "jdoe", Collections.emptyList());

        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_NETWORK");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void mapUserFromContext_existingLdapUser_doesNotOverwriteExistingRoles() {
        Role roleNetwork = new Role("ROLE_NETWORK");
        User existingUser = buildLdapUser("jdoe");
        existingUser.setRoles(new HashSet<>(Set.of(roleNetwork)));

        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(existingUser));

        UserDetails result = service.mapUserFromContext(
                dirContextOperations, "jdoe", Collections.emptyList());

        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_NETWORK");

        verify(roleRepository, never()).findByName(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    // ── Anti-collision — compte LOCAL ──────────────────────────────────────

    @Test
    void mapUserFromContext_existingLocalUser_throwsBadCredentials() {
        User localUser = new User();
        localUser.setUsername("admin");
        localUser.setAuthType("LOCAL");
        localUser.setEnabled(true);
        localUser.setRoles(new HashSet<>());

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(localUser));

        assertThatThrownBy(() ->
                service.mapUserFromContext(dirContextOperations, "admin", Collections.emptyList()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("local authentication");

        verify(userRepository, never()).saveAndFlush(any());
    }

    // ── Compte désactivé ───────────────────────────────────────────────────

    @Test
    void mapUserFromContext_disabledLdapUser_throwsDisabledException() {
        User disabledUser = buildLdapUser("jdoe");
        disabledUser.setEnabled(false);

        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(disabledUser));

        assertThatThrownBy(() ->
                service.mapUserFromContext(dirContextOperations, "jdoe", Collections.emptyList()))
                .isInstanceOf(DisabledException.class);
    }

    // ── Rôle par défaut manquant en base ───────────────────────────────────

    @Test
    void mapUserFromContext_defaultRoleMissing_throwsIllegalState() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.mapUserFromContext(dirContextOperations, "jdoe", Collections.emptyList()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROLE_IP");
    }

    // ── Concurrence — DataIntegrityViolationException ──────────────────────

    /**
     * Simule la collision : findByUsername retourne empty() (user absent),
     * saveAndFlush lève DataIntegrityViolationException (un autre thread a inséré en parallèle),
     * findByUsername retourne ensuite le user créé par le thread gagnant.
     * Le mapper doit retourner ce UserDetails normalement.
     */
    @Test
    void mapUserFromContext_concurrentFirstLogin_readsUserCreatedByWinningThread() {
        User concurrentlyCreatedUser = buildLdapUser("jdoe");

        when(userRepository.findByUsername("jdoe"))
                .thenReturn(Optional.empty())                          // premier appel : user absent
                .thenReturn(Optional.of(concurrentlyCreatedUser));     // deuxième appel (dans catch)

        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(roleIp));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: users_username_key"));

        UserDetails result = service.mapUserFromContext(
                dirContextOperations, "jdoe", Collections.emptyList());

        assertThat(result.getUsername()).isEqualTo("jdoe");
        assertThat(result.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_IP");
    }

    /**
     * Même collision, mais le user relu après DataIntegrityViolationException
     * a auth_type=LOCAL — cela signifie qu'un compte LOCAL portant ce username
     * a été créé entre les deux appels (scénario pathologique).
     * Le mapper doit refuser.
     */
    @Test
    void mapUserFromContext_concurrentFirstLogin_localUserFoundAfterCollision_throwsBadCredentials() {
        User localUser = new User();
        localUser.setUsername("jdoe");
        localUser.setAuthType("LOCAL");
        localUser.setEnabled(true);
        localUser.setRoles(new HashSet<>());

        when(userRepository.findByUsername("jdoe"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(localUser));

        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(roleIp));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() ->
                service.mapUserFromContext(dirContextOperations, "jdoe", Collections.emptyList()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("conflicting authentication type");
    }

    // ── Utilitaire ─────────────────────────────────────────────────────────

    private User buildLdapUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(null);
        user.setAuthType("LDAP");
        user.setEnabled(true);
        user.setRoles(new HashSet<>(Set.of(roleIp)));
        return user;
    }
}
