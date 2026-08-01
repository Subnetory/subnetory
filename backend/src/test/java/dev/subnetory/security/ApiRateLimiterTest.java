package dev.subnetory.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiRateLimiterTest {

    private static final String IP = "203.0.113.10";

    @Test
    void requestsBelowThreshold_areAllowed() {
        MutableClock clock = new MutableClock();
        ApiRateLimiter limiter = new ApiRateLimiter(clock, 3, 60);

        assertThat(limiter.recordRequest(IP).limited()).isFalse();
        assertThat(limiter.recordRequest(IP).limited()).isFalse();
        assertThat(limiter.recordRequest(IP).limited()).isFalse();
        assertThat(limiter.getCurrentCount(IP)).isEqualTo(3);
    }

    @Test
    void requestBeyondThreshold_isLimited() {
        MutableClock clock = new MutableClock();
        ApiRateLimiter limiter = new ApiRateLimiter(clock, 3, 60);

        limiter.recordRequest(IP);
        limiter.recordRequest(IP);
        limiter.recordRequest(IP);

        ApiRateLimiter.Decision decision = limiter.recordRequest(IP);

        assertThat(decision.limited()).isTrue();
        assertThat(decision.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    void counterResets_afterWindowExpires() {
        MutableClock clock = new MutableClock();
        ApiRateLimiter limiter = new ApiRateLimiter(clock, 2, 60);

        limiter.recordRequest(IP);
        limiter.recordRequest(IP);
        assertThat(limiter.recordRequest(IP).limited()).isTrue();

        clock.advance(Duration.ofSeconds(61));

        assertThat(limiter.recordRequest(IP).limited()).isFalse();
        assertThat(limiter.getCurrentCount(IP)).isEqualTo(1);
    }

    @Test
    void differentIps_haveIndependentCounters() {
        MutableClock clock = new MutableClock();
        ApiRateLimiter limiter = new ApiRateLimiter(clock, 1, 60);

        assertThat(limiter.recordRequest("198.51.100.1").limited()).isFalse();
        assertThat(limiter.recordRequest("198.51.100.2").limited()).isFalse();
        assertThat(limiter.recordRequest("198.51.100.1").limited()).isTrue();
    }

    @Test
    void nullOrBlankIp_usesUnknownBucket() {
        MutableClock clock = new MutableClock();
        ApiRateLimiter limiter = new ApiRateLimiter(clock, 5, 60);

        limiter.recordRequest(null);
        limiter.recordRequest("   ");

        assertThat(limiter.getCurrentCount("unknown")).isEqualTo(2);
    }

    @Test
    void invalidConfiguration_isRejectedAtConstruction() {
        assertThatThrownBy(() -> new ApiRateLimiter(Clock.systemUTC(), 0, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiRateLimiter(Clock.systemUTC(), 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
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
