package dev.subnetory.service;

import dev.subnetory.domain.User;
import dev.subnetory.exception.InvalidMfaCodeException;
import dev.subnetory.exception.PasswordPolicyException;
import dev.subnetory.repository.NetworkContextRepository;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceMfaTest {

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock PasswordPolicyService passwordPolicyService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuthAuditService authAuditService;
    @Mock UserTokenInvalidationService userTokenInvalidationService;
    @Mock NetworkContextRepository contextRepository;
    @Mock MfaService mfaService;

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
                contextRepository,
                mfaService);
    }

    private User buildLocalUser(String username, String passwordHash, boolean mfaEnabled) {
        User user = new User();
        user.setId(1L);
        user.setUsername(username);
        user.setPassword(passwordHash);
        user.setAuthType("LOCAL");
        user.setMfaEnabled(mfaEnabled);
        return user;
    }

    @Test
    void beginMfaSetup_delegatesToMfaService() {
        User user = buildLocalUser("jdoe", "hash", false);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(mfaService.beginSetup("jdoe")).thenReturn(new MfaService.MfaSetup("SECRET", "data:uri"));

        MfaService.MfaSetup setup = service.beginMfaSetup("jdoe");

        assertThat(setup.secret()).isEqualTo("SECRET");
    }

    @Test
    void enableMfa_delegatesAndRecordsAudit() {
        User user = buildLocalUser("jdoe", "hash", false);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(mfaService.activate(user, "SECRET", "123456")).thenReturn(List.of("a-b", "c-d"));

        List<String> codes = service.enableMfa("jdoe", "SECRET", "123456", "10.0.0.1", "JUnit");

        assertThat(codes).containsExactly("a-b", "c-d");
        verify(authAuditService).recordMfaEnabled("jdoe", "10.0.0.1", "JUnit");
    }

    @Test
    void disableOwnMfa_wrongPassword_throwsAndDoesNotCallMfaService() {
        User user = buildLocalUser("jdoe", "hash", true);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.disableOwnMfa("jdoe", "wrong", "123456", "10.0.0.1", "JUnit"))
                .isInstanceOf(PasswordPolicyException.class);

        verify(mfaService, never()).disable(any());
        verify(authAuditService, never()).recordMfaDisabled(any(), any(), any());
    }

    @Test
    void disableOwnMfa_ldapAccount_isRejected() {
        User user = buildLocalUser("jdoe", null, true);
        user.setAuthType("LDAP");
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.disableOwnMfa("jdoe", "whatever", "123456", "10.0.0.1", "JUnit"))
                .isInstanceOf(PasswordPolicyException.class);

        verify(mfaService, never()).disable(any());
    }

    @Test
    void disableOwnMfa_notEnabled_isRejected() {
        User user = buildLocalUser("jdoe", "hash", false);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CurrentPass123!", "hash")).thenReturn(true);

        assertThatThrownBy(() -> service.disableOwnMfa("jdoe", "CurrentPass123!", "123456", "10.0.0.1", "JUnit"))
                .isInstanceOf(PasswordPolicyException.class);
    }

    @Test
    void disableOwnMfa_invalidCode_throwsAndDoesNotDisable() {
        User user = buildLocalUser("jdoe", "hash", true);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CurrentPass123!", "hash")).thenReturn(true);
        when(mfaService.verifyChallenge(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.disableOwnMfa("jdoe", "CurrentPass123!", "000000", "10.0.0.1", "JUnit"))
                .isInstanceOf(InvalidMfaCodeException.class);

        verify(mfaService, never()).disable(any());
    }

    @Test
    void disableOwnMfa_success_disablesAndRecordsAudit() {
        User user = buildLocalUser("jdoe", "hash", true);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("CurrentPass123!", "hash")).thenReturn(true);
        when(mfaService.verifyChallenge(user, "123456")).thenReturn(true);

        service.disableOwnMfa("jdoe", "CurrentPass123!", "123456", "10.0.0.1", "JUnit");

        verify(mfaService).disable(user);
        verify(authAuditService).recordMfaDisabled("jdoe", "10.0.0.1", "JUnit");
    }

    @Test
    void regenerateOwnMfaRecoveryCodes_notEnabled_isRejected() {
        User user = buildLocalUser("jdoe", "hash", false);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.regenerateOwnMfaRecoveryCodes("jdoe", "123456", "10.0.0.1", "JUnit"))
                .isInstanceOf(PasswordPolicyException.class);

        verify(mfaService, never()).regenerateRecoveryCodes(any());
    }

    @Test
    void regenerateOwnMfaRecoveryCodes_invalidCode_throws() {
        User user = buildLocalUser("jdoe", "hash", true);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(mfaService.verifyChallenge(user, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.regenerateOwnMfaRecoveryCodes("jdoe", "000000", "10.0.0.1", "JUnit"))
                .isInstanceOf(InvalidMfaCodeException.class);
    }

    @Test
    void regenerateOwnMfaRecoveryCodes_success_returnsNewCodesAndRecordsAudit() {
        User user = buildLocalUser("jdoe", "hash", true);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(mfaService.verifyChallenge(user, "123456")).thenReturn(true);
        when(mfaService.regenerateRecoveryCodes(user)).thenReturn(List.of("x-y"));

        List<String> codes = service.regenerateOwnMfaRecoveryCodes("jdoe", "123456", "10.0.0.1", "JUnit");

        assertThat(codes).containsExactly("x-y");
        verify(authAuditService).recordMfaRecoveryCodesRegenerated("jdoe", "10.0.0.1", "JUnit");
    }

    // adminDisableMfa (Sprint 2.37 / Lot 4 - anti-lockout admin)

    @Test
    void adminDisableMfa_notEnabled_isRejectedAndDoesNotCallMfaService() {
        User user = buildLocalUser("jdoe", "hash", false);
        user.setId(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.adminDisableMfa(10L, "admin", "10.0.0.1", "JUnit"))
                .isInstanceOf(PasswordPolicyException.class);

        verify(mfaService, never()).disable(any());
        verify(authAuditService, never()).recordMfaDisabledByAdmin(any(), any(), any(), any());
    }

    @Test
    void adminDisableMfa_success_disablesAndRecordsAudit() {
        User user = buildLocalUser("jdoe", "hash", true);
        user.setId(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));

        service.adminDisableMfa(10L, "admin", "10.0.0.1", "JUnit");

        verify(mfaService).disable(user);
        verify(authAuditService).recordMfaDisabledByAdmin("admin", "jdoe", "10.0.0.1", "JUnit");
    }

    @Test
    void adminDisableMfa_unknownUser_throwsResourceNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adminDisableMfa(999L, "admin", "10.0.0.1", "JUnit"))
                .isInstanceOf(dev.subnetory.exception.ResourceNotFoundException.class);

        verify(mfaService, never()).disable(any());
    }
}
