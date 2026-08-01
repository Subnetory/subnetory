package dev.subnetory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.subnetory.domain.User;
import dev.subnetory.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    DataInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new DataInitializer();
        ReflectionTestUtils.setField(
                initializer,
                "defaultAdminPassword",
                "BootstrapPass123!");
    }

    @Test
    void initAdminPassword_missingHash_setsMandatoryPasswordChange() throws Exception {
        User admin = buildAdmin(null);

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.encode("BootstrapPass123!")).thenReturn("bootstrap-hash");

        initializer.initAdminPassword(userRepository, passwordEncoder).run(null);

        assertThat(admin.getPassword()).isEqualTo("bootstrap-hash");
        assertThat(admin.isMustChangePassword()).isTrue();
        verify(userRepository).save(admin);
    }

    @Test
    void initAdminPassword_missingHashAndMissingBootstrapPassword_failsExplicitly() {
        User admin = buildAdmin(null);
        ReflectionTestUtils.setField(initializer, "defaultAdminPassword", "");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> initializer.initAdminPassword(userRepository, passwordEncoder).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subnetory.admin.default-password")
                .hasMessageContaining("first startup");

        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void initAdminPassword_existingHashAndMissingBootstrapPassword_allowsStartup() throws Exception {
        User admin = buildAdmin("existing-hash");
        admin.setMustChangePassword(false);
        ReflectionTestUtils.setField(initializer, "defaultAdminPassword", "");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer.initAdminPassword(userRepository, passwordEncoder).run(null);

        assertThat(admin.getPassword()).isEqualTo("existing-hash");
        assertThat(admin.isMustChangePassword()).isFalse();
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void initAdminPassword_existingHashAndDifferentBootstrapPassword_doesNotOverwritePassword() throws Exception {
        User admin = buildAdmin("current-hash");
        admin.setMustChangePassword(false);
        ReflectionTestUtils.setField(
                initializer,
                "defaultAdminPassword",
                "DifferentBootstrapPass123!");

        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));

        initializer.initAdminPassword(userRepository, passwordEncoder).run(null);

        assertThat(admin.getPassword()).isEqualTo("current-hash");
        assertThat(admin.isMustChangePassword()).isFalse();
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    private User buildAdmin(String password) {
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(password);
        admin.setAuthType("LOCAL");
        admin.setEnabled(true);
        return admin;
    }
}
