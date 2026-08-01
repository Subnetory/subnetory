package dev.subnetory.service;

import dev.subnetory.domain.User;
import dev.subnetory.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MfaLoginChallengeServiceTest {

    @Mock UserRepository userRepository;
    @Mock MfaService mfaService;

    MfaLoginChallengeService service;

    private User buildUser(boolean enabled, boolean mfaEnabled) {
        User user = new User();
        user.setUsername("jdoe");
        user.setEnabled(enabled);
        user.setMfaEnabled(mfaEnabled);
        return user;
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new MfaLoginChallengeService(userRepository, mfaService);
    }

    @Test
    void isRequired_blankUsername_returnsFalse() {
        assertThat(service.isRequired(" ")).isFalse();
        assertThat(service.isRequired(null)).isFalse();
    }

    @Test
    void isRequired_unknownUser_returnsFalse() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.empty());

        assertThat(service.isRequired("jdoe")).isFalse();
    }

    @Test
    void isRequired_disabledAccount_returnsFalse() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(buildUser(false, true)));

        assertThat(service.isRequired("jdoe")).isFalse();
    }

    @Test
    void isRequired_mfaDisabled_returnsFalse() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(buildUser(true, false)));

        assertThat(service.isRequired("jdoe")).isFalse();
    }

    @Test
    void isRequired_mfaEnabled_returnsTrue() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(buildUser(true, true)));

        assertThat(service.isRequired("jdoe")).isTrue();
    }

    @Test
    void verify_blankCode_returnsFalseWithoutCallingMfaService() {
        assertThat(service.verify("jdoe", " ")).isFalse();
        assertThat(service.verify("jdoe", null)).isFalse();

        verify(mfaService, never()).verifyChallenge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verify_unknownUser_returnsFalse() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.empty());

        assertThat(service.verify("jdoe", "123456")).isFalse();
    }

    @Test
    void verify_mfaNotEnabled_returnsFalse() {
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(buildUser(true, false)));

        assertThat(service.verify("jdoe", "123456")).isFalse();

        verify(mfaService, never()).verifyChallenge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void verify_delegatesToMfaService() {
        User user = buildUser(true, true);
        when(userRepository.findByUsername("jdoe")).thenReturn(Optional.of(user));
        when(mfaService.verifyChallenge(user, "123456")).thenReturn(true);

        assertThat(service.verify("jdoe", "123456")).isTrue();
    }
}
