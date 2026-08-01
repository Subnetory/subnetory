package dev.subnetory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.subnetory.repository.UserTokenInvalidationRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class UserTokenInvalidationValidatorTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-07-07T15:00:00Z");

    @Mock
    private UserTokenInvalidationRepository repository;

    @InjectMocks
    private UserTokenInvalidationValidator validator;

    @Test
    void acceptsTokenWhenNoInvalidationExists() {
        Jwt token = jwt("alice", ISSUED_AT);
        when(repository.findNotBeforeByUsername("alice")).thenReturn(Optional.empty());

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenWithoutSubject() {
        Jwt token = jwt(null, ISSUED_AT);

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsTokenWithoutIssuedAtWhenInvalidationExists() {
        Jwt token = jwt("alice", null);
        when(repository.findNotBeforeByUsername("alice"))
                .thenReturn(Optional.of(ISSUED_AT));

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenIssuedBeforeInvalidationThreshold() {
        Jwt token = jwt("alice", ISSUED_AT);
        when(repository.findNotBeforeByUsername("alice"))
                .thenReturn(Optional.of(ISSUED_AT.plusSeconds(1)));

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void acceptsTokenIssuedAtSameInstantAsInvalidationThreshold() {
        Jwt token = jwt("alice", ISSUED_AT);
        when(repository.findNotBeforeByUsername("alice"))
                .thenReturn(Optional.of(ISSUED_AT));

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void acceptsTokenIssuedAfterInvalidationThreshold() {
        Jwt token = jwt("alice", ISSUED_AT.plusSeconds(2));
        when(repository.findNotBeforeByUsername("alice"))
                .thenReturn(Optional.of(ISSUED_AT));

        OAuth2TokenValidatorResult result = validator.validate(token);

        assertThat(result.hasErrors()).isFalse();
    }

    private static Jwt jwt(String subject, Instant issuedAt) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .expiresAt(ISSUED_AT.plusSeconds(3600));

        if (subject != null) {
            builder.subject(subject);
        }

        if (issuedAt != null) {
            builder.issuedAt(issuedAt);
        }

        return builder.build();
    }
}
