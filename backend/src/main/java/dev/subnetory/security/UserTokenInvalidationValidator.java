package dev.subnetory.security;

import dev.subnetory.repository.UserTokenInvalidationRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class UserTokenInvalidationValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error MISSING_SUBJECT = new OAuth2Error(
        "invalid_token",
        "The token subject is missing.",
        null);

    private static final OAuth2Error MISSING_IAT = new OAuth2Error(
        "invalid_token",
        "The token issued-at claim is missing while a user invalidation exists.",
        null);

    private static final OAuth2Error TOKEN_TOO_OLD = new OAuth2Error(
        "invalid_token",
        "The token was issued before the user token invalidation threshold.",
        null);

    private final UserTokenInvalidationRepository repository;

    public UserTokenInvalidationValidator(UserTokenInvalidationRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String username = token.getSubject();
        if (!StringUtils.hasText(username)) {
            return OAuth2TokenValidatorResult.failure(MISSING_SUBJECT);
        }

        Optional<Instant> notBefore = repository.findNotBeforeByUsername(username);
        if (notBefore.isEmpty()) {
            return OAuth2TokenValidatorResult.success();
        }

        Instant issuedAt = token.getIssuedAt();
        if (issuedAt == null) {
            return OAuth2TokenValidatorResult.failure(MISSING_IAT);
        }

        if (issuedAt.isBefore(notBefore.get())) {
            return OAuth2TokenValidatorResult.failure(TOKEN_TOO_OLD);
        }

        return OAuth2TokenValidatorResult.success();
    }
}
