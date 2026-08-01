package dev.subnetory.api.v1;

import dev.subnetory.dto.LdapDiagnosticResponse;
import dev.subnetory.dto.LdapSettingsRequest;
import dev.subnetory.dto.LdapSettingsResponse;
import dev.subnetory.dto.LdapUserSearchRequest;
import dev.subnetory.service.LdapAdminDiagnosticService;
import dev.subnetory.service.LdapConfigurationService;
import dev.subnetory.web.form.LdapSettingsForm;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ldap")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin - LDAP", description = "Configuration et diagnostics LDAP")
public class AdminLdapController {

    private final LdapConfigurationService ldapConfigurationService;
    private final LdapAdminDiagnosticService ldapAdminDiagnosticService;

    public AdminLdapController(LdapConfigurationService ldapConfigurationService,
                               LdapAdminDiagnosticService ldapAdminDiagnosticService) {
        this.ldapConfigurationService = ldapConfigurationService;
        this.ldapAdminDiagnosticService = ldapAdminDiagnosticService;
    }

    @GetMapping
    @Operation(summary = "Lire la configuration LDAP active")
    public LdapSettingsResponse getSettings() {
        return toResponse(ldapAdminDiagnosticService.status());
    }

    @PutMapping
    @Operation(summary = "Modifier la configuration LDAP")
    public ResponseEntity<LdapSettingsResponse> updateSettings(@RequestBody LdapSettingsRequest request) {
        ldapConfigurationService.save(toForm(request));
        return ResponseEntity.ok(toResponse(ldapAdminDiagnosticService.status()));
    }

    @PostMapping("/test-connection")
    @Operation(summary = "Tester la connexion LDAP")
    public LdapDiagnosticResponse testConnection() {
        return toResponse(ldapAdminDiagnosticService.testConnection());
    }

    @PostMapping("/test-user")
    @Operation(summary = "Tester la recherche d'un utilisateur LDAP")
    public LdapDiagnosticResponse testUser(@RequestBody LdapUserSearchRequest request) {
        return toResponse(ldapAdminDiagnosticService.testUserSearch(request.username()));
    }

    private LdapSettingsForm toForm(LdapSettingsRequest request) {
        LdapSettingsForm form = new LdapSettingsForm();
        form.setEnabled(request.enabled());
        form.setUrl(request.url());
        form.setBaseDn(request.baseDn());
        form.setUserSearchBase(request.userSearchBase());
        form.setUserSearchFilter(request.userSearchFilter());
        form.setManagerDn(request.managerDn());
        form.setManagerPassword(request.managerPassword());
        form.setClearManagerPassword(request.clearManagerPassword());
        if (request.defaultRoles() != null && !request.defaultRoles().isEmpty()) {
            form.setDefaultRoles(request.defaultRoles());
        } else {
            form.setDefaultRole(request.defaultRole());
        }
        return form;
    }

    private LdapSettingsResponse toResponse(LdapAdminDiagnosticService.LdapStatus status) {
        return new LdapSettingsResponse(
                status.enabled(),
                status.url(),
                status.baseDn(),
                status.userSearchBase(),
                status.userSearchFilter(),
                status.managerDnConfigured(),
                status.managerPasswordConfigured(),
                status.defaultRoles(),
                status.defaultRole());
    }

    private LdapDiagnosticResponse toResponse(LdapAdminDiagnosticService.LdapDiagnosticResult result) {
        return new LdapDiagnosticResponse(result.level(), result.title(), result.message());
    }
}
