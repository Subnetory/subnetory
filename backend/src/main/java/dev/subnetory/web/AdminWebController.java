package dev.subnetory.web;

import dev.subnetory.domain.Role;
import dev.subnetory.exception.PasswordPolicyException;
import dev.subnetory.exception.ResourceNotFoundException;
import dev.subnetory.security.ClientIpResolver;
import dev.subnetory.service.AdminLockoutException;
import dev.subnetory.service.AuthAuditRetentionService;
import dev.subnetory.service.AuthAuditService;
import dev.subnetory.service.LdapAdminDiagnosticService;
import dev.subnetory.service.LdapConfigurationService;
import dev.subnetory.service.UserAdminService;
import dev.subnetory.service.UserTokenInvalidationService;
import dev.subnetory.web.form.UserRoleForm;
import dev.subnetory.web.form.UserContextForm;
import dev.subnetory.web.form.UserCreateForm;
import dev.subnetory.web.form.LdapSettingsForm;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Controller Web d'administration.
 *
 * Perimetre :
 * - liste utilisateurs ;
 * - detail utilisateur ;
 * - mise a jour roles ;
 * - activation / desactivation ;
 * - reset mot de passe admin ;
 * - consultation du journal d'audit auth.
 */
@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminWebController {

    private static final int PAGE_SIZE = 25;
    private static final int AUDIT_PAGE_SIZE = 50;
    private static final List<LdapFilterOption> LDAP_FILTER_OPTIONS = List.of(
            new LdapFilterOption("(sAMAccountName={0})", "sAMAccountName"),
            new LdapFilterOption("(userPrincipalName={0})", "userPrincipalName"),
            new LdapFilterOption("(mail={0})", "mail"),
            new LdapFilterOption("(uid={0})", "uid"),
            new LdapFilterOption("(cn={0})", "cn")
    );

    private final UserAdminService userAdminService;
    private final AuthAuditService authAuditService;
    private final AuthAuditRetentionService authAuditRetentionService;
    private final LdapAdminDiagnosticService ldapAdminDiagnosticService;
    private final LdapConfigurationService ldapConfigurationService;
    private final UserTokenInvalidationService userTokenInvalidationService;
    private final ClientIpResolver clientIpResolver;
    private final MessageSource messageSource;

    /**
     * Retention appliquee par la purge automatique planifiee
     * ({@code AuthAuditRetentionScheduler}) — relue ici uniquement pour
     * affichage informatif sur la page {@code /admin/audit-log} (audit
     * 01/08/2026, backlog #27) : "je ne peux pas le faire pour le moment,
     * ca grossit sans que je puisse rien faire" — la purge automatique
     * existait deja (90 jours par defaut) mais n'etait ni visible ni
     * declenchable manuellement depuis l'IHM.
     */
    @org.springframework.beans.factory.annotation.Value("${subnetory.audit.retention.days:90}")
    private int auditRetentionDays;

    @org.springframework.beans.factory.annotation.Value("${subnetory.audit.retention.enabled:true}")
    private boolean auditRetentionEnabled;

    public AdminWebController(UserAdminService userAdminService,
                              AuthAuditService authAuditService,
                              AuthAuditRetentionService authAuditRetentionService,
                              LdapAdminDiagnosticService ldapAdminDiagnosticService,
                              LdapConfigurationService ldapConfigurationService,
                              UserTokenInvalidationService userTokenInvalidationService,
                              ClientIpResolver clientIpResolver,
                              MessageSource messageSource) {
        this.userAdminService = userAdminService;
        this.authAuditService = authAuditService;
        this.authAuditRetentionService = authAuditRetentionService;
        this.ldapAdminDiagnosticService = ldapAdminDiagnosticService;
        this.ldapConfigurationService = ldapConfigurationService;
        this.userTokenInvalidationService = userTokenInvalidationService;
        this.clientIpResolver = clientIpResolver;
        this.messageSource = messageSource;
    }

    private String msg(String key, Locale locale, Object... args) {
        return messageSource.getMessage(key, args, locale);
    }

    // Liste des utilisateurs

    @GetMapping("/users")
    public String list(@RequestParam(defaultValue = "0") int page, Model model, Locale locale) {
        model.addAttribute("users",
                userAdminService.findAll(PageRequest.of(page, PAGE_SIZE, Sort.by("username"))));
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminUsers", locale));
        return "admin/users";
    }

    @GetMapping("/users/new")
    public String newUser(Model model, Locale locale) {
        model.addAttribute("userForm", new UserCreateForm());
        model.addAttribute("allRoles", userAdminService.findAssignableRoles());
        model.addAttribute("allContexts", userAdminService.findAllContexts());
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminUserNew", locale));
        return "admin/user-form";
    }

    @PostMapping("/users")
    public String createUser(@ModelAttribute("userForm") UserCreateForm form,
                             Authentication auth,
                             RedirectAttributes flash,
                             Model model,
                             Locale locale) {
        try {
            var user = userAdminService.createLocalUser(
                    form.getUsername(),
                    form.getEmail(),
                    form.getPassword(),
                    form.isEnabled(),
                    form.getRoleIds(),
                    form.getContextIds(),
                    auth.getName());
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.userCreated", locale));
            return "redirect:/admin/users/" + user.getId();
        } catch (PasswordPolicyException | AdminLockoutException | IllegalArgumentException e) {
            model.addAttribute("flashError", e.getMessage());
        } catch (ResourceNotFoundException e) {
            model.addAttribute("flashError", msg("flash.admin.roleOrContextNotFound", locale));
        }

        model.addAttribute("allRoles", userAdminService.findAssignableRoles());
        model.addAttribute("allContexts", userAdminService.findAllContexts());
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminUserNew", locale));
        return "admin/user-form";
    }

    // Detail d'un utilisateur

    @GetMapping("/users/{id}")
    public String detail(@PathVariable Long id,
                         Model model,
                         HttpServletResponse response,
                         Locale locale) {
        try {
            var user = userAdminService.findById(id);
            model.addAttribute("user", user);
            model.addAttribute("allRoles", userAdminService.findAssignableRoles());
            model.addAttribute("allContexts", userAdminService.findAllContexts());
            model.addAttribute("form", new UserRoleForm());
            model.addAttribute("contextForm", new UserContextForm());
            model.addAttribute("canManageApiTokens", true);

            Set<Long> userRoleIds = user.getRoles().stream()
                    .map(Role::getId)
                    .collect(Collectors.toSet());

            model.addAttribute("userRoleIds", userRoleIds);
            Set<Long> userContextIds = user.getAllowedContexts().stream()
                    .map(context -> context.getId())
                    .collect(Collectors.toSet());
            model.addAttribute("userContextIds", userContextIds);
            model.addAttribute("activeSection", "admin");
            model.addAttribute("pageTitle", msg("pageTitle.adminUserDetail", locale));
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        return "admin/user-detail";
    }

    // Journal d'audit auth

    @GetMapping("/audit-log")
    public String auditLog(@RequestParam(required = false) String q,
                           @RequestParam(required = false) String eventType,
                           @RequestParam(defaultValue = "0") int page,
                           Model model,
                           Locale locale) {
        int safePage = Math.max(0, page);

        model.addAttribute("logs",
                authAuditService.findAuditLogs(
                        q,
                        eventType,
                        PageRequest.of(safePage, AUDIT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"))));

        model.addAttribute("q", q);
        model.addAttribute("eventType", eventType);
        model.addAttribute("page", safePage);
        model.addAttribute("auditRetentionDays", auditRetentionDays);
        model.addAttribute("auditRetentionEnabled", auditRetentionEnabled);
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminAuditLog", locale));
        return "admin/audit-log";
    }

    /**
     * Purge manuelle immediate (audit 01/08/2026, backlog #27), complement
     * a la purge automatique planifiee ({@code AuthAuditRetentionScheduler}) —
     * meme pattern que {@code AdminBackupWebController#purge}.
     */
    @PostMapping("/audit-log/purge")
    public String purgeAuditLog(@RequestParam("beforeDate") java.time.LocalDate beforeDate,
                                RedirectAttributes flash,
                                Locale locale) {
        var cutoff = beforeDate.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        int deleted = authAuditRetentionService.purgeOlderThan(cutoff);
        flash.addFlashAttribute("flashSuccess",
                msg("flash.admin.auditPurged", locale, beforeDate, deleted));
        return "redirect:/admin/audit-log";
    }

    // Annuaire LDAP

    @GetMapping("/ldap")
    public String ldap(Model model, Locale locale) {
        LdapSettingsForm form = ldapConfigurationService.form();
        model.addAttribute("ldapStatus", ldapAdminDiagnosticService.status());
        model.addAttribute("ldapForm", form);
        model.addAttribute("allRoles", userAdminService.findAssignableRoles());
        model.addAttribute("ldapFilterOptions", LDAP_FILTER_OPTIONS);
        model.addAttribute("ldapCustomFilter", isCustomLdapFilter(form.getUserSearchFilter()));
        model.addAttribute("activeSection", "admin");
        model.addAttribute("pageTitle", msg("pageTitle.adminLdap", locale));
        return "admin/ldap";
    }

    @PostMapping("/ldap")
    public String updateLdap(@ModelAttribute("ldapForm") LdapSettingsForm form,
                             RedirectAttributes flash,
                             Model model,
                             Locale locale) {
        try {
            ldapConfigurationService.save(form);
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.ldapConfigSaved", locale));
            return "redirect:/admin/ldap";
        } catch (IllegalArgumentException | IllegalStateException e) {
            model.addAttribute("flashError", e.getMessage());
            model.addAttribute("ldapStatus", ldapAdminDiagnosticService.status());
            model.addAttribute("ldapForm", form);
            model.addAttribute("allRoles", userAdminService.findAssignableRoles());
            model.addAttribute("ldapFilterOptions", LDAP_FILTER_OPTIONS);
            model.addAttribute("ldapCustomFilter", isCustomLdapFilter(form.getUserSearchFilter()));
            model.addAttribute("activeSection", "admin");
            model.addAttribute("pageTitle", msg("pageTitle.adminLdap", locale));
            return "admin/ldap";
        }
    }

    @PostMapping("/ldap/test-connection")
    public String testLdapConnection(RedirectAttributes flash) {
        var result = ldapAdminDiagnosticService.testConnection();
        flash.addFlashAttribute("ldapDiagnostic", result);
        return "redirect:/admin/ldap";
    }

    @PostMapping("/ldap/test-user")
    public String testLdapUser(@RequestParam String username,
                               RedirectAttributes flash,
                               Locale locale) {
        try {
            var result = ldapAdminDiagnosticService.testUserSearch(username);
            flash.addFlashAttribute("ldapDiagnostic", result);
            flash.addFlashAttribute("ldapUsername", username);
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("ldapDiagnostic",
                    LdapAdminDiagnosticService.LdapDiagnosticResult.error(
                            msg("flash.admin.ldapSearchDenied", locale),
                            e.getMessage()));
        }
        return "redirect:/admin/ldap";
    }


    @GetMapping(value = "/audit-log/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportAuditLogCsv(@RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String eventType) {
        String csv = authAuditService.exportAuditLogsCsv(q, eventType);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"auth-audit-log.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
    // Mise a jour des roles

    @PostMapping("/users/{id}/roles")
    public String updateRoles(@PathVariable Long id,
                              @ModelAttribute("form") UserRoleForm form,
                              Authentication auth,
                              RedirectAttributes flash,
                              Locale locale) {
        try {
            userAdminService.updateRoles(id, form.getRoleIds(), auth.getName());
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.rolesUpdated", locale));
        } catch (AdminLockoutException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userOrRoleNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/users/{id}/contexts")
    public String updateContexts(@PathVariable Long id,
                                 @ModelAttribute("contextForm") UserContextForm form,
                                 Authentication auth,
                                 RedirectAttributes flash,
                                 Locale locale) {
        try {
            userAdminService.updateContexts(id, form.getContextIds(), auth.getName());
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.contextsUpdated", locale));
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userOrContextNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    // Activation

    @PostMapping("/users/{id}/enable")
    public String enable(@PathVariable Long id,
                         Authentication auth,
                         RedirectAttributes flash,
                         Locale locale) {
        try {
            userAdminService.setEnabled(id, true, auth.getName());
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.accountEnabled", locale));
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    // Desactivation

    @PostMapping("/users/{id}/disable")
    public String disable(@PathVariable Long id,
                          Authentication auth,
                          RedirectAttributes flash,
                          Locale locale) {
        try {
            userAdminService.setEnabled(id, false, auth.getName());
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.accountDisabled", locale));
        } catch (AdminLockoutException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    // Suppression definitive du compte

    @PostMapping("/users/{id}/delete")
    public String delete(@PathVariable Long id,
                         Authentication auth,
                         HttpServletRequest request,
                         RedirectAttributes flash,
                         Locale locale) {
        try {
            userAdminService.deleteUser(
                    id,
                    auth.getName(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"));
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.userDeleted", locale));
            return "redirect:/admin/users";
        } catch (AdminLockoutException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/admin/users/" + id;
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userNotFound", locale));
            return "redirect:/admin/users";
        }
    }

    // Desactivation MFA (anti-lockout admin)

    @PostMapping("/users/{id}/disable-mfa")
    public String disableMfa(@PathVariable Long id,
                             Authentication auth,
                             HttpServletRequest request,
                             RedirectAttributes flash,
                             Locale locale) {
        try {
            userAdminService.adminDisableMfa(
                    id,
                    auth.getName(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"));
            flash.addFlashAttribute("flashSuccess", msg("flash.admin.mfaDisabled", locale));
        } catch (PasswordPolicyException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    // Invalidation globale des tokens API

    @PostMapping("/users/{id}/invalidate-tokens")
    public String invalidateTokens(@PathVariable Long id,
                                   Authentication auth,
                                   HttpServletRequest request,
                                   RedirectAttributes flash,
                                   Locale locale) {
        try {
            var user = userAdminService.findById(id);
            String reason = UserTokenInvalidationService.REASON_ADMIN_REVOKE;

            userTokenInvalidationService.invalidateTokens(user.getUsername(), auth.getName(), reason);
            authAuditService.recordTokensInvalidated(
                    auth.getName(),
                    user.getUsername(),
                    clientIpResolver.resolve(request),
                    request.getHeader("User-Agent"),
                    reason);

            flash.addFlashAttribute("flashSuccess", msg("flash.admin.tokensRevoked", locale));
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    // Reset mot de passe

    @PostMapping("/users/{id}/reset-password")
    public String resetPassword(@PathVariable Long id,
                                @RequestParam String newPassword,
                                Authentication auth,
                                HttpServletRequest request,
                                RedirectAttributes flash,
                                Locale locale) {
        try {
            String ipAddress = clientIpResolver.resolve(request);
            String userAgent = request.getHeader("User-Agent");

            userAdminService.adminResetPassword(
                    id,
                    newPassword,
                    auth.getName(),
                    ipAddress,
                    userAgent);

            flash.addFlashAttribute("flashSuccess", msg("flash.admin.passwordReset", locale));
        } catch (PasswordPolicyException e) {
            flash.addFlashAttribute("flashError", e.getMessage());
        } catch (ResourceNotFoundException e) {
            flash.addFlashAttribute("flashError", msg("flash.admin.userNotFound", locale));
        }
        return "redirect:/admin/users/" + id;
    }

    private boolean isCustomLdapFilter(String filter) {
        return LDAP_FILTER_OPTIONS.stream()
                .noneMatch(option -> option.value().equals(filter));
    }

    private record LdapFilterOption(String value, String label) {}
}
