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
        cleanupExpiredEntries();

        LoginAttempt attempt = attempts.get(normalizeIp(ipAddress));
        if (attempt == null || attempt.lockedUntil == null) {
            return false;
        }

        if (Instant.now(clock).isBefore(attempt.lockedUntil)) {
            return true;
        }

        attempts.remove(normalizeIp(ipAddress));
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
     * Enregistre un echec de login.
     */
    public RateLimitDecision recordFailure(String ipAddress) {
        cleanupExpiredEntries();

        String key = normalizeIp(ipAddress);
        Instant now = Instant.now(clock);

        LoginAttempt attempt = attempts.computeIfAbsent(key, ignored -> new LoginAttempt());
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

    /**
     * Remet a zero le compteur apres une authentification reussie.
     */
    public void recordSuccess(String ipAddress) {
        attempts.remove(normalizeIp(ipAddress));
    }

    /**
     * Visible pour les tests unitaires.
     */
    int getFailureCount(String ipAddress) {
        LoginAttempt attempt = attempts.get(normalizeIp(ipAddress));
        return attempt == null ? 0 : attempt.failureCount;
    }

    private void cleanupExpiredEntries() {
        Instant now = Instant.now(clock);
        Iterator<Map.Entry<String, LoginAttempt>> iterator = attempts.entrySet().iterator();

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