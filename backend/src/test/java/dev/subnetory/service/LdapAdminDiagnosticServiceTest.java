package dev.subnetory.service;

import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.naming.directory.DirContext;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LdapAdminDiagnosticServiceTest {

    @Test
    void status_reportsManagerConfigurationWithoutExposingSecret() {
        var settings = settings(true, "secret-value", "ROLE_NETWORK");
        LdapAdminDiagnosticService service = newService(settings, null);

        LdapAdminDiagnosticService.LdapStatus status = service.status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.managerDnConfigured()).isTrue();
        assertThat(status.managerPasswordConfigured()).isTrue();
        assertThat(status.defaultRole()).isEqualTo("ROLE_NETWORK");
        assertThat(status.defaultRoles()).containsExactly("ROLE_NETWORK");
        assertThat(status.toString()).doesNotContain("secret-value");
    }

    @Test
    void testConnection_whenDisabled_returnsWarning() {
        LdapAdminDiagnosticService service = newService(settings(false, "", "ROLE_IP"), null);

        LdapAdminDiagnosticService.LdapDiagnosticResult result = service.testConnection();

        assertThat(result.level()).isEqualTo("warning");
        assertThat(result.title()).isEqualTo("Service LDAP inactif");
    }

    @Test
    void testConnection_success_closesContext() throws Exception {
        LdapContextSource contextSource = mock(LdapContextSource.class);
        DirContext context = mock(DirContext.class);
        when(contextSource.getReadOnlyContext()).thenReturn(context);
        LdapAdminDiagnosticService service = newService(settings(true, "", "ROLE_IP"), contextSource);

        LdapAdminDiagnosticService.LdapDiagnosticResult result = service.testConnection();

        assertThat(result.level()).isEqualTo("success");
        verify(context).close();
    }

    @Test
    void testConnection_errorMasksManagerPassword() {
        LdapContextSource contextSource = mock(LdapContextSource.class);
        when(contextSource.getReadOnlyContext())
                .thenThrow(new IllegalStateException("bind failed with super-secret"));
        LdapAdminDiagnosticService service = newService(settings(true, "super-secret", "ROLE_IP"), contextSource);

        LdapAdminDiagnosticService.LdapDiagnosticResult result = service.testConnection();

        assertThat(result.level()).isEqualTo("error");
        assertThat(result.message()).contains("********");
        assertThat(result.message()).doesNotContain("super-secret");
    }

    @Test
    void testUserSearch_whenDisabled_returnsWarningBeforeValidatingUsername() {
        LdapAdminDiagnosticService service = newService(settings(false, "", "ROLE_IP"), null);

        LdapAdminDiagnosticService.LdapDiagnosticResult result = service.testUserSearch("user\u0001");

        assertThat(result.level()).isEqualTo("warning");
    }

    @Test
    void testUserSearch_blankUsername_returnsError() {
        LdapAdminDiagnosticService service = newService(settings(true, "", "ROLE_IP"), null);

        LdapAdminDiagnosticService.LdapDiagnosticResult result = service.testUserSearch("   ");

        assertThat(result.level()).isEqualTo("error");
        assertThat(result.title()).isEqualTo("Identifiant requis");
    }

    @Test
    void testUserSearch_rejectsControlCharactersAndLongValues() {
        LdapAdminDiagnosticService service = newService(settings(true, "", "ROLE_IP"), null);

        assertThatThrownBy(() -> service.testUserSearch("adm\nin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identifiant invalide.");
        assertThatThrownBy(() -> service.testUserSearch("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Identifiant trop long.");
    }

    @Test
    void testUserSearch_searchFailureReturnsSafeError() {
        LdapContextSource contextSource = mock(LdapContextSource.class);
        when(contextSource.getReadOnlyContext())
                .thenThrow(new IllegalStateException("ldap-password refused"));
        LdapAdminDiagnosticService service = newService(settings(true, "ldap-password", "ROLE_IP"), contextSource);

        LdapAdminDiagnosticService.LdapDiagnosticResult result = service.testUserSearch("jdupont");

        assertThat(result.level()).isEqualTo("error");
        assertThat(result.title()).isEqualTo("Utilisateur introuvable");
        assertThat(result.message()).doesNotContain("ldap-password");
    }

    private static LdapConfigurationService.EffectiveLdapSettings settings(boolean enabled,
                                                                          String managerPassword,
                                                                          String defaultRole) {
        return new LdapConfigurationService.EffectiveLdapSettings(
                enabled,
                "ldap://localhost:389",
                "dc=example,dc=com",
                "ou=users",
                "(sAMAccountName={0})",
                "cn=subnetory,dc=example,dc=com",
                managerPassword,
                List.of(defaultRole));
    }

    private static LdapAdminDiagnosticService newService(
            LdapConfigurationService.EffectiveLdapSettings settings,
            LdapContextSource contextSource
    ) {
        LdapConfigurationService configurationService = mock(LdapConfigurationService.class);
        when(configurationService.effectiveSettings()).thenReturn(settings);
        return new LdapAdminDiagnosticService(configurationService) {
            @Override
            public LdapContextSource contextSource(LdapConfigurationService.EffectiveLdapSettings ignored) {
                if (contextSource == null) {
                    return super.contextSource(ignored);
                }
                return contextSource;
            }
        };
    }
}
