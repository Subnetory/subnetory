package dev.subnetory.security;

import dev.subnetory.repository.RevokedTokenRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validateur JWT qui refuse les tokens explicitement revoques.
 *
 * <p>Les tokens historiques sans claim jti restent acceptes jusqu'a leur
 * expiration naturelle : ils ne sont pas revocables, mais la fenetre est bornee
 * par subnetory.jwt.expiration-minutes.</p>
 */
@Component
public class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED_TOKEN_ERROR = new OAuth2Error(
            "invalid_token",
            "The token has been revoked.",
            null
    );

    private final RevokedTokenRepository repository;

    public RevokedTokenValidator(RevokedTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String jti = token.getId();

        if (!StringUtils.hasText(jti)) {
            return OAuth2TokenValidatorResult.success();
        }

        if (repository.existsById(jti)) {
            return OAuth2TokenValidatorResult.failure(REVOKED_TOKEN_ERROR);
        }

        return OAuth2TokenValidatorResult.success();
    }
}
