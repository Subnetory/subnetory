package dev.subnetory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * Drainage des sessions Web actives (correctif securite MOYENNE, audit
 * 04/08/2026), appelee apres une restauration reussie de la base
 * ({@code dev.subnetory.backup.BackupExecutionService#restore}).
 *
 * <p>Une session Web Thymeleaf existante conserve en memoire (contexte de
 * securite Spring) les autorites/roles charges au moment de la connexion —
 * si une restauration a change les roles de l'utilisateur entre-temps
 * (retour a un etat anterieur), la session reste privilegiee selon l'ancien
 * etat jusqu'a son expiration naturelle. {@link #expireAllSessions()} force
 * chaque session encore ouverte a etre consideree comme expiree : la
 * prochaine requete de l'utilisateur echoue avec une redirection vers
 * {@code /login?expired} (voir {@code SecurityConfig#webFilterChain},
 * {@code invalidSessionUrl}), sans etat intermediaire silencieusement
 * incorrect.</p>
 */
@Service
public class SessionInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(SessionInvalidationService.class);

    private final SessionRegistry sessionRegistry;

    public SessionInvalidationService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /** Marque comme expirees toutes les sessions Web actuellement enregistrees. */
    public int expireAllSessions() {
        int count = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                session.expireNow();
                count++;
            }
        }
        if (count > 0) {
            log.warn("Toutes les sessions Web actives ({}) marquees comme expirees suite a une restauration.", count);
        }
        return count;
    }
}
