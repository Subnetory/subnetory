package dev.subnetory.api.v1;

import dev.subnetory.dto.LdapSettingsRequest;
import dev.subnetory.service.LdapAdminDiagnosticService;
import dev.subnetory.service.LdapConfigurationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminLdapControllerTest {

    private final LdapConfigurationService configurationService = mock(LdapConfigurationService.class);
    private final LdapAdminDiagnosticService diagnosticService = mock(LdapAdminDiagnosticService.class);
    private final AdminLdapController controller =
            new AdminLdapController(configurationService, diagnosticService);

    @Test
    void getSettingsDoesNotExposeManagerPassword() {
        when(diagnosticService.status()).thenReturn(status(true));

        var response = controller.getSettings();

        assertThat(response.enabled()).isTrue();
        assertThat(response.managerPasswordConfigured()).isTrue();
        assertThat(response.toString()).doesNotContain("SecretPass123!");
    }

    @Test
    void updateSettingsDelegatesToConfigurationServiceAndReturnsUpdatedStatus() {
        when(diagnosticService.status()).thenReturn(status(false));
        var request = new LdapSettingsRequest(
                false,
                "ldaps://ldap.example.com:636",
                "dc=example,dc=com",
                "ou=users",
                "(sAMAccountName={0})",
                "cn=subnetory,dc=example,dc=com",
                "SecretPass123!",
                false,
                Set.of("ROLE_NETWORK", "ROLE_IP"),
                "ROLE_IP");

        var response = controller.updateSettings(request);

        verify(configurationService).save(org.mockito.ArgumentMatchers.any());
        assertThat(response.getBody().enabled()).isFalse();
        assertThat(response.getBody().defaultRoles()).containsExactly("ROLE_IP");
    }

    private LdapAdminDiagnosticService.LdapStatus status(boolean enabled) {
        return new LdapAdminDiagnosticService.LdapStatus(
                enabled,
                "ldaps://ldap.example.com:636",
                "dc=example,dc=com",
                "ou=users",
                "(sAMAccountName={0})",
                true,
                true,
                "ROLE_IP",
                List.of("ROLE_IP"));
    }
}
