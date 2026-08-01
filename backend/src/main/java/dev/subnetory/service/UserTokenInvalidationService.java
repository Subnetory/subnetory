package dev.subnetory.service;

import dev.subnetory.repository.UserTokenInvalidationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class UserTokenInvalidationService {

    public static final String REASON_LOGOUT_ALL = "LOGOUT_ALL";
    public static final String REASON_ADMIN_REVOKE = "ADMIN_REVOKE";
    public static final String REASON_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    public static final String REASON_AUTHORIZATION_CHANGE = "AUTHORIZATION_CHANGE";
    public static final String ACTOR_SYSTEM = "SYSTEM";

    private final UserTokenInvalidationRepository repository;

    public UserTokenInvalidationService(UserTokenInvalidationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OffsetDateTime invalidateTokens(String username, String invalidatedBy, String reason) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("username must not be blank");
        }

        String actor = StringUtils.hasText(invalidatedBy) ? invalidatedBy : ACTOR_SYSTEM;
        String invalidationReason = StringUtils.hasText(reason) ? reason : REASON_ADMIN_REVOKE;
        OffsetDateTime notBefore = OffsetDateTime.now(ZoneOffset.UTC);

        repository.upsertNotBefore(username, notBefore, actor, invalidationReason);
        return notBefore;
    }
}
