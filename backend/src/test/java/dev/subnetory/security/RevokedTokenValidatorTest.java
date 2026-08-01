package dev.subnetory.security;

import dev.subnetory.repository.RevokedTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevokedTokenValidatorTest {

    @Mock
    RevokedTokenRepository repository;

    @Test
    void validate_tokenWithoutJti_isValidAndDoesNotHitRepository() {
        RevokedTokenValidator validator = new RevokedTokenValidator(repository);
        Jwt jwt = jwtWithoutJti();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
        verify(repository, never()).existsById(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void validate_tokenWithUnknownJti_isValid() {
        RevokedTokenValidator validator = new RevokedTokenValidator(repository);
        Jwt jwt = jwtWithJti("11111111-1111-1111-1111-111111111111");
        when(repository.existsById("11111111-1111-1111-1111-111111111111")).thenReturn(false);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertFalse(result.hasErrors());
    }

    @Test
    void validate_tokenWithRevokedJti_isInvalid() {
        RevokedTokenValidator validator = new RevokedTokenValidator(repository);
        Jwt jwt = jwtWithJti("22222222-2222-2222-2222-222222222222");
        when(repository.existsById("22222222-2222-2222-2222-222222222222")).thenReturn(true);

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertTrue(result.hasErrors());
    }

    private Jwt jwtWithoutJti() {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuer("subnetory")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject("admin")
                .claim("roles", java.util.List.of("ROLE_ADMIN"))
                .build();
    }

    private Jwt jwtWithJti(String jti) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuer("subnetory")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .subject("admin")
                .claim("jti", jti)
                .claim("roles", java.util.List.of("ROLE_ADMIN"))
                .build();
    }
}
