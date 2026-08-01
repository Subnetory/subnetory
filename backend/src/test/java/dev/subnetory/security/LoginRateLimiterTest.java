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