package dev.subnetory.service;

import dev.subnetory.config.LdapProperties;
import dev.subnetory.domain.LdapSettings;
import dev.subnetory.domain.Role;
import dev.subnetory.repository.LdapSettingsRepository;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.web.form.LdapSettingsForm;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LdapConfigurationServiceTest {

    private final LdapProperties fallbackProperties = new LdapProperties();
    private final LdapSettingsRepository repository = mock(LdapSettingsRepository.class);
    private final RoleRepository roleRepository = mock(RoleRepository.class);
    private final SecretCipherService secretCipherService =
            new SecretCipherService("01234567890123456789012345678901");
    private final LdapConfigurationService service = new LdapConfigurationService(
            fallbackProperties,
            repository,
            roleRepository,
            secretCipherService);

    @Test
    void effectiveSettings_usesFallbackPropertiesWhenDatabaseConfigurationDoesNotExist() {
        fallbackProperties.setEnabled(true);
        fallbackProperties.setUrl("ldaps://ldap.example.com:636");
        when(repository.findById(LdapSettings.SINGLETON_ID)).thenReturn(Optional.empty());

        var settings = service.effectiveSettings();

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.url()).isEqualTo("ldaps://ldap.example.com:636");
    }

    @Test
    void saveEncryptsManagerPasswordAndDoesNotStorePlainText() {
        when(repository.findById(LdapSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(new Role("ROLE_IP")));
        when(repository.save(any(LdapSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LdapSettings saved = serviceSave(form("SecretPass123!"));

        assertThat(saved.getManagerPasswordEncrypted()).isNotBlank();
        assertThat(saved.getManagerPasswordEncrypted()).doesNotContain("SecretPass123!");
        assertThat(secretCipherService.decrypt(saved.getManagerPasswordEncrypted()))
                .isEqualTo("SecretPass123!");
    }

    @Test
    void saveKeepsFallbackManagerPasswordOnFirstGraphicalSave() {
        fallbackProperties.setManagerPassword("FallbackSecret123!");
        when(repository.findById(LdapSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(new Role("ROLE_IP")));

        LdapSettings saved = serviceSave(form(""));

        assertThat(secretCipherService.decrypt(saved.getManagerPasswordEncrypted()))
                .isEqualTo("FallbackSecret123!");
    }

    @Test
    void saveAcceptsMultipleDefaultRolesAndStoresThemInStableOrder() {
        when(repository.findById(LdapSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(roleRepository.findByName("ROLE_NETWORK")).thenReturn(Optional.of(new Role("ROLE_NETWORK")));
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(new Role("ROLE_IP")));

        LdapSettingsForm form = form("");
        form.setDefaultRoles(Set.of("ROLE_IP", "ROLE_NETWORK"));

        LdapSettings saved = serviceSave(form);

        assertThat(saved.getDefaultRole()).isEqualTo("ROLE_NETWORK,ROLE_IP");
    }

    @Test
    void saveRejectsEmptyDefaultRoles() {
        LdapSettingsForm form = form("SecretPass123!");
        form.setDefaultRoles(Set.of());

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Sélectionnez au moins un rôle LDAP par défaut.");
    }

    @Test
    void saveRejectsInvalidUrlSchemeAndUnsafeFilter() {
        when(roleRepository.findByName("ROLE_IP")).thenReturn(Optional.of(new Role("ROLE_IP")));

        LdapSettingsForm invalidUrl = form("SecretPass123!");
        invalidUrl.setUrl("http://ldap.example.com");
        assertThatThrownBy(() -> service.save(invalidUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("URL LDAP invalide.");

        LdapSettingsForm invalidFilter = form("SecretPass123!");
        invalidFilter.setUserSearchFilter("(uid=admin)");
        assertThatThrownBy(() -> service.save(invalidFilter))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Le filtre utilisateur doit contenir le paramètre {0}.");
    }

    private LdapSettings serviceSave(LdapSettingsForm form) {
        final LdapSettings[] saved = new LdapSettings[1];
        when(repository.save(any(LdapSettings.class))).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        service.save(form);
        return saved[0];
    }

    private LdapSettingsForm form(String password) {
        LdapSettingsForm form = new LdapSettingsForm();
        form.setEnabled(true);
        form.setUrl("ldaps://ldap.example.com:636");
        form.setBaseDn("dc=example,dc=com");
        form.setUserSearchBase("ou=users");
        form.setUserSearchFilter("(sAMAccountName={0})");
        form.setManagerDn("cn=subnetory,dc=example,dc=com");
        form.setManagerPassword(password);
        form.setDefaultRole("ROLE_IP");
        return form;
    }
}
