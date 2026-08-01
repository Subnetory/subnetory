package dev.subnetory.security;

import dev.subnetory.config.LdapProperties;
import dev.subnetory.domain.Role;
import dev.subnetory.domain.User;
import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Mappe le contexte LDAP vers un UserDetails Subnetory.
 *
 * Appelé par LdapAuthenticationProvider après un bind LDAP réussi.
 * Responsabilités :
 *  - chercher l'utilisateur en base par son username ;
 *  - le créer s'il est inconnu (auto-provisioning, auth_type=LDAP, password=null) ;
 *  - refuser si un compte LOCAL existe avec le même username (anti-collision) ;
 *  - charger les rôles existants sans les écraser lors des connexions suivantes.
 *
 * Concurrence : deux premières connexions simultanées du même utilisateur LDAP.
 * saveAndFlush() force le flush immédiat → DataIntegrityViolationException si collision.
 * noRollbackFor garantit que la transaction reste active après l'exception.
 * Le thread perdant relit l'utilisateur créé par le thread gagnant.
 */
public class LdapUserProvisioningService implements UserDetailsContextMapper {

    private static final Logger log = LoggerFactory.getLogger(LdapUserProvisioningService.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final Supplier<List<String>> defaultRolesSupplier;

    public LdapUserProvisioningService(UserRepository userRepository,
                                       RoleRepository roleRepository,
                                       LdapProperties ldapProperties) {
        this(userRepository, roleRepository, () -> List.of(ldapProperties.getDefaultRole()));
    }

    public LdapUserProvisioningService(UserRepository userRepository,
                                       RoleRepository roleRepository,
                                       Supplier<List<String>> defaultRolesSupplier) {
        this.userRepository  = userRepository;
        this.roleRepository  = roleRepository;
        this.defaultRolesSupplier = defaultRolesSupplier;
    }

    @Override
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public UserDetails mapUserFromContext(DirContextOperations ctx,
                                          String username,
                                          Collection<? extends GrantedAuthority> authorities) {
        User user = userRepository.findByUsername(username)
                .orElseGet(() -> provisionNewLdapUser(username));

        if ("LOCAL".equals(user.getAuthType())) {
            log.warn("LDAP login blocked for LOCAL account '{}'", username);
            throw new BadCredentialsException(
                    "Account '" + username + "' is configured for local authentication. "
                    + "LDAP login is not permitted for this account.");
        }

        if (!user.isEnabled()) {
            throw new DisabledException("Account '" + username + "' is disabled.");
        }

        Set<SimpleGrantedAuthority> grantedAuthorities = user.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority(r.getName()))
                .collect(Collectors.toSet());

        log.debug("LDAP authentication successful for '{}' — roles: {}", username, grantedAuthorities);

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getUsername())
                .password("")
                .authorities(grantedAuthorities)
                .disabled(!user.isEnabled())
                .build();
    }

    @Override
    public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
        throw new UnsupportedOperationException(
                "Writing to LDAP directory is not supported by Subnetory.");
    }

    // ── Provisioning ───────────────────────────────────────────────────────

    private User provisionNewLdapUser(String username) {
        List<String> defaultRoleNames = defaultRolesSupplier.get();
        if (defaultRoleNames == null || defaultRoleNames.isEmpty()) {
            throw new IllegalStateException("No default LDAP role configured.");
        }

        User user = new User();
        user.setUsername(username);
        user.setPassword(null);
        user.setAuthType("LDAP");
        user.setEnabled(true);
        for (String roleName : defaultRoleNames) {
            Role role = roleRepository.findByName(roleName)
                    .orElseThrow(() -> new IllegalStateException(
                            "Default LDAP role '" + roleName + "' not found. "
                                    + "Ensure V2 migration has seeded roles before enabling LDAP."));
            user.getRoles().add(role);
        }

        try {
            // saveAndFlush force le flush immédiat pour détecter une violation UNIQUE
            // avant la fin de la transaction — nécessaire pour le catch ci-dessous.
            User saved = userRepository.saveAndFlush(user);
            log.info("Auto-provisioned LDAP user '{}' with roles '{}'",
                    username, defaultRoleNames);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Collision concurrente : un autre thread a créé le même utilisateur entre
            // notre SELECT (vide) et notre INSERT. La contrainte UNIQUE a protégé la base.
            // On relit l'utilisateur créé par le thread gagnant.
            log.debug("Concurrent provisioning detected for '{}', re-reading from DB", username);
            return userRepository.findByUsername(username)
                    .filter(u -> "LDAP".equals(u.getAuthType()))
                    .orElseThrow(() -> new BadCredentialsException(
                            "Account '" + username + "' exists with conflicting authentication type."));
        }
    }
}
