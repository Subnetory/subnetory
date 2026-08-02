package dev.subnetory.web;

import dev.subnetory.config.SecurityConfig;
import dev.subnetory.domain.NetworkContext;
import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.exception.PasswordPolicyException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.security.ApiRateLimiter;
import dev.subnetory.security.LoginRateLimiter;
import dev.subnetory.security.RateLimitingAuthenticationFailureHandler;
import dev.subnetory.security.RateLimitingAuthenticationSuccessHandler;
import dev.subnetory.security.SubnetoryUserDetailsService;
import dev.subnetory.service.AdminLockoutException;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.LdapAdminDiagnosticService;
import dev.subnetory.service.LdapConfigurationService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.service.UserTokenInvalidationService;
import dev.subnetory.web.form.LdapSettingsForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminWebController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class AdminWebControllerIT {

    @Autowired MockMvc mvc;

    @MockitoBean UserAdminService userAdminService;
    @MockitoBean AuthAuditService authAuditService;
    @MockitoBean dev.subnetory.service.AuthAuditRetentionService authAuditRetentionService;
    @MockitoBean LdapAdminDiagnosticService ldapAdminDiagnosticService;
    @MockitoBean LdapConfigurationService ldapConfigurationService;
    @MockitoBean UserTokenInvalidationService userTokenInvalidationService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean SubnetoryUserDetailsService userDetailsService;

    // Beans ajoutes par Sprint 2.13 / T4.
    // Necessaires ici car @WebMvcTest ne charge pas tout le contexte applicatif.
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean ApiRateLimiter apiRateLimiter;
    @MockitoBean ClientIpResolver clientIpResolver;
    @MockitoBean RateLimitingAuthenticationFailureHandler failureHandler;
    @MockitoBean RateLimitingAuthenticationSuccessHandler successHandler;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        Role roleAdmin = new Role("ROLE_ADMIN");
        roleAdmin.setId(1L);
        Role roleReadOnly = new Role("ROLE_READ_ONLY");
        roleReadOnly.setId(4L);
        Role roleNetwork = new Role("ROLE_NETWORK");
        roleNetwork.setId(2L);
        Role roleIp = new Role("ROLE_IP");
        roleIp.setId(3L);
        NetworkContext contextProduction = new NetworkContext();
        contextProduction.setId(1L);
        contextProduction.setName("PRODUCTION");
        NetworkContext contextClient = new NetworkContext();
        contextClient.setId(2L);
        contextClient.setName("CLIENT-A");

        sampleUser = new User();
        sampleUser.setId(10L);
        sampleUser.setUsername("admin");
        sampleUser.setAuthType("LOCAL");
        sampleUser.setEnabled(true);
        sampleUser.setRoles(new HashSet<>(Set.of(roleAdmin)));

        when(userAdminService.findAll(any(Pageable.class))).thenReturn(Page.empty());
        when(userAdminService.findAllRoles()).thenReturn(List.of(roleAdmin, roleReadOnly, roleNetwork, roleIp));
        when(userAdminService.findAssignableRoles()).thenReturn(List.of(roleAdmin, roleReadOnly, roleNetwork, roleIp));
        when(userAdminService.findAllContexts()).thenReturn(List.of(contextProduction, contextClient));
        when(authAuditService.findAuditLogs(any(), any(), any(Pageable.class))).thenReturn(Page.empty());
        when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
        when(ldapAdminDiagnosticService.status()).thenReturn(new LdapAdminDiagnosticService.LdapStatus(
                false,
                "ldap://localhost:389",
                "dc=example,dc=com",
                "ou=users",
                "(sAMAccountName={0})",
                false,
                false,
                "ROLE_IP",
                List.of("ROLE_IP")));
        when(ldapConfigurationService.form()).thenReturn(new LdapSettingsForm());
    }

    // Acces selon role

    @Test
    void anonymous_list_redirectsToLogin() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "IP")
    void roleIp_list_returns403() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "NETWORK")
    void roleNetwork_list_returns403() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_list_returns200() throws Exception {
        mvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"));
    }

    // Creation

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_newUser_returns200() throws Exception {
        mvc.perform(get("/admin/users/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-form"))
                .andExpect(content().string(containsString("Nouvel utilisateur")))
                .andExpect(content().string(containsString("Tout sélectionner")))
                .andExpect(content().string(containsString("data-check-all=\"#user-create-contexts\"")));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void createUser_withCsrf_redirectsToCreatedUser() throws Exception {
        User created = new User();
        created.setId(42L);
        created.setUsername("client.viewer");
        when(userAdminService.createLocalUser(
                anyString(), any(), anyString(), anyBoolean(), any(), any(), anyString()))
                .thenReturn(created);

        mvc.perform(post("/admin/users")
                        .with(csrf())
                        .param("username", "client.viewer")
                        .param("email", "client.viewer@example.com")
                        .param("password", "ValidPass123!")
                        .param("enabled", "true")
                        .param("roleIds", "3")
                        .param("contextIds", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/42"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(userAdminService).createLocalUser(
                eq("client.viewer"),
                eq("client.viewer@example.com"),
                eq("ValidPass123!"),
                eq(true),
                eq(Set.of(3L)),
                eq(Set.of(1L)),
                eq("admin"));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void createUser_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users")
                        .param("username", "client.viewer")
                        .param("password", "ValidPass123!")
                        .param("roleIds", "3"))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).createLocalUser(
                anyString(), any(), anyString(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void createUser_policyViolation_returnsFormWithError() throws Exception {
        when(userAdminService.createLocalUser(
                anyString(), any(), anyString(), anyBoolean(), any(), any(), anyString()))
                .thenThrow(new PasswordPolicyException("Mot de passe non conforme."));

        mvc.perform(post("/admin/users")
                        .with(csrf())
                        .param("username", "client.viewer")
                        .param("password", "weak")
                        .param("roleIds", "3"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-form"))
                .andExpect(content().string(containsString("Mot de passe non conforme.")));
    }

    // Detail

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_detail_existingUser_returns200() throws Exception {
        when(userAdminService.findById(10L)).thenReturn(sampleUser);

        mvc.perform(get("/admin/users/10"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-detail"))
                .andExpect(content().string(containsString("Tout sélectionner")))
                .andExpect(content().string(containsString("data-check-all=\"#user-detail-contexts\"")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_detail_unknownUser_returns404() throws Exception {
        when(userAdminService.findById(999L))
                .thenThrow(new ResourceNotFoundException("User", 999L));

        mvc.perform(get("/admin/users/999"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/404"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void detail_doesNotContainPasswordHash() throws Exception {
        when(userAdminService.findById(10L)).thenReturn(sampleUser);

        mvc.perform(get("/admin/users/10"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("$2a$"))))
                .andExpect(content().string(not(containsString("$2b$"))))
                .andExpect(content().string(not(containsString("$2y$"))));
    }

    // Journal d'audit auth

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_auditLog_returns200() throws Exception {
        mvc.perform(get("/admin/audit-log"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit-log"))
                .andExpect(content().string(containsString("sn-audit-filters__grid")))
                // Thymeleaf th:text échappe l'apostrophe en entité HTML (&#39;).
                .andExpect(content().string(containsString("Type d&#39;événement")))
                .andExpect(content().string(containsString("Réinitialiser")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void roleAdmin_ldap_returnsConfigurationForm() throws Exception {
        mvc.perform(get("/admin/ldap"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/ldap"))
                .andExpect(content().string(containsString("sn-ldap-summary")))
                .andExpect(content().string(containsString("Connexion annuaire")))
                .andExpect(content().string(containsString("Rôles par défaut")))
                .andExpect(content().string(containsString("Configuration")))
                .andExpect(content().string(containsString("Enregistrer la configuration")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateLdap_withCsrf_redirectsWithFlashSuccess() throws Exception {
        mvc.perform(post("/admin/ldap")
                        .with(csrf())
                        .param("enabled", "true")
                        .param("url", "ldaps://ldap.example.com:636")
                        .param("baseDn", "dc=example,dc=com")
                        .param("userSearchBase", "ou=users")
                        .param("userSearchFilter", "(sAMAccountName={0})")
                        .param("managerDn", "cn=subnetory,dc=example,dc=com")
                        .param("managerPassword", "SecretPass123!")
                        .param("defaultRoles", "ROLE_NETWORK", "ROLE_IP"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/ldap"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(ldapConfigurationService).save(any());
    }

    // Mise a jour des roles

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void updateRoles_withCsrf_redirectsWithFlashSuccess() throws Exception {
        when(userAdminService.updateRoles(anyLong(), any(), anyString())).thenReturn(sampleUser);

        mvc.perform(post("/admin/users/10/roles")
                        .with(csrf())
                        .param("roleIds", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void updateRoles_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/roles")
                        .param("roleIds", "1"))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).updateRoles(any(), any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void updateRoles_lockoutViolation_redirectsWithFlashError() throws Exception {
        when(userAdminService.updateRoles(anyLong(), any(), anyString()))
                .thenThrow(new AdminLockoutException(
                        "Impossible de retirer le role ADMIN au dernier administrateur actif."));

        mvc.perform(post("/admin/users/10/roles")
                        .with(csrf())
                        .param("roleIds", "2"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    void anonymous_updateRoles_redirectsToLogin() throws Exception {
        mvc.perform(post("/admin/users/10/roles")
                        .with(csrf())
                        .param("roleIds", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // Desactivation

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void disable_withCsrf_redirectsWithFlashSuccess() throws Exception {
        when(userAdminService.setEnabled(anyLong(), anyBoolean(), anyString()))
                .thenReturn(sampleUser);

        mvc.perform(post("/admin/users/10/disable").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disable_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/disable"))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).setEnabled(anyLong(), anyBoolean(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void disable_lastAdmin_redirectsWithFlashError() throws Exception {
        when(userAdminService.setEnabled(anyLong(), anyBoolean(), anyString()))
                .thenThrow(new AdminLockoutException(
                        "Impossible de desactiver le dernier administrateur actif."));

        mvc.perform(post("/admin/users/10/disable").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // Activation

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void enable_withCsrf_redirectsWithFlashSuccess() throws Exception {
        when(userAdminService.setEnabled(anyLong(), anyBoolean(), anyString()))
                .thenReturn(sampleUser);

        mvc.perform(post("/admin/users/10/enable").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashSuccess"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void enable_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/enable"))
                .andExpect(status().isForbidden());
    }

    // Invalidation tokens API

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void invalidateTokens_withCsrf_redirectsWithFlashSuccess() throws Exception {
        when(userAdminService.findById(10L)).thenReturn(sampleUser);

        mvc.perform(post("/admin/users/10/invalidate-tokens")
                        .with(csrf())
                        .header("User-Agent", "JUnit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(userTokenInvalidationService).invalidateTokens(
                eq("admin"),
                eq("admin"),
                eq(UserTokenInvalidationService.REASON_ADMIN_REVOKE));
        verify(authAuditService).recordTokensInvalidated(
                eq("admin"),
                eq("admin"),
                eq("127.0.0.1"),
                eq("JUnit"),
                eq(UserTokenInvalidationService.REASON_ADMIN_REVOKE));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidateTokens_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/invalidate-tokens"))
                .andExpect(status().isForbidden());

        verify(userTokenInvalidationService, never()).invalidateTokens(anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "IP")
    void invalidateTokens_withoutAdminRole_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/invalidate-tokens").with(csrf()))
                .andExpect(status().isForbidden());

        verify(userTokenInvalidationService, never()).invalidateTokens(anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void invalidateTokens_unknownUser_redirectsWithFlashError() throws Exception {
        when(userAdminService.findById(999L))
                .thenThrow(new ResourceNotFoundException("User", 999L));

        mvc.perform(post("/admin/users/999/invalidate-tokens").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/999"))
                .andExpect(flash().attributeExists("flashError"));

        verify(userTokenInvalidationService, never()).invalidateTokens(anyString(), anyString(), anyString());
    }

    // Reset mot de passe admin

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void resetPassword_withCsrf_redirectsWithFlashSuccess() throws Exception {
        mvc.perform(post("/admin/users/10/reset-password")
                        .with(csrf())
                        .header("User-Agent", "JUnit")
                        .param("newPassword", "ValidPass123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(userAdminService).adminResetPassword(
                eq(10L),
                eq("ValidPass123!"),
                eq("admin"),
                eq("127.0.0.1"),
                eq("JUnit"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void resetPassword_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/reset-password")
                        .param("newPassword", "ValidPass123!"))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).adminResetPassword(
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void resetPassword_policyViolation_redirectsWithFlashError() throws Exception {
        doThrow(new PasswordPolicyException("Le mot de passe doit contenir au moins 12 caracteres."))
                .when(userAdminService).adminResetPassword(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString());

        mvc.perform(post("/admin/users/10/reset-password")
                        .with(csrf())
                        .header("User-Agent", "JUnit")
                        .param("newPassword", "weak"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashError"));
    }

    // Desactivation MFA admin (anti-lockout, Sprint 2.37 / Lot 4)

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void disableMfa_withCsrf_redirectsWithFlashSuccess() throws Exception {
        mvc.perform(post("/admin/users/10/disable-mfa")
                        .with(csrf())
                        .header("User-Agent", "JUnit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashSuccess"));

        verify(userAdminService).adminDisableMfa(
                eq(10L),
                eq("admin"),
                eq("127.0.0.1"),
                eq("JUnit"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disableMfa_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/disable-mfa"))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).adminDisableMfa(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "IP")
    void disableMfa_withoutAdminRole_returns403() throws Exception {
        mvc.perform(post("/admin/users/10/disable-mfa").with(csrf()))
                .andExpect(status().isForbidden());

        verify(userAdminService, never()).adminDisableMfa(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void disableMfa_unknownUser_redirectsWithFlashError() throws Exception {
        doThrow(new ResourceNotFoundException("User", 999L))
                .when(userAdminService).adminDisableMfa(eq(999L), anyString(), anyString(), any());

        mvc.perform(post("/admin/users/999/disable-mfa")
                        .with(csrf())
                        .header("User-Agent", "JUnit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/999"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN", username = "admin")
    void disableMfa_notEnabled_redirectsWithFlashError() throws Exception {
        doThrow(new PasswordPolicyException("Le MFA n'est pas active sur ce compte."))
                .when(userAdminService).adminDisableMfa(eq(10L), anyString(), anyString(), anyString());

        mvc.perform(post("/admin/users/10/disable-mfa")
                        .with(csrf())
                        .header("User-Agent", "JUnit"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users/10"))
                .andExpect(flash().attributeExists("flashError"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void auditLog_negativePage_normalizesToZeroAndSortsByCreatedAtDesc() throws Exception {
        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> pageableCaptor =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        mvc.perform(get("/admin/audit-log").param("page", "-5"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit-log"));

        org.mockito.Mockito.verify(authAuditService).findAuditLogs(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                pageableCaptor.capture());

        org.springframework.data.domain.Pageable pageable = pageableCaptor.getValue();
        org.springframework.data.domain.Sort.Order order = pageable.getSort().getOrderFor("createdAt");

        org.junit.jupiter.api.Assertions.assertEquals(0, pageable.getPageNumber());
        org.junit.jupiter.api.Assertions.assertEquals(50, pageable.getPageSize());
        org.junit.jupiter.api.Assertions.assertNotNull(order);
        org.junit.jupiter.api.Assertions.assertEquals(org.springframework.data.domain.Sort.Direction.DESC, order.getDirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void auditLogExportCsv_returnsAttachment() throws Exception {
        org.mockito.Mockito.when(authAuditService.exportAuditLogsCsv(null, null))
                .thenReturn("createdAt,eventType\r\n");

        mvc.perform(get("/admin/audit-log/export.csv"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"auth-audit-log.csv\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("createdAt")));

        org.mockito.Mockito.verify(authAuditService).exportAuditLogsCsv(null, null);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void purgeAuditLog_withCsrf_redirectsWithFlashSuccess() throws Exception {
        org.mockito.Mockito.when(authAuditRetentionService.purgeOlderThan(org.mockito.ArgumentMatchers.any()))
                .thenReturn(3);

        mvc.perform(post("/admin/audit-log/purge").with(csrf()).param("beforeDate", "2026-01-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/audit-log"));

        org.mockito.Mockito.verify(authAuditRetentionService).purgeOlderThan(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void purgeAuditLog_withoutCsrf_returns403() throws Exception {
        mvc.perform(post("/admin/audit-log/purge").param("beforeDate", "2026-01-01"))
                .andExpect(status().isForbidden());
    }
}
