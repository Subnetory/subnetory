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
    /** Correctif securite MOYENNE (audit 04/08/2026) : voir {@link #invalidateAllTokens}. */
    public static final String REASON_POST_RESTORE = "POST_RESTORE";
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

    /**
     * Invalide les jetons JWT de TOUS les utilisateurs (correctif securite
     * MOYENNE, audit 04/08/2026) — appelee apres une restauration reussie de
     * la base. {@code user_token_invalidations} n'est pas exclue du
     * dump/restore de {@code BackupExecutionService} : une restauration peut
     * donc ramener cette table a un etat anterieur ou un jeton emis puis
     * revoque entre-temps redevient valide jusqu'a son expiration naturelle.
     * Appeler cette methode juste apres la restauration ecrit une ligne
     * fraiche (horodatee "maintenant") pour chaque utilisateur, qui prevaut
     * sur tout etat restaure plus ancien.
     */
    @Transactional
    public OffsetDateTime invalidateAllTokens(String invalidatedBy, String reason) {
        String actor = StringUtils.hasText(invalidatedBy) ? invalidatedBy : ACTOR_SYSTEM;
        String invalidationReason = StringUtils.hasText(reason) ? reason : REASON_POST_RESTORE;
        OffsetDateTime notBefore = OffsetDateTime.now(ZoneOffset.UTC);

        repository.upsertNotBeforeForAllUsers(notBefore, actor, invalidationReason);
        return notBefore;
    }
}
