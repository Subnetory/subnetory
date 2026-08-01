package dev.subnetory.service;

import dev.subnetory.domain.User;
import dev.subnetory.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Determine si un compte local doit remplacer son mot de passe
 * temporaire ou son mot de passe de bootstrap.
 */
@Service
public class MandatoryPasswordChangeService {

    public static final String REQUIRED_CHANGE_PATH =
            "/profile/change-password-required";

    private static final String AUTH_TYPE_LDAP = "LDAP";

    private final UserRepository userRepository;

    public MandatoryPasswordChangeService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public boolean isRequired(String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }

        return userRepository.findByUsername(username)
                .filter(User::isEnabled)
                .filter(user ->
                        !AUTH_TYPE_LDAP.equalsIgnoreCase(user.getAuthType()))
                .map(User::isMustChangePassword)
                .orElse(false);
    }
}
