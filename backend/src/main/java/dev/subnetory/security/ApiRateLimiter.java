package dev.subnetory.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter in-memory generalise a l'ensemble de l'API (Sprint 2.36 / F7).
 *
 * <p>Complementaire a {@link LoginRateLimiter} (correctif H2), qui ne couvre
 * que les tentatives d'authentification avec un seuil volontairement strict.
 * Ce limiteur couvre l'ensemble de la surface {@code /api/v1/**} avec un
 * seuil beaucoup plus genereux, pense pour ne pas gener un usage normal
 * (tableau de bord, imports en masse) tout en bornant un abus ou un script
 * mal ecrit tapant l'API en boucle.</p>
 *
 * <p>Fenetre fixe par IP : un compteur est incremente a chaque requete: des
 * qu'une nouvelle fenetre commence pour cette IP (fenetre precedente expiree),
 * le compteur repart de zero. Stockage in-memory uniquement, coherent avec le
 * choix deja fait pour {@link LoginRateLimiter} sur une architecture
 * mono-replique.</p>
 */
@Service
public class ApiRateLimiter {

    /** Marge de nettoyage : entrees purgees au-dela de 2 fenetres d'inactivite. */
    private static final int CLEANUP_WINDOW_MULTIPLIER = 2;

    private final Clock clock;
    private final int maxRequestsPerWindow;
    private final Duration window;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public ApiRateLimiter(
            @Value("${subnetory.security.api-rate-limit.requests-per-window:300}") int maxRequestsPerWindow,
            @Value("${subnetory.security.api-rate-limit.window-seconds:60}") long windowSeconds) {
        this(Clock.systemUTC(), maxRequestsPerWindow, windowSeconds);
    }

    ApiRateLimiter(Clock clock, int maxRequestsPerWindow, long windowSeconds) {
        if (maxRequestsPerWindow < 1) {
            throw new IllegalArgumentException("requests-per-window doit etre >= 1");
        }
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("window-seconds doit etre >= 1");
        }
        this.clock = clock;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * Enregistre une requete pour l'IP donnee et indique si elle doit etre
     * bloquee (fenetre depassee).
     */
    public Decision recordRequest(String ipAddress) {
        String key = normalizeIp(ipAddress);
        Instant now = Instant.now(clock);

        Bucket updated = buckets.compute(key, (ignored, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart.plus(window))) {
                return new Bucket(now, 1);
            }
            return new Bucket(existing.windowStart, existing.count + 1);
        });

        cleanupExpiredEntries(now);

        if (updated.count > maxRequestsPerWindow) {
            long retryAfterSeconds = Duration.between(now, updated.windowStart.plus(window)).toSeconds();
            return Decision.limited(Math.max(retryAfterSeconds, 1));
        }

        return Decision.allowed();
    }

    /** Visible pour les tests unitaires. */
    int getCurrentCount(String ipAddress) {
        Bucket bucket = buckets.get(normalizeIp(ipAddress));
        return bucket == null ? 0 : bucket.count;
    }

    private void cleanupExpiredEntries(Instant now) {
        Duration ttl = window.multipliedBy(CLEANUP_WINDOW_MULTIPLIER);
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            if (entry.getValue().windowStart.plus(ttl).isBefore(now)) {
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

    private record Bucket(Instant windowStart, int count) {
    }

    public record Decision(boolean limited, long retryAfterSeconds) {

        static Decision allowed() {
            return new Decision(false, 0);
        }

        static Decision limited(long retryAfterSeconds) {
            return new Decision(true, retryAfterSeconds);
        }
    }
}
