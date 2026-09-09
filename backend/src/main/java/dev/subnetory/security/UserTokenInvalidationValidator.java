package dev.subnetory.security;

import dev.subnetory.repository.UserTokenInvalidationRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    private static final OAuth2Error INVALID_PRECISE_IAT = new OAuth2Error(
        "invalid_token",
        "The token precise issued-at claim is invalid.",
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

        // NumericDate tronque iat à la seconde : il est donc impossible de
        // distinguer deux jetons séparés par un logout-all dans la même seconde.
        // Les jetons Subnetory récents embarquent l'instant ISO précis. Pour les
        // anciens jetons, la comparaison stricte conserve le comportement sûr :
        // ils restent refusés jusqu'à la seconde suivante.
        Instant effectiveIssuedAt = issuedAt;
        String preciseIssuedAt = token.getClaimAsString(JwtTokenService.PRECISE_ISSUED_AT_CLAIM);
        if (StringUtils.hasText(preciseIssuedAt)) {
            try {
                effectiveIssuedAt = Instant.parse(preciseIssuedAt);
            } catch (DateTimeParseException e) {
                return OAuth2TokenValidatorResult.failure(INVALID_PRECISE_IAT);
            }
        }

        if (effectiveIssuedAt.isBefore(notBefore.get())) {
            return OAuth2TokenValidatorResult.failure(TOKEN_TOO_OLD);
        }

        return OAuth2TokenValidatorResult.success();
    }
}
