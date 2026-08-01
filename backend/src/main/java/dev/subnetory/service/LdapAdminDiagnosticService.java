package dev.subnetory.service;

import javax.naming.directory.DirContext;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Diagnostics LDAP exposes a safe, admin-only view of LDAP connectivity.
 *
 * <p>No secret is returned by this service. User lookup accepts only a bounded
 * login value and relies on Spring LDAP filter parameters for escaping.</p>
 */
@Service
public class LdapAdminDiagnosticService {

    private static final int MAX_USERNAME_LENGTH = 128;

    private final LdapConfigurationService ldapConfigurationService;

    public LdapAdminDiagnosticService(LdapConfigurationService ldapConfigurationService) {
        this.ldapConfigurationService = ldapConfigurationService;
    }

    public LdapStatus status() {
        var settings = ldapConfigurationService.effectiveSettings();
        boolean managerConfigured = StringUtils.hasText(settings.managerDn())
                && StringUtils.hasText(settings.managerPassword());
        return new LdapStatus(
                settings.enabled(),
                settings.url(),
                settings.baseDn(),
                settings.userSearchBase(),
                settings.userSearchFilter(),
                StringUtils.hasText(settings.managerDn()),
                managerConfigured,
                settings.defaultRole(),
                settings.defaultRoles()
        );
    }

    public LdapDiagnosticResult testConnection() {
        var settings = ldapConfigurationService.effectiveSettings();
        if (!settings.enabled()) {
            return LdapDiagnosticResult.warning(
                    "Service LDAP inactif",
                    "La connexion LDAP n’est pas active sur cette instance.");
        }

        try {
            LdapContextSource contextSource = contextSource(settings);
            DirContext context = contextSource.getReadOnlyContext();
            context.close();
            return LdapDiagnosticResult.success(
                    "Connexion LDAP réussie",
                    "Le serveur LDAP répond avec la configuration actuelle.");
        } catch (Exception e) {
            return LdapDiagnosticResult.error(
                    "Connexion LDAP impossible",
                    safeMessage(e, settings));
        }
    }

    public LdapDiagnosticResult testUserSearch(String username) {
        var settings = ldapConfigurationService.effectiveSettings();
        if (!settings.enabled()) {
            return LdapDiagnosticResult.warning(
                    "Service LDAP inactif",
                    "La recherche LDAP n’est pas active sur cette instance.");
        }

        String safeUsername = normalizeUsername(username);
        if (!StringUtils.hasText(safeUsername)) {
            return LdapDiagnosticResult.error(
                    "Identifiant requis",
                    "Renseignez un identifiant utilisateur à rechercher.");
        }

        try {
            LdapContextSource contextSource = contextSource(settings);
            FilterBasedLdapUserSearch userSearch = new FilterBasedLdapUserSearch(
                    settings.userSearchBase(),
                    settings.userSearchFilter(),
                    contextSource);
            var user = userSearch.searchForUser(safeUsername);
            String dn = user.getDn() != null ? user.getDn().toString() : "DN non communiqué";
            return LdapDiagnosticResult.success(
                    "Utilisateur trouvé",
                    "Entrée LDAP : " + dn);
        } catch (Exception e) {
            return LdapDiagnosticResult.error(
                    "Utilisateur introuvable",
                    safeMessage(e, settings));
        }
    }

    public LdapContextSource contextSource(LdapConfigurationService.EffectiveLdapSettings settings) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(settings.url());
        source.setBase(settings.baseDn());
        if (StringUtils.hasText(settings.managerDn())) {
            source.setUserDn(settings.managerDn());
            source.setPassword(settings.managerPassword());
        }
        source.afterPropertiesSet();
        return source;
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return "";
        }
        String trimmed = username.trim();
        if (trimmed.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("Identifiant trop long.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("Identifiant invalide.");
            }
        }
        return trimmed;
    }

    private String safeMessage(Exception e, LdapConfigurationService.EffectiveLdapSettings settings) {
        String message = e.getMessage();
        if (!StringUtils.hasText(message)) {
            return e.getClass().getSimpleName();
        }
        if (StringUtils.hasText(settings.managerPassword())) {
            return message.replace(settings.managerPassword(), "********");
        }
        return message;
    }

    public record LdapStatus(
            boolean enabled,
            String url,
            String baseDn,
            String userSearchBase,
            String userSearchFilter,
            boolean managerDnConfigured,
            boolean managerPasswordConfigured,
            String defaultRole,
            java.util.List<String> defaultRoles
    ) {}

    public record LdapDiagnosticResult(
            String level,
            String title,
            String message
    ) {
        public static LdapDiagnosticResult success(String title, String message) {
            return new LdapDiagnosticResult("success", title, message);
        }

        public static LdapDiagnosticResult warning(String title, String message) {
            return new LdapDiagnosticResult("warning", title, message);
        }

        public static LdapDiagnosticResult error(String title, String message) {
            return new LdapDiagnosticResult("error", title, message);
        }
    }
}
