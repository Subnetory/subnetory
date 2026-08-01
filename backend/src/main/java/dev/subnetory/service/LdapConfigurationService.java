package dev.subnetory.service;

import dev.subnetory.config.LdapProperties;
import dev.subnetory.domain.LdapSettings;
import dev.subnetory.repository.LdapSettingsRepository;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.security.AssignableRoles;
import dev.subnetory.web.form.LdapSettingsForm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class LdapConfigurationService {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("ldap", "ldaps");
    private static final int MAX_FIELD_LENGTH = 512;

    private final LdapProperties fallbackProperties;
    private final LdapSettingsRepository repository;
    private final RoleRepository roleRepository;
    private final SecretCipherService secretCipherService;

    public LdapConfigurationService(LdapProperties fallbackProperties,
                                    LdapSettingsRepository repository,
                                    RoleRepository roleRepository,
                                    SecretCipherService secretCipherService) {
        this.fallbackProperties = fallbackProperties;
        this.repository = repository;
        this.roleRepository = roleRepository;
        this.secretCipherService = secretCipherService;
    }

    @Transactional(readOnly = true)
    public EffectiveLdapSettings effectiveSettings() {
        return repository.findById(LdapSettings.SINGLETON_ID)
                .map(this::fromEntity)
                .orElseGet(this::fromProperties);
    }

    @Transactional(readOnly = true)
    public LdapSettingsForm form() {
        EffectiveLdapSettings settings = effectiveSettings();
        LdapSettingsForm form = new LdapSettingsForm();
        form.setEnabled(settings.enabled());
        form.setUrl(settings.url());
        form.setBaseDn(settings.baseDn());
        form.setUserSearchBase(settings.userSearchBase());
        form.setUserSearchFilter(settings.userSearchFilter());
        form.setManagerDn(settings.managerDn());
        form.setDefaultRoles(new LinkedHashSet<>(settings.defaultRoles()));
        return form;
    }

    public void save(LdapSettingsForm form) {
        String url = required(form.getUrl(), "URL LDAP");
        validateUrl(url);
        String baseDn = required(form.getBaseDn(), "Base DN");
        String searchBase = required(form.getUserSearchBase(), "Base de recherche utilisateur");
        String searchFilter = required(form.getUserSearchFilter(), "Filtre utilisateur");
        if (!searchFilter.contains("{0}")) {
            throw new IllegalArgumentException("Le filtre utilisateur doit contenir le paramètre {0}.");
        }
        List<String> defaultRoles = validateDefaultRoles(form.getDefaultRoles());

        Optional<LdapSettings> existingSettings = repository.findById(LdapSettings.SINGLETON_ID);
        LdapSettings settings = existingSettings
                .orElseGet(() -> {
                    LdapSettings created = new LdapSettings();
                    created.setId(LdapSettings.SINGLETON_ID);
                    return created;
                });
        settings.setEnabled(form.isEnabled());
        settings.setUrl(url);
        settings.setBaseDn(baseDn);
        settings.setUserSearchBase(searchBase);
        settings.setUserSearchFilter(searchFilter);
        settings.setManagerDn(optional(form.getManagerDn()));
        settings.setDefaultRole(serializeRoles(defaultRoles));

        if (form.isClearManagerPassword()) {
            settings.setManagerPasswordEncrypted(null);
        } else if (StringUtils.hasText(form.getManagerPassword())) {
            settings.setManagerPasswordEncrypted(secretCipherService.encrypt(form.getManagerPassword().trim()));
        } else if (existingSettings.isEmpty() && StringUtils.hasText(fallbackProperties.getManagerPassword())) {
            settings.setManagerPasswordEncrypted(secretCipherService.encrypt(fallbackProperties.getManagerPassword().trim()));
        }

        repository.save(settings);
    }

    private EffectiveLdapSettings fromEntity(LdapSettings settings) {
        return new EffectiveLdapSettings(
                settings.isEnabled(),
                settings.getUrl(),
                settings.getBaseDn(),
                settings.getUserSearchBase(),
                settings.getUserSearchFilter(),
                optional(settings.getManagerDn()),
                secretCipherService.decrypt(settings.getManagerPasswordEncrypted()),
                parseRoles(settings.getDefaultRole())
        );
    }

    private EffectiveLdapSettings fromProperties() {
        return new EffectiveLdapSettings(
                fallbackProperties.isEnabled(),
                fallbackProperties.getUrl(),
                fallbackProperties.getBaseDn(),
                fallbackProperties.getUserSearchBase(),
                fallbackProperties.getUserSearchFilter(),
                optional(fallbackProperties.getManagerDn()),
                optional(fallbackProperties.getManagerPassword()),
                parseRoles(fallbackProperties.getDefaultRole())
        );
    }

    private List<String> validateDefaultRoles(Set<String> roleNames) {
        List<String> roles = roleNames == null ? List.of() : roleNames.stream()
                .map(this::optional)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("Sélectionnez au moins un rôle LDAP par défaut.");
        }
        for (String role : roles) {
            if (!AssignableRoles.contains(role)) {
                throw new IllegalArgumentException("Ce rôle n'est pas attribuable dans Subnetory.");
            }
            if (roleRepository.findByName(role).isEmpty()) {
                throw new IllegalArgumentException("Un rôle LDAP sélectionné n'existe pas.");
            }
        }
        List<String> ordered = AssignableRoles.orderedNames().stream()
                .filter(roles::contains)
                .toList();
        return ordered.isEmpty() ? List.of(AssignableRoles.IP) : ordered;
    }

    private List<String> parseRoles(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of(AssignableRoles.IP);
        }
        List<String> roles = java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return AssignableRoles.orderedNames().stream()
                .filter(roles::contains)
                .toList();
    }

    private String serializeRoles(List<String> roles) {
        return roles.stream().collect(Collectors.joining(","));
    }

    private String required(String value, String label) {
        String trimmed = optional(value);
        if (!StringUtils.hasText(trimmed)) {
            throw new IllegalArgumentException(label + " obligatoire.");
        }
        if (trimmed.length() > MAX_FIELD_LENGTH || containsControl(trimmed)) {
            throw new IllegalArgumentException(label + " invalide.");
        }
        return trimmed;
    }

    private String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!ALLOWED_SCHEMES.contains(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException("URL LDAP invalide.");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL LDAP invalide.");
        }
    }

    public record EffectiveLdapSettings(
            boolean enabled,
            String url,
            String baseDn,
            String userSearchBase,
            String userSearchFilter,
            String managerDn,
            String managerPassword,
            List<String> defaultRoles
    ) {
        public String defaultRole() {
            return defaultRoles == null || defaultRoles.isEmpty() ? AssignableRoles.IP : defaultRoles.getFirst();
        }
    }
}
