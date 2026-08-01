package dev.subnetory.security;

import dev.subnetory.repository.RoleRepository;
import dev.subnetory.repository.UserRepository;
import dev.subnetory.service.LdapAdminDiagnosticService;
import dev.subnetory.service.LdapConfigurationService;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class DynamicLdapAuthenticationProvider implements AuthenticationProvider {

    private final LdapConfigurationService ldapConfigurationService;
    private final LdapAdminDiagnosticService ldapAdminDiagnosticService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public DynamicLdapAuthenticationProvider(LdapConfigurationService ldapConfigurationService,
                                             LdapAdminDiagnosticService ldapAdminDiagnosticService,
                                             UserRepository userRepository,
                                             RoleRepository roleRepository) {
        this.ldapConfigurationService = ldapConfigurationService;
        this.ldapAdminDiagnosticService = ldapAdminDiagnosticService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        var settings = ldapConfigurationService.effectiveSettings();
        if (!settings.enabled()) {
            return null;
        }
        String username = authentication.getName();
        String password = authentication.getCredentials() == null
                ? ""
                : authentication.getCredentials().toString();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return null;
        }

        try {
            LdapContextSource contextSource = ldapAdminDiagnosticService.contextSource(settings);
            FilterBasedLdapUserSearch userSearch = new FilterBasedLdapUserSearch(
                    settings.userSearchBase(),
                    settings.userSearchFilter(),
                    contextSource);
            BindAuthenticator authenticator = new BindAuthenticator(contextSource);
            authenticator.setUserSearch(userSearch);
            authenticator.afterPropertiesSet();

            var ldapUser = authenticator.authenticate(authentication);
            var mapper = new LdapUserProvisioningService(
                    userRepository,
                    roleRepository,
                    settings::defaultRoles);
            UserDetails userDetails = mapper.mapUserFromContext(ldapUser, username, List.of());
            return UsernamePasswordAuthenticationToken.authenticated(
                    userDetails,
                    authentication.getCredentials(),
                    userDetails.getAuthorities());
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new BadCredentialsException("LDAP authentication failed.", e);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
