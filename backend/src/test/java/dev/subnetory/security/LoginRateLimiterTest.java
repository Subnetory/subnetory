package dev.subnetory.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private static final String IP = "192.0.2.10";

    @Test
    void firstFailures_areAllowedBeforeDelayThreshold() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 1; i < LoginRateLimiter.DELAY_THRESHOLD; i++) {
            LoginRateLimiter.RateLimitDecision decision = limiter.recordFailure(IP);

            assertThat(decision.delayed()).isFalse();
            assertThat(decision.locked()).isFalse();
            assertThat(limiter.getFailureCount(IP)).isEqualTo(i);
        }
    }

    @Test
    void fifthFailure_returnsDelayDecision() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 1; i < LoginRateLimiter.DELAY_THRESHOLD; i++) {
            limiter.recordFailure(IP);
        }

        LoginRateLimiter.RateLimitDecision decision = limiter.recordFailure(IP);

        assertThat(decision.delayed()).isTrue();
        assertThat(decision.locked()).isFalse();
        assertThat(decision.waitDuration()).isEqualTo(LoginRateLimiter.DELAY_DURATION);
        assertThat(limiter.getFailureCount(IP)).isEqualTo(LoginRateLimiter.DELAY_THRESHOLD);
    }

    @Test
    void tenthFailure_locksIpForFifteenMinutes() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 1; i < LoginRateLimiter.LOCK_THRESHOLD; i++) {
            limiter.recordFailure(IP);
        }

        LoginRateLimiter.RateLimitDecision decision = limiter.recordFailure(IP);

        assertThat(decision.locked()).isTrue();
        assertThat(decision.delayed()).isFalse();
        assertThat(decision.waitDuration()).isEqualTo(LoginRateLimiter.LOCK_DURATION);
        assertThat(limiter.isLocked(IP)).isTrue();
        assertThat(limiter.getRemainingLockSeconds(IP)).isGreaterThan(0);
    }

    @Test
    void successfulLogin_resetsCounter() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        limiter.recordFailure(IP);
        limiter.recordFailure(IP);
        limiter.recordFailure(IP);

        assertThat(limiter.getFailureCount(IP)).isEqualTo(3);

        limiter.recordSuccess(IP);

        assertThat(limiter.getFailureCount(IP)).isZero();
        assertThat(limiter.isLocked(IP)).isFalse();
    }

    @Test
    void lockExpiresAfterConfiguredDuration() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        for (int i = 0; i < LoginRateLimiter.LOCK_THRESHOLD; i++) {
            limiter.recordFailure(IP);
        }

        assertThat(limiter.isLocked(IP)).isTrue();

        clock.advance(LoginRateLimiter.LOCK_DURATION.plusSeconds(1));

        assertThat(limiter.isLocked(IP)).isFalse();
        assertThat(limiter.getFailureCount(IP)).isZero();
    }

    @Test
    void nullOrBlankIp_usesUnknownBucket() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        limiter.recordFailure(null);
        limiter.recordFailure("   ");

        assertThat(limiter.getFailureCount("unknown")).isEqualTo(2);
    }

    // -------------------------------------------------------
    // Compteur par nom d'utilisateur (audit 02/08/2026, correctif MOYENNE)
    // -------------------------------------------------------

    @Test
    void usernameLockThreshold_locksAcrossDifferentIps() {
        // Meme compte cible depuis des IP toutes differentes : chaque IP
        // reste individuellement sous le seuil, mais le compteur par
        // utilisateur doit quand meme se declencher.
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        String username = "alice";

        LoginRateLimiter.RateLimitDecision lastDecision = null;
        for (int i = 1; i <= LoginRateLimiter.LOCK_THRESHOLD; i++) {
            lastDecision = limiter.recordFailure("203.0.113." + i, username);
        }

        assertThat(lastDecision.locked()).isTrue();
        assertThat(limiter.getFailureCountByUsername(username)).isEqualTo(LoginRateLimiter.LOCK_THRESHOLD);
        // Chaque IP individuelle est restee tres en dessous du seuil.
        assertThat(limiter.getFailureCount("203.0.113.1")).isEqualTo(1);
    }

    @Test
    void isLocked_withUsername_reflectsUsernameLockEvenFromNewIp() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        String username = "bob";

        for (int i = 1; i <= LoginRateLimiter.LOCK_THRESHOLD; i++) {
            limiter.recordFailure("198.51.100." + i, username);
        }

        // Nouvelle IP jamais vue, mais meme compte : doit rester verrouille.
        assertThat(limiter.isLocked("198.51.100.250", username)).isTrue();
        // Sans le nom d'utilisateur, cette IP neuve n'est pas verrouillee
        // (comportement historique IP-seul inchange).
        assertThat(limiter.isLocked("198.51.100.250")).isFalse();
    }

    @Test
    void usernameNormalization_isCaseInsensitive() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);

        limiter.recordFailure(IP, "Admin");
        limiter.recordFailure(IP, "ADMIN");
        limiter.recordFailure(IP, "admin");

        assertThat(limiter.getFailureCountByUsername("admin")).isEqualTo(3);
    }

    @Test
    void recordSuccess_withUsername_resetsBothCounters() {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        String username = "carol";

        limiter.recordFailure(IP, username);
        limiter.recordFailure(IP, username);
        assertThat(limiter.getFailureCount(IP)).isEqualTo(2);
        assertThat(limiter.getFailureCountByUsername(username)).isEqualTo(2);

        limiter.recordSuccess(IP, username);

        assertThat(limiter.getFailureCount(IP)).isZero();
        assertThat(limiter.getFailureCountByUsername(username)).isZero();
    }

    /**
     * Regression (audit du 03/08/2026, correctif MOYEN) : {@code
     * failureCount++} sur l'objet {@link LoginRateLimiter} mutable partage
     * n'etait pas atomique malgre le {@link java.util.concurrent.ConcurrentHashMap}
     * sous-jacent (celui-ci ne protege que ses propres operations, pas
     * l'objet qu'il contient). Des echecs concurrents sur la meme IP
     * pouvaient donc perdre des incrementations. Ce test lance
     * {@code LOCK_THRESHOLD} echecs en parallele sur la meme IP et verifie
     * qu'aucun n'est perdu : le compteur final doit valoir exactement
     * {@code LOCK_THRESHOLD}, pas moins.
     */
    @Test
    void recordFailure_concurrentCallsOnSameKey_neverLosesAnIncrement() throws InterruptedException {
        MutableClock clock = new MutableClock();
        LoginRateLimiter limiter = new LoginRateLimiter(clock);
        int callers = LoginRateLimiter.LOCK_THRESHOLD;

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(callers);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    limiter.recordFailure(IP);
                }));
            }
            for (var future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        } finally {
            pool.shutdown();
        }

        assertThat(limiter.getFailureCount(IP)).isEqualTo(callers);
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-06-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}