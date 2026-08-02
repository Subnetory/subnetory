package dev.subnetory.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter in-memory pour les tentatives de login.
 *
 * Politique Sprint 2.13 / T4 :
 * - compteur par adresse IP ;
 * - a partir de 5 echecs : delai de 5 secondes ;
 * - a partir de 10 echecs : verrouillage 15 minutes ;
 * - remise a zero sur succes ;
 * - stockage in-memory uniquement, acceptable pour le MVP.
 *
 * <p>Compteur par nom d'utilisateur (audit du 02/08/2026, correctif MOYENNE) :
 * le compteur par IP seul ne protege pas un compte cible depuis de nombreuses
 * IP differentes (botnet, proxys residentiels tournants) — chaque IP reste
 * individuellement sous les seuils meme si le meme compte encaisse des
 * centaines de tentatives au total. Les methodes historiques a un seul
 * argument (IP) restent strictement inchangees pour compatibilite (tests,
 * appelants existants) ; les nouvelles surcharges a deux arguments
 * verrouillent si l'IP OU le nom d'utilisateur normalise depasse son propre
 * seuil, avec un compteur independant par dimension.</p>
 */
@Service
public class LoginRateLimiter {

    static final int DELAY_THRESHOLD = 5;
    static final int LOCK_THRESHOLD = 10;
    static final Duration DELAY_DURATION = Duration.ofSeconds(5);
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    static final Duration ENTRY_TTL = Duration.ofMinutes(30);

    private final Clock clock;
    private final Map<String, LoginAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<String, LoginAttempt> attemptsByUsername = new ConcurrentHashMap<>();

    public LoginRateLimiter() {
        this(Clock.systemUTC());
    }

    LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Indique si une IP est actuellement verrouillee.
     */
    public boolean isLocked(String ipAddress) {
        return isLocked(attempts, normalizeIp(ipAddress));
    }

    /**
     * Indique si l'IP OU le nom d'utilisateur est actuellement verrouille.
     */
    public boolean isLocked(String ipAddress, String username) {
        return isLocked(ipAddress) || (username != null && isLocked(attemptsByUsername, normalizeUsername(username)));
    }

    private boolean isLocked(Map<String, LoginAttempt> bucket, String key) {
        cleanupExpiredEntries(bucket);

        LoginAttempt attempt = bucket.get(key);
        if (attempt == null || attempt.lockedUntil == null) {
            return false;
        }

        if (Instant.now(clock).isBefore(attempt.lockedUntil)) {
            return true;
        }

        bucket.remove(key);
        return false;
    }

    /**
     * Retourne la duree restante de verrouillage en secondes.
     */
    public long getRemainingLockSeconds(String ipAddress) {
        LoginAttempt attempt = attempts.get(normalizeIp(ipAddress));
        if (attempt == null || attempt.lockedUntil == null) {
            return 0;
        }

        long seconds = Duration.between(Instant.now(clock), attempt.lockedUntil).toSeconds();
        return Math.max(seconds, 0);
    }

    /**
     * Enregistre un echec de login pour une IP.
     */
    public RateLimitDecision recordFailure(String ipAddress) {
        return recordFailure(attempts, normalizeIp(ipAddress));
    }

    /**
     * Enregistre un echec de login pour une IP ET un nom d'utilisateur,
     * chacun avec son propre compteur independant. La decision retournee
     * (delai/verrouillage) est la plus severe des deux dimensions, pour que
     * l'appelant applique toujours la protection la plus stricte applicable.
     */
    public RateLimitDecision recordFailure(String ipAddress, String username) {
        RateLimitDecision ipDecision = recordFailure(ipAddress);
        if (username == null) {
            return ipDecision;
        }
        RateLimitDecision usernameDecision = recordFailure(attemptsByUsername, normalizeUsername(username));
        return moreSevere(ipDecision, usernameDecision);
    }

    private RateLimitDecision recordFailure(Map<String, LoginAttempt> bucket, String key) {
        cleanupExpiredEntries(bucket);

        Instant now = Instant.now(clock);

        LoginAttempt attempt = bucket.computeIfAbsent(key, ignored -> new LoginAttempt());
        attempt.failureCount++;
        attempt.lastFailureAt = now;

        if (attempt.failureCount >= LOCK_THRESHOLD) {
            attempt.lockedUntil = now.plus(LOCK_DURATION);
            return RateLimitDecision.locked(LOCK_DURATION);
        }

        if (attempt.failureCount >= DELAY_THRESHOLD) {
            return RateLimitDecision.delayed(DELAY_DURATION);
        }

        return RateLimitDecision.allowed();
    }

    private static RateLimitDecision moreSevere(RateLimitDecision a, RateLimitDecision b) {
        if (a.locked() || b.locked()) {
            return a.locked() ? a : b;
        }
        if (a.delayed() || b.delayed()) {
            return a.delayed() ? a : b;
        }
        return RateLimitDecision.allowed();
    }

    /**
     * Remet a zero le compteur IP apres une authentification reussie.
     */
    public void recordSuccess(String ipAddress) {
        attempts.remove(normalizeIp(ipAddress));
    }

    /**
     * Remet a zero les compteurs IP et nom d'utilisateur apres une
     * authentification reussie.
     */
    public void recordSuccess(String ipAddress, String username) {
        recordSuccess(ipAddress);
        if (username != null) {
            attemptsByUsername.remove(normalizeUsername(username));
        }
    }

    /**
     * Visible pour les tests unitaires.
     */
    int getFailureCount(String ipAddress) {
        LoginAttempt attempt = attempts.get(normalizeIp(ipAddress));
        return attempt == null ? 0 : attempt.failureCount;
    }

    /**
     * Visible pour les tests unitaires.
     */
    int getFailureCountByUsername(String username) {
        LoginAttempt attempt = attemptsByUsername.get(normalizeUsername(username));
        return attempt == null ? 0 : attempt.failureCount;
    }

    private void cleanupExpiredEntries(Map<String, LoginAttempt> bucket) {
        Instant now = Instant.now(clock);
        Iterator<Map.Entry<String, LoginAttempt>> iterator = bucket.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, LoginAttempt> entry = iterator.next();
            LoginAttempt attempt = entry.getValue();

            boolean lockExpired = attempt.lockedUntil != null && !now.isBefore(attempt.lockedUntil);
            boolean stale = attempt.lastFailureAt != null
                    && attempt.lastFailureAt.plus(ENTRY_TTL).isBefore(now);

            if (lockExpired || stale) {
                iterator.remove();
            }
        }
    }

    private String normalizeIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return "unknown";
        }
        return ipAddress.trim();
    }

    /**
     * Normalise en minuscules, independamment de la sensibilite a la casse
     * de l'authentification elle-meme (SubnetoryUserDetailsService fait une
     * recherche exacte via findByUsername), pour eviter qu'un attaquant
     * fasse compter ses tentatives sur des cles differentes ("Admin",
     * "ADMIN", "admin"...) et dilue ainsi artificiellement le compteur par
     * nom d'utilisateur sur plusieurs seaux au lieu d'un seul.
     */
    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return "unknown";
        }
        return username.trim().toLowerCase();
    }

    private static final class LoginAttempt {
        private int failureCount;
        private Instant lastFailureAt;
        private Instant lockedUntil;
    }

    public record RateLimitDecision(boolean delayed, boolean locked, Duration waitDuration) {

        static RateLimitDecision allowed() {
            return new RateLimitDecision(false, false, Duration.ZERO);
        }

        static RateLimitDecision delayed(Duration duration) {
            return new RateLimitDecision(true, false, duration);
        }

        static RateLimitDecision locked(Duration duration) {
            return new RateLimitDecision(false, true, duration);
        }
    }
}