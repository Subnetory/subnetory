package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserAdminServicePasswordInvalidationTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock PasswordPolicyService passwordPolicyService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthAuditService authAuditService;
    @Mock UserTokenInvalidationService userTokenInvalidationService;
    @Mock NetworkContextRepository contextRepository;

    UserAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(
                userRepository,
                roleRepository,
                passwordPolicyService,
                passwordEncoder,
                authAuditService,
                userTokenInvalidationService,
                contextRepository);
    }

    @Test
    void changeOwnPassword_invalidatesApiTokensAfterSuccessfulPasswordChange() {
        User user = buildLocalUser(10L, "jdoe", "old-hash");
        user.setMustChangePassword(true);

        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("OldPass123!", "old-hash")).thenReturn(true);
        when(passwordEncoder.encode("NewPass123!")).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.changeOwnPassword(
                "jdoe",
                "OldPass123!",
                "NewPass123!",
                "10.0.0.10",
                "JUnit");

        assertThat(user.getPassword()).isEqualTo("new-hash");
        assertThat(user.isMustChangePassword()).isFalse();
        verify(userTokenInvalidationService).invalidateTokens(
                eq("jdoe"),
                eq("jdoe"),
                eq(UserTokenInvalidationService.REASON_PASSWORD_CHANGE));
        verify(authAuditService).recordTokensInvalidated(
                eq("jdoe"),
                eq("jdoe"),
                eq("10.0.0.10"),
                eq("JUnit"),
                eq(UserTokenInvalidationService.REASON_PASSWORD_CHANGE));
        verify(authAuditService).recordPasswordChange(
                eq("jdoe"),
                eq("10.0.0.10"),
                eq("JUnit"));
    }

    @Test
    void adminResetPassword_invalidatesApiTokensAfterSuccessfulReset() {
        User user = buildLocalUser(10L, "jdoe", "old-hash");

        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("ResetPass123!")).thenReturn("reset-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.adminResetPassword(
                10L,
                "ResetPass123!",
                "admin",
                "10.0.0.20",
                "JUnit");

        assertThat(user.getPassword()).isEqualTo("reset-hash");
        assertThat(user.isMustChangePassword()).isTrue();
        verify(userTokenInvalidationService).invalidateTokens(
                eq("jdoe"),
                eq("admin"),
                eq(UserTokenInvalidationService.REASON_PASSWORD_CHANGE));
        verify(authAuditService).recordTokensInvalidated(
                eq("admin"),
                eq("jdoe"),
                eq("10.0.0.20"),
                eq("JUnit"),
                eq(UserTokenInvalidationService.REASON_PASSWORD_CHANGE));
        verify(authAuditService).recordAdminPasswordReset(
                eq("admin"),
                eq("jdoe"),
                eq("10.0.0.20"),
                eq("JUnit"));
    }

    @Test
    void createLocalUser_setsTemporaryPasswordAndContextScope() {
        Role roleIp = new Role("ROLE_IP");
        roleIp.setId(3L);
        NetworkContext production = buildContext(7L, "PRODUCTION");

        when(userRepository.existsByUsernameIgnoreCase("client.viewer")).thenReturn(false);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(roleIp));
        when(contextRepository.findById(7L)).thenReturn(Optional.of(production));
        when(passwordEncoder.encode("ValidPass123!")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        User created = service.createLocalUser(
                " client.viewer ",
                " client.viewer@example.com ",
                "ValidPass123!",
                true,
                Set.of(3L),
                Set.of(7L),
                "admin");

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getUsername()).isEqualTo("client.viewer");
        assertThat(created.getEmail()).isEqualTo("client.viewer@example.com");
        assertThat(created.getPassword()).isEqualTo("encoded-password");
        assertThat(created.getAuthType()).isEqualTo("LOCAL");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(created.getRoles()).containsExactly(roleIp);
        assertThat(created.getAllowedContexts()).containsExactly(production);
        verify(passwordPolicyService).validate("ValidPass123!");
        verify(authAuditService).recordUserCreated("admin", "client.viewer", 1, 1);
    }

    @Test
    void createLocalUser_duplicateUsername_isRejectedBeforePasswordHash() {
        when(userRepository.existsByUsernameIgnoreCase("client.viewer")).thenReturn(true);

        assertThatThrownBy(() -> service.createLocalUser(
                "client.viewer",
                null,
                "ValidPass123!",
                true,
                Set.of(3L),
                Set.of(),
                "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existe deja");

        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void createLocalUser_unknownContext_isRejected() {
        Role roleIp = new Role("ROLE_IP");
        roleIp.setId(3L);

        when(userRepository.existsByUsernameIgnoreCase("client.viewer")).thenReturn(false);
        when(roleRepository.findById(3L)).thenReturn(Optional.of(roleIp));
        when(contextRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createLocalUser(
                "client.viewer",
                null,
                "ValidPass123!",
                true,
                Set.of(3L),
                Set.of(99L),
                "admin"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(userRepository, never()).save(any());
    }

    private static User buildLocalUser(Long id, String username, String password) {
        Role roleAdmin = new Role("ROLE_ADMIN");
        roleAdmin.setId(1L);

        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword(password);
        user.setAuthType("LOCAL");
        user.setEnabled(true);
        user.setRoles(new HashSet<>(Set.of(roleAdmin)));
        return user;
    }

    private static NetworkContext buildContext(Long id, String name) {
        NetworkContext context = new NetworkContext();
        context.setId(id);
        context.setName(name);
        return context;
    }
}
