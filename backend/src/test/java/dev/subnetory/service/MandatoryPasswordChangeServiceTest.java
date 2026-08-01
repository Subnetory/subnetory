package dev.subnetory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.subnetory.domain.User;
import dev.subnetory.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MandatoryPasswordChangeServiceTest {

    @Mock
    UserRepository userRepository;

    MandatoryPasswordChangeService service;

    @BeforeEach
    void setUp() {
        service = new MandatoryPasswordChangeService(userRepository);
    }

    @Test
    void blankUsername_doesNotRequireChange() {
        assertThat(service.isRequired("   ")).isFalse();
        verifyNoInteractions(userRepository);
    }

    @Test
    void enabledLocalUserWithFlag_requiresChange() {
        User user = buildUser("LOCAL", true, true);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThat(service.isRequired("admin")).isTrue();
    }

    @Test
    void enabledLocalUserWithoutFlag_doesNotRequireChange() {
        User user = buildUser("LOCAL", true, false);
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThat(service.isRequired("admin")).isFalse();
    }

    @Test
    void ldapUserIsExcludedEvenWhenFlagged() {
        User user = buildUser("LDAP", true, true);
        when(userRepository.findByUsername("ldap-user")).thenReturn(Optional.of(user));

        assertThat(service.isRequired("ldap-user")).isFalse();
    }

    @Test
    void disabledUserDoesNotRequireChange() {
        User user = buildUser("LOCAL", false, true);
        when(userRepository.findByUsername("disabled")).thenReturn(Optional.of(user));

        assertThat(service.isRequired("disabled")).isFalse();
    }

    private User buildUser(String authType, boolean enabled, boolean required) {
        User user = new User();
        user.setUsername("user");
        user.setAuthType(authType);
        user.setEnabled(enabled);
        user.setMustChangePassword(required);
        return user;
    }
}
